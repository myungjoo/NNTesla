package io.github.myungjoo.nntesla.spike

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
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
        permissions.launch(SpikeViewModel.REQUIRED_PERMISSIONS)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SpikeScreen(viewModel)
                }
            }
        }
    }
}

/** Share of the screen (height in portrait, width in landscape) given to the speed. */
private const val SPEED_FRACTION = 0.45f

/** Narrowest a secondary section may get before the panel drops to fewer columns. */
private val MIN_SECTION_WIDTH = 300.dp
private val GAP = 8.dp

/**
 * The speed always gets [SPEED_FRACTION] of the window, on the long axis: top band in portrait,
 * left band in landscape. Everything else lives in [SecondaryPanel], which reflows its sections
 * into as many columns as the remaining space allows.
 */
@Composable
private fun SpikeScreen(vm: SpikeViewModel) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                SpeedPanel(vm, Modifier.fillMaxHeight().fillMaxWidth(SPEED_FRACTION))
                VerticalDivider()
                SecondaryPanel(vm, Modifier.fillMaxHeight().weight(1f))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                SpeedPanel(vm, Modifier.fillMaxWidth().fillMaxHeight(SPEED_FRACTION))
                HorizontalDivider()
                SecondaryPanel(vm, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}

@Composable
private fun SpeedPanel(vm: SpikeViewModel, modifier: Modifier) {
    val fast by vm.fast.collectAsStateWithLifecycle()
    val sample = fast
    BoxWithConstraints(modifier.padding(8.dp), contentAlignment = Alignment.Center) {
        // Size the digits to the panel: up to three monospace digits (~0.6 em each) across, and
        // the number plus the unit line within the height.
        val digits: Dp = minOf(maxHeight * 0.7f, maxWidth / 1.9f)
        val numberSize = with(LocalDensity.current) { digits.toSp() }
        val unitSize = numberSize * 0.16f
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = sample?.speedKmh?.let { "%.0f".format(it) } ?: "--",
                fontSize = numberSize,
                lineHeight = numberSize,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                // Records when each new sample is actually drawn; this is the fast-path display time.
                modifier = Modifier.drawWithContent {
                    drawContent()
                    if (sample != null) vm.onSpeedDrawn(sample.receivedAtNanos)
                },
            )
            Text("km/h  ${sample?.shiftState ?: "-"}", fontSize = unitSize, lineHeight = unitSize)
        }
    }
}

/**
 * Non-speed UI as independent sections. The column count follows the available width, so the
 * same sections stack in one column on a phone in portrait and spread out on wider windows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SecondaryPanel(vm: SpikeViewModel, modifier: Modifier) {
    BoxWithConstraints(modifier) {
        val inner = maxWidth - GAP * 2
        val columns = ((inner + GAP) / (MIN_SECTION_WIDTH + GAP)).toInt().coerceAtLeast(1)
        val sectionWidth = (inner - GAP * (columns - 1)) / columns
        val section = Modifier.width(sectionWidth)
        FlowRow(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(GAP),
            horizontalArrangement = Arrangement.spacedBy(GAP),
            verticalArrangement = Arrangement.spacedBy(GAP),
        ) {
            Section("Vehicle", section) { VehicleDataSection(vm) }
            Section("Connection", section) { ConnectionSection(vm) }
            Section("Pairing", section) { PairingSection(vm) }
            Section("Experiment", section) { ExperimentSection(vm) }
            Section("Report", section) { ReportSection(vm) }
        }
    }
}

@Composable
private fun Section(title: String, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun VehicleDataSection(vm: SpikeViewModel) {
    val fast by vm.fast.collectAsStateWithLifecycle()
    val slow by vm.slow.collectAsStateWithLifecycle()
    val small = MaterialTheme.typography.bodySmall
    Text(
        "shift ${fast?.shiftState ?: "-"} · power ${fast?.powerKw?.let { "$it kW" } ?: "-"} · " +
            "vehicle ts ${fast?.vehicleTimestampMillis ?: "-"}",
        style = small,
    )
    Text(
        "battery ${slow.batteryLevelPercent.show()} % · range ${slow.batteryRangeKm.show("%.0f")} km",
        style = small,
    )
    Text(
        "inside ${slow.insideTempCelsius.show("%.1f")} °C · outside ${slow.outsideTempCelsius.show("%.1f")} °C",
        style = small,
    )
    Text(
        "tires ${slow.tirePressureKpa?.value?.joinToString(" / ") { it?.let { v -> "%.0f".format(v) } ?: "-" } ?: "-"} kPa",
        style = small,
    )
    Text("dest ${slow.destination.show()} (${slow.minutesToArrival.show("%.0f")} min)", style = small)
}

private fun <T> Timed<T>?.show(format: String? = null): String {
    val v = this?.value ?: return "-"
    return if (format != null && v is Number) format.format(v) else v.toString()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionSection(vm: SpikeViewModel) {
    val vin by vm.vin.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    Text("${status.first} - ${status.second}")
    if (message.isNotEmpty()) Text(message, style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(
        value = vin,
        onValueChange = vm::setVin,
        label = { Text("VIN") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
        modifier = Modifier.fillMaxWidth(),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = vm::connect) { Text("Connect") }
        OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
        OutlinedButton(onClick = vm::retryHandshake) { Text("Retry handshake") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PairingSection(vm: SpikeViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    var monitorRole by rememberSaveable { mutableStateOf(true) }
    Text("Public key: ${vm.publicKeyHex.take(18)}...", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = monitorRole, onClick = { monitorRole = true }, label = { Text("Vehicle monitor") })
        FilterChip(selected = !monitorRole, onClick = { monitorRole = false }, label = { Text("Driver") })
    }
    OutlinedButton(
        enabled = status.first == TeslaVehicleClient.State.NEEDS_PAIRING || status.first == TeslaVehicleClient.State.READY,
        onClick = { vm.pair(if (monitorRole) Keys.Role.ROLE_VEHICLE_MONITOR else Keys.Role.ROLE_DRIVER) },
    ) { Text("Send pairing request (then tap keycard)") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExperimentSection(vm: SpikeViewModel) {
    val status by vm.status.collectAsStateWithLifecycle()
    val running by vm.running.collectAsStateWithLifecycle()
    var maxInFlight by rememberSaveable { mutableIntStateOf(1) }
    var slowEnabled by rememberSaveable { mutableStateOf(true) }
    var slowBatched by rememberSaveable { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = maxInFlight == 1, onClick = { maxInFlight = 1 }, label = { Text("1 in flight") })
        FilterChip(selected = maxInFlight == 2, onClick = { maxInFlight = 2 }, label = { Text("2 in flight") })
        FilterChip(selected = slowEnabled, onClick = { slowEnabled = !slowEnabled }, label = { Text("Slow path") })
        FilterChip(selected = slowBatched, onClick = { slowBatched = !slowBatched }, label = { Text("Batch slow") })
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !running && status.first == TeslaVehicleClient.State.READY,
            onClick = {
                vm.startRun(PollingScheduler.Config(maxInFlight = maxInFlight, slowEnabled = slowEnabled, slowBatched = slowBatched))
            },
        ) { Text("Start run") }
        OutlinedButton(enabled = running, onClick = vm::stopRun) { Text("Stop") }
        OutlinedButton(onClick = vm::exportCsv) { Text("Export CSV") }
    }
}

@Composable
private fun ReportSection(vm: SpikeViewModel) {
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
