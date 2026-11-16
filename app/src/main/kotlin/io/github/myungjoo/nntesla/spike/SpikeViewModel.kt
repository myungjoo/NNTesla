package io.github.myungjoo.nntesla.spike

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.content.Context
import android.hardware.display.DisplayManager
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

    // 메인 스레드에서 바꾸고 BLE 스레드의 리스너에서 읽는다.
    @Volatile
    private var client: TeslaVehicleClient? = null
    private var scheduler: PollingScheduler? = null
    private var runConfig: PollingScheduler.Config? = null
    private var reportJob: Job? = null

    init {
        // Auto-connect: a VIN from a previous session plus granted permissions is all we need.
        autoConnectIfPossible()
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
        if (scheduler != null) {
            scheduler?.stop()
            scheduler = null
            recorder.markGap()
        }
        client?.shutdown()
        lateinit var created: TeslaVehicleClient
        created = TeslaVehicleClient(getApplication(), v, key, object : TeslaVehicleClient.Listener {
            override fun onStateChanged(state: TeslaVehicleClient.State, detail: String) {
                // 교체되거나 끊긴 클라이언트가 늦게 보내는 이벤트(예: shutdown 후 "stopped")는 무시한다.
                if (client !== created) return
                _status.value = state to detail
                viewModelScope.launch(Dispatchers.Main) { if (client === created) onClientState(state) }
            }
        })
        client = created
        created.start(autoReconnect = true)
    }

    /** Main thread. Pauses polling while the link is down and resumes it once READY again. */
    private fun onClientState(state: TeslaVehicleClient.State) {
        if (state == TeslaVehicleClient.State.READY) {
            // 계기판이므로 연결되면 바로 값을 받기 시작한다. 측정 설정을 바꾸려면 설정 화면에서 Stop 후 다시 Start.
            if (!_running.value) {
                startRun(PollingScheduler.Config())
            } else if (scheduler == null) {
                startScheduler()
            }
        } else if (scheduler != null) {
            scheduler?.stop()
            scheduler = null
            recorder.markGap()
        }
    }

    fun disconnect() {
        stopRun()
        client?.shutdown()
        client = null
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
        if (_running.value) return
        recorder.reset()
        runConfig = config
        _running.value = true
        if (_status.value.first == TeslaVehicleClient.State.READY) startScheduler()
        reportJob = viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                _report.value = recorder.snapshot()
                delay(500)
            }
        }
    }

    private fun startScheduler() {
        val c = client ?: return
        val config = runConfig ?: return
        val s = PollingScheduler(c, config) { kind, result ->
            // BLE thread: publish first, then record, so recording never delays the UI.
            val ok = result.outcome as? TeslaVehicleClient.Outcome.Ok
            val fastState = ok?.let { dataSource.publish(it.data, result.decodedNanos) }
            recorder.record(kind, result, if (kind == PollingScheduler.Kind.FAST) fastState else null)
        }
        scheduler = s
        s.start()
    }

    fun stopRun() {
        scheduler?.stop()
        scheduler = null
        runConfig = null
        reportJob?.cancel()
        reportJob = null
        if (_running.value) _report.value = recorder.snapshot()
        _running.value = false
    }

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
        /** 차량 스캔·연결에 필요한 런타임 권한. 위치 권한이 필요한 이유는 tesla-ble 매니페스트 참고. */
        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    }
}
