package io.github.myungjoo.nntesla.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.tesla.generated.carserver.server.CarServer
import com.tesla.generated.carserver.vehicle.Vehicle.VehicleData
import com.tesla.generated.keys.Keys
import com.tesla.generated.universalmessage.UniversalMessage.Domain
import com.tesla.generated.universalmessage.UniversalMessage.MessageFault_E
import com.tesla.generated.universalmessage.UniversalMessage.OperationStatus_E
import com.tesla.generated.universalmessage.UniversalMessage.RoutableMessage
import io.github.myungjoo.nntesla.protocol.BleFraming
import io.github.myungjoo.nntesla.protocol.EcdhKey
import io.github.myungjoo.nntesla.protocol.KeyNotPairedException
import io.github.myungjoo.nntesla.protocol.Messages
import io.github.myungjoo.nntesla.protocol.Session
import io.github.myungjoo.nntesla.protocol.StateCategory
import java.security.SecureRandom

/**
 * Connects to one vehicle over BLE, runs the infotainment handshake and executes
 * `GetVehicleData` requests. Requests may be pipelined; responses are matched by request UUID.
 *
 * Threading: everything runs on a dedicated high-priority thread ([handler]). Listener and request
 * callbacks are invoked on that thread; hop to another thread only where needed (e.g. UI).
 */
