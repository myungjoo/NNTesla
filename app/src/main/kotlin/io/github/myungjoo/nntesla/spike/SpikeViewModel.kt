package io.github.myungjoo.nntesla.spike

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import android.view.Display
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tesla.generated.keys.Keys
import io.github.myungjoo.nntesla.ble.KeystoreEcdhKey
import io.github.myungjoo.nntesla.ble.PollingScheduler
import io.github.myungjoo.nntesla.ble.TeslaBleDataSource
import io.github.myungjoo.nntesla.ble.TeslaVehicleClient
import io.github.myungjoo.nntesla.vehicledata.FastState
import io.github.myungjoo.nntesla.vehicledata.SlowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SpikeViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("spike", Context.MODE_PRIVATE)
    private val key = KeystoreEcdhKey.loadOrCreate()
    private val dataSource = TeslaBleDataSource()

    private val frameNanos: Long = run {
        val refresh = app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate ?: 60f
        (1e9 / refresh).toLong()
    }
    private val recorder = LatencyRecorder(frameNanos)

    val publicKeyHex: String = key.publicKeyBytes.joinToString("") { "%02x".format(it) }

    private val _vin = MutableStateFlow(prefs.getString("vin", "") ?: "")
    val vin: StateFlow<String> = _vin

    private val _status = MutableStateFlow(TeslaVehicleClient.State.IDLE to "")
    val status: StateFlow<Pair<TeslaVehicleClient.State, String>> = _status

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _report = MutableStateFlow<Report?>(null)
    val report: StateFlow<Report?> = _report

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message

    val fast: StateFlow<FastState?> = dataSource.fast
    val slow: StateFlow<SlowState> = dataSource.slow

    private var reportJob: Job? = null

    private val runner = PollingRunner(object : PollingRunner.Listener<TeslaVehicleClient> {
        override fun startScheduler(client: TeslaVehicleClient, config: PollingScheduler.Config): PollingRunner.Handle {
            val s = PollingScheduler(client, config) { kind, result ->
                // BLE thread: publish first, then record, so recording never delays the UI.
                val ok = result.outcome as? TeslaVehicleClient.Outcome.Ok
                if (ok != null) runnerOnData() else lastFailure = result.outcome.describe()
                val fastState = ok?.let { dataSource.publish(it.data, result.decodedNanos) }
                recorder.record(kind, result, if (kind == PollingScheduler.Kind.FAST) fastState else null)
            }
            s.start()
            Log.i(TAG, "polling started")
            return PollingRunner.Handle { s.stop() }
        }

        override fun onRunStarted() {
            recorder.reset()
            _running.value = true
            reportJob = viewModelScope.launch(Dispatchers.Default) {
                while (isActive) {
                    _report.value = recorder.snapshot()
                    delay(500)
                }
            }
        }

        override fun onRunStopped() {
            reportJob?.cancel()
            reportJob = null
            _report.value = recorder.snapshot()
            _running.value = false
        }

        override fun onGap() {
            Log.i(TAG, "polling paused")
            recorder.markGap()
        }

        override fun isReady() = _status.value.first == TeslaVehicleClient.State.READY
    })

    private val client: TeslaVehicleClient? get() = runner.client

    /** 마지막 요청 실패 이유. 값이 끊겨 복구할 때 로그에 남긴다(BLE 스레드에서 쓴다). */
    @Volatile
    private var lastFailure = "-"

    private fun runnerOnData() {
        runner.onData()
    }

    init {
        // Auto-connect: a VIN from a previous session plus granted permissions is all we need.
        autoConnectIfPossible()
        // 끊겼다가 다시 연결됐는데 값이 들어오지 않으면 스스로 복구한다(PollingRunner.checkStall).
        viewModelScope.launch {
            while (isActive) {
                delay(STALL_CHECK_MS)
                when (runner.checkStall()) {
                    PollingRunner.Recovery.RESTART_SCHEDULER ->
                        Log.w(TAG, "no data while connected (last failure: $lastFailure) - polling restarted")
                    PollingRunner.Recovery.RECONNECT -> {
                        Log.w(TAG, "still no data (last failure: $lastFailure) - reconnecting")
                        if (client != null) connect()
                    }
                    PollingRunner.Recovery.NONE -> Unit
                }
            }
        }
    }

    fun setVin(value: String) {
        val v = value.trim().uppercase()
        _vin.value = v
        prefs.edit().putString("vin", v).apply()
    }

    /** Called after the permission dialog closes; connects if a VIN is already known. */
    fun autoConnectIfPossible() {
        if (client == null && _vin.value.length == 17 && hasBlePermissions()) connect()
    }

    private fun hasBlePermissions(): Boolean = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(getApplication(), it) == PackageManager.PERMISSION_GRANTED }

    fun connect() {
        val v = _vin.value
        if (v.length != 17) {
            _message.value = "VIN must be 17 characters"
            return
        }
        if (!hasBlePermissions()) {
            _message.value = "Nearby devices and location permissions are required"
            return
        }
        _message.value = ""
        // 이전 클라이언트에 묶인 스케줄러를 먼저 멈춘다. 측정 중이면 새 클라이언트가 READY가 될 때 다시 시작한다.
        runner.pause()
        client?.shutdown()
        lateinit var created: TeslaVehicleClient
        created = TeslaVehicleClient(getApplication(), v, key, object : TeslaVehicleClient.Listener {
            override fun onStateChanged(state: TeslaVehicleClient.State, detail: String) {
                // 교체되거나 끊긴 클라이언트가 늦게 보내는 이벤트(예: shutdown 후 "stopped")는 무시한다.
                if (!runner.isCurrent(created)) return
                _status.value = state to detail
                // 계기판이므로 연결되면 바로 값을 받기 시작한다(PollingRunner 참고).
                viewModelScope.launch(Dispatchers.Main) { runner.onState(created, state) }
            }
        })
        runner.setClient(created)
        created.start(autoReconnect = true)
    }

    fun disconnect() {
        stopRun()
        client?.shutdown()
        runner.setClient(null)
        _status.value = TeslaVehicleClient.State.IDLE to "disconnected (auto-connect off until Connect)"
    }

    fun pair(role: Keys.Role) {
        client?.requestPairing(role)
    }

    fun retryHandshake() {
        client?.retryHandshake()
    }

    /** Starts a measurement run. If the link drops, the run pauses and resumes on reconnect. */
    fun startRun(config: PollingScheduler.Config) {
        runner.startRun(config)
    }

    fun stopRun() = runner.stopRun()

    /** Called from the speed text's draw pass. */
    fun onSpeedDrawn(sampleReceivedAtNanos: Long) {
        recorder.markDrawn(sampleReceivedAtNanos, System.nanoTime())
    }

    fun exportCsv() {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                val dir = getApplication<Application>().getExternalFilesDir(null) ?: getApplication<Application>().filesDir
                val name = "spike-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".csv"
                File(dir, name).also { recorder.exportCsv(it) }
            }
            _message.value = "Saved ${file.absolutePath}"
        }
    }

    override fun onCleared() {
        disconnect()
    }

    companion object {
        private const val TAG = "NNTesla.Poll"
        private const val STALL_CHECK_MS = 1_000L

        private fun TeslaVehicleClient.Outcome.describe(): String = when (this) {
            is TeslaVehicleClient.Outcome.Failed -> reason
            TeslaVehicleClient.Outcome.Timeout -> "timeout"
            is TeslaVehicleClient.Outcome.Ok -> "ok"
        }

        /** 차량 스캔·연결에 필요한 런타임 권한. 위치 권한이 필요한 이유는 tesla-ble 매니페스트 참고. */
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
