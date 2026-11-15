package io.github.myungjoo.nntesla.spike

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tesla.generated.keys.Keys
import io.github.myungjoo.nntesla.ble.PollingScheduler
import io.github.myungjoo.nntesla.ble.TeslaVehicleClient
import io.github.myungjoo.nntesla.vehicledata.Timed

class MainActivity : ComponentActivity() {
    private val viewModel: SpikeViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.autoConnectIfPossible()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        permissions.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT))
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeScreen(viewModel)
                }
            }
        }
    }
}

@Composable
private fun SpikeScreen(vm: SpikeViewModel) {
    val vin by vm.vin.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()

    var maxInFlight by remember { mutableIntStateOf(1) }
    var slowEnabled by remember { mutableStateOf(true) }
    var slowBatched by remember { mutableStateOf(false) }
    var monitorRole by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SpeedPanel(vm)
        HorizontalDivider()

        Text("Status: ${status.first} - ${status.second}")
        if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)

        OutlinedTextField(
            value = vin,
            onValueChange = vm::setVin,
            label = { Text("VIN") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::connect) { Text("Connect") }
            OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
            OutlinedButton(onClick = vm::retryHandshake) { Text("Retry handshake") }
        }

        Text("Pairing", fontWeight = FontWeight.Bold)
        Text("Public key: ${vm.publicKeyHex.take(18)}...", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = monitorRole, onClick = { monitorRole = true }, label = { Text("Vehicle monitor") })
            FilterChip(selected = !monitorRole, onClick = { monitorRole = false }, label = { Text("Driver") })
        }
        OutlinedButton(
            enabled = status.first == TeslaVehicleClient.State.NEEDS_PAIRING || status.first == TeslaVehicleClient.State.READY,
            onClick = { vm.pair(if (monitorRole) Keys.Role.ROLE_VEHICLE_MONITOR else Keys.Role.ROLE_DRIVER) },
        ) { Text("Send pairing request (then tap keycard)") }

        HorizontalDivider()
        Text("Experiment", fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = maxInFlight == 1, onClick = { maxInFlight = 1 }, label = { Text("1 in flight") })
            FilterChip(selected = maxInFlight == 2, onClick = { maxInFlight = 2 }, label = { Text("2 in flight") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = slowEnabled, onClick = { slowEnabled = !slowEnabled }, label = { Text("Slow path") })
            FilterChip(selected = slowBatched, onClick = { slowBatched = !slowBatched }, label = { Text("Batch slow") })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !running && status.first == TeslaVehicleClient.State.READY,
                onClick = {
                    vm.startRun(
                        PollingScheduler.Config(maxInFlight = maxInFlight, slowEnabled = slowEnabled, slowBatched = slowBatched),
                    )
                },
            ) { Text("Start run") }
            OutlinedButton(enabled = running, onClick = vm::stopRun) { Text("Stop") }
            OutlinedButton(onClick = vm::exportCsv) { Text("Export CSV") }
        }

        HorizontalDivider()
        ReportPanel(vm)
    }
}

@Composable
private fun SpeedPanel(vm: SpikeViewModel) {
    val fast by vm.fast.collectAsStateWithLifecycle()
    val slow by vm.slow.collectAsStateWithLifecycle()
    val sample = fast
    Text(
        text = sample?.speedKmh?.let { "%.1f".format(it) } ?: "--",
        fontSize = 96.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        // Records when each new sample is actually drawn; this is the fast-path display time.
        modifier = Modifier.drawWithContent {
            drawContent()
            if (sample != null) vm.onSpeedDrawn(sample.receivedAtNanos)
        },
    )
    Text(
        "km/h · shift ${sample?.shiftState ?: "-"} · " +
            "power ${sample?.powerKw?.let { "$it kW" } ?: "-"} · vehicle ts ${sample?.vehicleTimestampMillis ?: "-"}",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "battery ${slow.batteryLevelPercent.show()} % · range ${slow.batteryRangeKm.show("%.0f")} km · " +
            "in ${slow.insideTempCelsius.show("%.1f")} °C · out ${slow.outsideTempCelsius.show("%.1f")} °C",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "tires ${slow.tirePressureKpa?.value?.joinToString(" / ") { it?.let { v -> "%.0f".format(v) } ?: "-" } ?: "-"} kPa · " +
            "dest ${slow.destination.show()} (${slow.minutesToArrival.show("%.0f")} min)",
        style = MaterialTheme.typography.bodySmall,
    )
}

private fun <T> Timed<T>?.show(format: String? = null): String {
    val v = this?.value ?: return "-"
    return if (format != null && v is Number) format.format(v) else v.toString()
}

@Composable
private fun ReportPanel(vm: SpikeViewModel) {
    val report by vm.report.collectAsStateWithLifecycle()
    val r = report ?: run {
        Text("No run yet")
        return
    }
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    Text("Run: %.1f s, fast samples drawn: %d, link drops: %d".format(r.elapsedSeconds, r.fastDrawn, r.linkDrops), style = mono)
    PathBlock("FAST drive (target P95 <= 500 ms)", r.fast, mono)
    Text(
        "vehicle ts unchanged: ${r.vehicleTimestampUnchangedPercent?.let { "%.1f%%".format(it) } ?: "-"}  " +
            "change interval ms: ${r.vehicleTimestampChangeMs.fmt()}",
        style = mono,
    )
    for ((group, stats) in r.slow) {
        PathBlock("SLOW $group (target P95 <= 3000 ms)", stats, mono)
    }
}

@Composable
private fun PathBlock(title: String, s: PathStats, style: androidx.compose.ui.text.TextStyle) {
    val verdict = when (s.pass) {
        true -> "PASS"
        false -> "FAIL"
        null -> "n/a"
    }
    Text("$title: $verdict", fontWeight = FontWeight.Bold)
    Text("req ${s.requests} ok ${s.ok} timeout ${s.timeouts} fail ${s.failures}${s.lastFailure?.let { " ($it)" } ?: ""}", style = style)
    Text("latency bound ${s.latencyBoundMs.fmt()}", style = style)
    Text("rtt           ${s.rttMs.fmt()}", style = style)
    Text("write         ${s.writeMs.fmt()}", style = style)
    Text("resp bytes    ${s.responseBytes.fmt()}", style = style)
}

private fun Percentiles?.fmt(): String =
    this?.let { "p50 %.0f p95 %.0f p99 %.0f max %.0f (n=%d)".format(it.p50, it.p95, it.p99, it.max, it.n) } ?: "-"