@SuppressLint("MissingPermission") // Callers obtain BLUETOOTH_SCAN / BLUETOOTH_CONNECT first.
class TeslaVehicleClient(
    context: Context,
    private val vin: String,
    private val key: EcdhKey,
    private val listener: Listener,
) {
    enum class State { IDLE, SCANNING, CONNECTING, HANDSHAKING, NEEDS_PAIRING, READY, FAILED }

    interface Listener {
        fun onStateChanged(state: State, detail: String)
    }

    sealed interface Outcome {
        class Ok(val data: VehicleData) : Outcome
        class Failed(val reason: String) : Outcome
        data object Timeout : Outcome
    }

    /** Timing of one request, all in System.nanoTime(). Zero means "did not happen". */
    class Result(
        val categories: Set<StateCategory>,
        val sendNanos: Long,
        val writtenNanos: Long,
        val firstRxNanos: Long,
        val completeNanos: Long,
        val decodedNanos: Long,
        val requestBytes: Int,
        val responseBytes: Int,
        val outcome: Outcome,
    )

    private val appContext = context.applicationContext
    private val thread = HandlerThread("tesla-ble", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    val handler = Handler(thread.looper)
    private val random = SecureRandom()
    private val routingAddress = ByteArray(16).also(random::nextBytes)
    private val link = BleLink(appContext, handler, LinkListener())

    private var state = State.IDLE
    private var session: Session? = null
    private var mtu = 0
    private var handshakeAttempts = 0

    private class Pending(
        val uuid: ByteArray,
        val requestHash: ByteArray?,
        val categories: Set<StateCategory>,
        val sendNanos: Long,
        val requestBytes: Int,
        val callback: (Result) -> Unit,
    ) {
        var writtenNanos = 0L
        lateinit var timeout: Runnable
    }

    private val pending = HashMap<String, Pending>()
    private var handshakeUuid: ByteArray? = null

    val negotiatedMtu: Int get() = mtu
    val inFlight: Int get() = pending.size

    /**
     * Connects to the vehicle. With [autoReconnect], any failure or link loss (vehicle out of
     * range, asleep, BLE error) is retried with backoff until [stop] is called. States that need
     * the user (pairing) are not retried, except that pairing completion is polled for.
     */
    fun start(autoReconnect: Boolean = true) = handler.post {
        this.autoReconnect = autoReconnect
        handler.removeCallbacks(reconnect)
        if (state != State.IDLE && state != State.FAILED) return@post
        scan()
    }

    fun stop() = handler.post {
        autoReconnect = false
        pairingDeadlineNanos = 0L
        handler.removeCallbacks(reconnect)
        handler.removeCallbacks(pairingPoll)
        stopScan()
        cancelLinkTimers()
        link.close()
        // 대기 중인 콜백(스케줄러)이 곧바로 재요청하므로, 상태를 먼저 내려 READY가 아님을 보이게 한다.
        session = null
        setState(State.IDLE, "stopped")
        failAllPending("stopped")
    }

    /** Retries the infotainment handshake (e.g. after the key was paired). */
    fun retryHandshake() = handler.post {
        if (state == State.NEEDS_PAIRING || state == State.READY) {
            handshakeAttempts = 0
            startHandshake()
        }
    }

    /**
     * Sends an add-key request; the vehicle then waits for an owner keycard tap on the console.
     * Least privilege: a cluster only needs to read data, so VEHICLE_MONITOR is the default.
     */
    fun requestPairing(role: Keys.Role = Keys.Role.ROLE_VEHICLE_MONITOR) = handler.post {
        if (state != State.NEEDS_PAIRING && state != State.READY && state != State.HANDSHAKING) return@post
        link.send(Messages.addKeyRequest(key.publicKeyBytes, role))
        pairingDeadlineNanos = System.nanoTime() + PAIRING_WINDOW_MS * 1_000_000L
        setState(State.NEEDS_PAIRING, "pairing request sent - tap an owner keycard on the console")
    }

    // --- reconnect & pairing completion ------------------------------------------------------

    private var autoReconnect = false
    private var backoffIndex = 0
    private var pairingDeadlineNanos = 0L

    private val reconnect = Runnable {
        if (!autoReconnect || state != State.FAILED) return@Runnable
        // The link may still be up (e.g. handshake failed); start from a clean slate.
        stopScan()
        cancelLinkTimers()
        link.close()
        session = null
        failAllPending("reconnecting")
        scan()
    }

    /** While a pairing request is outstanding, re-try the handshake until the key is accepted. */
    private val pairingPoll = Runnable {
        if (state == State.NEEDS_PAIRING && System.nanoTime() < pairingDeadlineNanos) {
            handshakeAttempts = 0
            startHandshake()
        }
    }

    /**
     * Requests [categories] in one `GetVehicleData`. Must be called on [handler]'s thread while
     * [State.READY]. [callback] runs exactly once on that thread.
     */
    fun requestState(categories: Set<StateCategory>, timeoutMillis: Long, callback: (Result) -> Unit) {
        val s = session
        val sendNanos = System.nanoTime()
        if (state != State.READY || s == null || !link.isReady) {
            callback(Result(categories, sendNanos, 0, 0, 0, 0, 0, 0, Outcome.Failed("not ready")))
            return
        }
        val uuid = ByteArray(16).also(random::nextBytes)
        val authorized = s.authorize(Messages.getVehicleData(categories), routingAddress, uuid)
        val encoded = authorized.message.toByteArray()
        val p = Pending(uuid, authorized.requestHash, categories, sendNanos, encoded.size, callback)
        p.timeout = Runnable {
            if (pending.remove(uuid.hex()) != null) {
                callback(Result(categories, sendNanos, p.writtenNanos, 0, 0, 0, encoded.size, 0, Outcome.Timeout))
            }
        }
        pending[uuid.hex()] = p
        handler.postDelayed(p.timeout, timeoutMillis)
        link.send(encoded) { p.writtenNanos = it }
    }

    fun shutdown() {
        stop()
        thread.quitSafely()
    }

    // --- scanning ---------------------------------------------------------------------------

    private val scanner get() = appContext.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
    private var scanCallback: ScanCallback? = null
    private val scanTimeout = Runnable {
        stopScan()
        setState(State.FAILED, "vehicle not found (is it nearby and awake?)")
    }

    private fun scan() {
        val leScanner = scanner ?: return setState(State.FAILED, "Bluetooth is off or unavailable")
        val localName = BleFraming.localName(vin)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                handler.post { onScanResultOnThread(result) }
            }

            override fun onScanFailed(errorCode: Int) {
                handler.post { setState(State.FAILED, "scan failed ($errorCode)") }
            }
        }
        scanCallback = cb
        setState(State.SCANNING, "looking for $localName")
        leScanner.startScan(
            listOf(ScanFilter.Builder().setDeviceName(localName).build()),
            ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
            cb,
        )
        handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    private fun onScanResultOnThread(result: ScanResult) {
        if (state != State.SCANNING) return
        stopScan()
        if (!result.isConnectable) {
            setState(State.FAILED, "vehicle is already connected to the maximum number of BLE devices")
            return
        }
        setState(State.CONNECTING, "connecting to ${result.device.address}")
        link.connect(result.device)
        handler.postDelayed(connectTimeout, CONNECT_TIMEOUT_MS)
    }

    /**
     * GATT 연결 뒤 MTU 협상, 서비스 탐색, RX 구독 중 어느 콜백이든 오지 않으면 CONNECTING에서 영원히
     * 멈춘다. 일정 시간 안에 링크가 준비되지 않으면 끊고 FAILED로 보내 자동 재연결에 맡긴다.
     */
    private val connectTimeout = Runnable {
        if (state != State.CONNECTING) return@Runnable
        link.close()
        session = null
        setState(State.FAILED, "BLE connection setup timed out")
    }

    /** 이전 링크에 묶인 타이머를 정리한다. 남겨 두면 다음 연결에서 엉뚱한 시점에 발화한다. */
    private fun cancelLinkTimers() {
        handler.removeCallbacks(connectTimeout)
        handler.removeCallbacks(handshakeTimeout)
        handshakeUuid = null
    }

    private fun stopScan() {
        handler.removeCallbacks(scanTimeout)
        scanCallback?.let { cb -> runCatching { scanner?.stopScan(cb) } }
        scanCallback = null
    }

    // --- handshake & responses --------------------------------------------------------------

    private fun startHandshake() {
        handshakeAttempts += 1
        if (handshakeAttempts > MAX_HANDSHAKE_ATTEMPTS) {
            setState(State.FAILED, "infotainment did not answer the handshake (is the car awake?)")
            return
        }
        val uuid = ByteArray(16).also(random::nextBytes)
        handshakeUuid = uuid
        setState(State.HANDSHAKING, "handshake attempt $handshakeAttempts")
        link.send(Messages.sessionInfoRequest(Domain.DOMAIN_INFOTAINMENT, key.publicKeyBytes, routingAddress, uuid).toByteArray())
        // 시도마다 타이머는 하나만 둔다(재시도·페어링 폴링이 겹쳐도 시도 횟수가 두 배로 소모되지 않게).
        handler.removeCallbacks(handshakeTimeout)
        handler.postDelayed(handshakeTimeout, HANDSHAKE_TIMEOUT_MS)
    }

    private val handshakeTimeout = Runnable {
        if (state == State.HANDSHAKING) startHandshake()
    }

    private fun onMessage(bytes: ByteArray, firstRx: Long, complete: Long) {
        val message = try {
            RoutableMessage.parseFrom(bytes)
        } catch (e: Exception) {
            return // e.g. raw VCSEC replies to a pairing request
        }
        val requestUuid = message.requestUuid.toByteArray()
        val hs = handshakeUuid
        if (hs != null && requestUuid.contentEquals(hs)) {
            onHandshakeReply(message, hs)
            return
        }
        val p = pending.remove(requestUuid.hex()) ?: return
        handler.removeCallbacks(p.timeout)
        val outcome = decode(message, p)
        p.callback(
            Result(
                p.categories, p.sendNanos, p.writtenNanos, firstRx, complete, System.nanoTime(),
                p.requestBytes, bytes.size, outcome,
            ),
        )
    }

    private fun decode(message: RoutableMessage, p: Pending): Outcome {
        val s = session ?: return Outcome.Failed("no session")
        val status = message.signedMessageStatus
        if (status.signedMessageFault != MessageFault_E.MESSAGEFAULT_ERROR_NONE) {
            if (message.hasSessionInfo() && message.signatureData.hasSessionInfoTag()) {
                runCatching {
                    s.updateSignedSessionInfo(p.uuid, message.sessionInfo.toByteArray(), message.signatureData.sessionInfoTag.tag.toByteArray())
                }
            }
            return Outcome.Failed(status.signedMessageFault.name)
        }
        if (status.operationStatus == OperationStatus_E.OPERATIONSTATUS_WAIT) return Outcome.Failed("vehicle busy")
        return try {
            val payload = s.decryptResponse(message, p.requestHash!!).payload
            val response = CarServer.Response.parseFrom(payload)
            if (response.actionStatus.result == CarServer.OperationStatus_E.OPERATIONSTATUS_ERROR) {
                Outcome.Failed("vehicle error: " + response.actionStatus.resultReason.plainText)
            } else {
                Outcome.Ok(response.vehicleData)
            }
        } catch (e: Exception) {
            Outcome.Failed("decode error: ${e.message}")
        }
    }

    private fun onHandshakeReply(message: RoutableMessage, challenge: ByteArray) {
        handler.removeCallbacks(handshakeTimeout)
        handshakeUuid = null
        // An unpaired key is reported either as this fault or as a SessionInfo status
        // (KEY_NOT_ON_WHITELIST, handled below); upstream treats both as "not paired".
        when (message.signedMessageStatus.signedMessageFault) {
            MessageFault_E.MESSAGEFAULT_ERROR_UNKNOWN_KEY_ID -> {
                setState(State.NEEDS_PAIRING, "this phone's key is not paired with the vehicle")
                return
            }
            MessageFault_E.MESSAGEFAULT_ERROR_INACTIVE_KEY -> {
                setState(State.NEEDS_PAIRING, "this phone's key is disabled on the vehicle; re-pair it")
                return
            }
            else -> Unit
        }
        if (!message.hasSessionInfo()) {
            setState(State.FAILED, "handshake rejected: ${message.signedMessageStatus.signedMessageFault}")
            return
        }
        try {
            session = Session.fromHandshake(
                Domain.DOMAIN_INFOTAINMENT, vin, key, challenge,
                message.sessionInfo.toByteArray(),
                message.signatureData.sessionInfoTag.tag.toByteArray(),
            )
            handshakeAttempts = 0
            setState(State.READY, "session established (MTU $mtu)")
        } catch (e: KeyNotPairedException) {
            setState(State.NEEDS_PAIRING, "this phone's key is not paired with the vehicle")
        } catch (e: Exception) {
            setState(State.FAILED, "handshake failed: ${e.message}")
        }
    }

    private fun failAllPending(reason: String) {
        val all = pending.values.toList()
        pending.clear()
        for (p in all) {
            handler.removeCallbacks(p.timeout)
            p.callback(Result(p.categories, p.sendNanos, p.writtenNanos, 0, 0, 0, p.requestBytes, 0, Outcome.Failed(reason)))
        }
    }

    private fun setState(newState: State, detail: String) {
        state = newState
        var shown = detail
        when (newState) {
            State.READY -> {
                backoffIndex = 0
                pairingDeadlineNanos = 0L
            }
            State.FAILED -> if (autoReconnect) {
                val delay = RECONNECT_BACKOFF_MS[minOf(backoffIndex, RECONNECT_BACKOFF_MS.lastIndex)]
                backoffIndex += 1
                handler.removeCallbacks(reconnect)
                handler.postDelayed(reconnect, delay)
                shown = "$detail - retrying in ${delay / 1000.0} s"
            }
            State.NEEDS_PAIRING -> if (System.nanoTime() < pairingDeadlineNanos) {
                handler.removeCallbacks(pairingPoll)
                handler.postDelayed(pairingPoll, PAIRING_POLL_MS)
            }
            else -> Unit
        }
        listener.onStateChanged(newState, shown)
    }

    private inner class LinkListener : BleLink.Listener {
        override fun onLinkReady(mtu: Int) {
            handler.removeCallbacks(connectTimeout)
            this@TeslaVehicleClient.mtu = mtu
            handshakeAttempts = 0
            startHandshake()
        }

        override fun onLinkMessage(message: ByteArray, firstChunkNanos: Long, completeNanos: Long) {
            onMessage(message, firstChunkNanos, completeNanos)
        }

        override fun onLinkClosed(reason: String) {
            // 상태를 먼저 FAILED로 바꾼 뒤 대기 요청을 실패 처리한다. 순서가 반대면 콜백이
            // 아직 READY인 클라이언트에 재요청해, 이미 닫힌 링크에서 예외가 난다.
            cancelLinkTimers()
            session = null
            setState(State.FAILED, reason)
            failAllPending(reason)
        }
    }

    companion object {
        private const val SCAN_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val HANDSHAKE_TIMEOUT_MS = 1_500L
        private const val MAX_HANDSHAKE_ATTEMPTS = 5
        private val RECONNECT_BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
        private const val PAIRING_POLL_MS = 3_000L
        private const val PAIRING_WINDOW_MS = 120_000L

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
