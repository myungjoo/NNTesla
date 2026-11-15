package io.github.myungjoo.nntesla.spike

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tesla.generated.keys.Keys
import io.github.myungjoo.nntesla.ble.PollingScheduler
import io.github.myungjoo.nntesla.ble.TeslaVehicleClient
import io.github.myungjoo.nntesla.vehicledata.Media
import io.github.myungjoo.nntesla.vehicledata.SlowState
import io.github.myungjoo.nntesla.vehicledata.Timed

class MainActivity : ComponentActivity() {
    private val viewModel: SpikeViewModel by viewModels()

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.autoConnectIfPossible()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // 계기판이라 화면 전체를 쓴다. 접은 Fold의 가로 모드는 높이가 440dp 남짓이라 시스템 바가 차지하는
        // 높이도 아깝다. 가장자리에서 쓸어 넘기면 잠시 다시 보인다.
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
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

/** Narrowest a tools section may get before the tools screen drops to fewer columns. */
private val MIN_SECTION_WIDTH = 300.dp

private val GAP = 8.dp

/**
 * 기본 화면은 주행 중 필요한 정보만 보여 준다. 속도는 언제나 창의 [SPEED_FRACTION]을 긴 축으로 차지한다
 * (세로 모드는 위쪽 띠, 가로 모드는 왼쪽 띠). 나머지는 [DrivePanel]이다. 연결 관리·페어링·측정 같은
 * 도구는 설정 버튼을 눌러야 [ToolsScreen]으로 나온다.
 */
@Composable
private fun SpikeScreen(vm: SpikeViewModel) {
    var showTools by rememberSaveable { mutableStateOf(false) }
    if (showTools) {
        BackHandler { showTools = false }
        ToolsScreen(vm, onClose = { showTools = false })
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > maxHeight) {
            Row(Modifier.fillMaxSize()) {
                SpeedPanel(vm, Modifier.fillMaxHeight().fillMaxWidth(SPEED_FRACTION))
                VerticalDivider()
                DrivePanel(vm, onOpenTools = { showTools = true }, Modifier.fillMaxHeight().weight(1f))
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                SpeedPanel(vm, Modifier.fillMaxWidth().fillMaxHeight(SPEED_FRACTION))
                HorizontalDivider()
                DrivePanel(vm, onOpenTools = { showTools = true }, Modifier.fillMaxWidth().weight(1f))
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
        // 경고등은 판넬 아래쪽에 겹쳐 그린다. 속도 숫자의 크기·위치 계산에는 들어가지 않는다.
        SpeedTelltales(vm, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(maxHeight * TELLTALE_FRACTION))
    }
}

/** 속도 판넬 높이 중 경고등 줄이 차지하는 비율(10% 이내). */
private const val TELLTALE_FRACTION = 0.08f

/** 도어·창문·잠금 경고등. 느린 경로 값만 읽어, 속도 숫자는 이 값이 바뀌어도 다시 그려지지 않는다. */
@Composable
private fun SpeedTelltales(vm: SpikeViewModel, modifier: Modifier) {
    val slow by vm.slow.collectAsStateWithLifecycle()
    TelltaleRow(slow.closures?.value, modifier)
}

/** 주행 정보 타일 하나. 값은 [TileGrid]가 정한 크기로 그린다. [wide]면 한 줄을 통째로 쓴다. */
private sealed interface DriveTile {
    val wide: Boolean get() = false

    data class Value(val label: String, val value: String, val unit: String) : DriveTile
    data class Tires(val kpa: List<Float?>?) : DriveTile

    /** 작은 화면에서 실외·실내 온도를 한 줄로: "Out: -5 °C / In: 22 °C". 한 줄을 통째로 쓴다. */
    data class Temps(val outside: String, val inside: String) : DriveTile {
        override val wide get() = true
    }

    data class Navigation(
        val destination: String?,
        val minutes: String,
        val detail: String,
    ) : DriveTile {
        override val wide get() = true
    }

    data class NowPlaying(val text: String?, val source: String?) : DriveTile {
        override val wide get() = true
    }
}

/**
 * 속도 밖의 주행 정보. 위쪽 줄에 연결 상태와 설정 버튼, 그 아래를 타일이 빈칸 없이 채운다. 배터리와 주행 가능
 * 거리는 위쪽 영역에 나머지보다 2.5~3배 큰 글자로, 나머지는 아래쪽 영역에 둔다. 두 영역의 높이, 열 수, 글자
 * 크기는 [TileGrid.split]이 남은 영역에 맞춰 고른다. 마지막 줄이 덜 차면 그 줄 타일이 넓어진다.
 *
 * 내비게이션과 재생 중인 음악은 글이 길어 한 줄을 통째로 쓰고, 값이 없어도 자리를 지킨다(값이 생길 때 배치가
 * 바뀌지 않게). 작은 화면([TileGrid.SMALL_PANEL_HEIGHT] 미만)에서는 실외·실내 온도를 "Out: / In:" 한 줄로 합친다.
 */
@Composable
private fun DrivePanel(vm: SpikeViewModel, onOpenTools: () -> Unit, modifier: Modifier) {
    val fast by vm.fast.collectAsStateWithLifecycle()
    val slow by vm.slow.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val primary = listOf(
        DriveTile.Value("Battery", slow.batteryLevelPercent.show(), "%"),
        DriveTile.Value("Range", slow.batteryRangeKm.show("%.0f"), "km"),
    )
    val outside = slow.outsideTempCelsius.show("%.0f")
    val inside = slow.insideTempCelsius.show("%.0f")
    val common = listOf(
        DriveTile.Value("Power", fast?.powerKw?.toString() ?: "-", "kW"),
        DriveTile.Tires(slow.tirePressureKpa?.value),
        DriveTile.Value("Odometer", slow.odometerKm.show("%.0f"), "km"),
        slow.headingDegrees?.value.let { DriveTile.Value("Heading", Compass.name(it), it?.let { d -> "$d°" } ?: "") },
    )
    val wide = listOf(navigationTile(slow), nowPlayingTile(slow.media?.value))
    fun secondary(mergeTemps: Boolean): List<DriveTile> = if (mergeTemps) {
        common + DriveTile.Temps(outside, inside) + wide
    } else {
        // 실외·실내 온도를 맨 앞에 나란히 두어 2열 이상이면 같은 줄(같은 높이)에 오게 한다.
        listOf(DriveTile.Value("Outside", outside, "°C"), DriveTile.Value("Inside", inside, "°C")) + common + wide
    }
    Column(modifier.padding(horizontal = GAP)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ConnectionBadge(status.first, Modifier.weight(1f))
            IconButton(onClick = onOpenTools) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).padding(bottom = GAP)) {
            fun splitFor(tiles: List<DriveTile>) = TileGrid.split(
                primary.size,
                tiles.count { !it.wide },
                maxWidth.value,
                maxHeight.value,
                GAP.value,
                secondaryWide = tiles.count { it.wide },
            )
            val secondary = secondary(mergeTemps = maxHeight.value < TileGrid.SMALL_PANEL_HEIGHT)
            val split = splitFor(secondary)
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(GAP)) {
                TileBlock(
                    primary,
                    split.primary.columns,
                    split.primaryValueSize,
                    inline = false,
                    Modifier.fillMaxWidth().height(split.primaryHeight.dp),
                )
                TileBlock(
                    secondary,
                    split.secondary.columns,
                    split.secondary.valueSize,
                    inline = true,
                    Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

private fun navigationTile(slow: SlowState): DriveTile.Navigation {
    val destination = slow.destination?.value?.ifEmpty { null }
    if (destination == null) return DriveTile.Navigation(null, "-", "")
    val detail = listOfNotNull(
        slow.routeDistanceKm?.value?.let { "%.0f km".format(it) },
        slow.trafficDelayMinutes?.value?.takeIf { it >= 1f }?.let { "+%.0f min traffic".format(it) },
        slow.energyAtArrivalPercent?.value?.let { "%.0f%% at arrival".format(it) },
    ).joinToString(" · ")
    return DriveTile.Navigation(destination, slow.minutesToArrival.show("%.0f"), detail)
}

private fun nowPlayingTile(media: Media?): DriveTile.NowPlaying {
    val text = listOfNotNull(media?.artist, media?.title).joinToString(" — ").ifEmpty { null }
    return DriveTile.NowPlaying(text, media?.source)
}

/**
 * [tiles]를 [columns]열 격자로 [modifier] 영역에 꽉 채운다. 넓은 타일은 뒤에 한 줄씩 둔다.
 * [inline]이면 라벨과 값을 한 줄에 둔다. [valueDp]는 값 글자 크기(dp)다.
 */
@Composable
private fun TileBlock(tiles: List<DriveTile>, columns: Int, valueDp: Float, inline: Boolean, modifier: Modifier) {
    val rows = tiles.filter { !it.wide }.chunked(columns) + tiles.filter { it.wide }.map { listOf(it) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GAP)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                row.forEach { tile ->
                    val m = Modifier.weight(1f).fillMaxHeight()
                    when (tile) {
                        is DriveTile.Value -> if (inline) InlineTile(tile, valueDp, m) else InfoTile(tile, valueDp, m)
                        is DriveTile.Tires -> TireTile(tile.kpa, valueDp, m)
                        is DriveTile.Temps -> TempsTile(tile, valueDp, m)
                        is DriveTile.Navigation -> NavigationTile(tile, valueDp, m)
                        is DriveTile.NowPlaying -> NowPlayingTile(tile, valueDp, m)
                    }
                }
            }
        }
    }
}

/** 연결 상태를 색 점과 짧은 문구로. 자세한 내용은 설정 화면에 있다. */
@Composable
private fun ConnectionBadge(state: TeslaVehicleClient.State, modifier: Modifier) {
    val (color, label) = when (state) {
        TeslaVehicleClient.State.READY -> Color(0xFF4CAF50) to "Connected"
        TeslaVehicleClient.State.SCANNING,
        TeslaVehicleClient.State.CONNECTING,
        TeslaVehicleClient.State.HANDSHAKING -> Color(0xFFFFC107) to "Connecting"
        TeslaVehicleClient.State.NEEDS_PAIRING -> Color(0xFFFF9800) to "Pairing needed"
        TeslaVehicleClient.State.FAILED -> Color(0xFFF44336) to "Disconnected"
        TeslaVehicleClient.State.IDLE -> Color.Gray to "Not connected"
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

/** dp 크기를 글자 크기(sp)로. */
@Composable
private fun Float.dpToSp(): TextUnit = with(LocalDensity.current) { this@dpToSp.dp.toSp() }

/** 라벨·단위 글자 크기(dp). 값 글자에 비례하되 [MIN_LABEL_DP]보다 작아지지 않는다. */
private fun labelDp(valueDp: Float, ratio: Float = 0.4f) = maxOf(valueDp * ratio, MIN_LABEL_DP)

@Composable
private fun InfoTile(tile: DriveTile.Value, valueDp: Float, modifier: Modifier) {
    val value = valueDp.dpToSp()
    Card(modifier) {
        Column(Modifier.fillMaxSize().padding(horizontal = TileGrid.TILE_PADDING.dp, vertical = 6.dp)) {
            Text(tile.label, fontSize = labelDp(valueDp, 0.3f).dpToSp(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            // 타일이 세로로 길어도(펼친 Fold) 값이 아래에 붙지 않게 남은 높이의 가운데에 둔다.
            Row(
                Modifier.fillMaxWidth().weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                Text(tile.value, fontSize = value, lineHeight = value, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(tile.unit, fontSize = labelDp(valueDp, 0.35f).dpToSp(), maxLines = 1, softWrap = false)
            }
        }
    }
}

/** 1 psi = 6.894757 kPa. */
private const val KPA_PER_PSI = 6.894757f

/** 라벨과 단위의 최소 글자 크기(dp). 값 글자가 작은 타일에서도 읽을 수 있게. */
private const val MIN_LABEL_DP = 12f

/**
 * 라벨은 왼쪽, 값과 단위는 오른쪽에 한 줄로. 낮은 타일에서도 값이 잘리지 않는다. 값이 길면(누적 주행거리 등)
 * 칸 폭에 맞춰 글자를 줄인다([TileGrid.fitValue]).
 */
@Composable
private fun InlineTile(tile: DriveTile.Value, valueDp: Float, modifier: Modifier) {
    Card(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = TileGrid.TILE_PADDING.dp)) {
            val label = labelDp(valueDp)
            val fitted = TileGrid.fitValue(maxWidth.value, tile.label, tile.value, tile.unit, label, valueDp)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    tile.label,
                    fontSize = label.dpToSp(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(tile.value, fontSize = fitted.dpToSp(), lineHeight = fitted.dpToSp(), fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(" " + tile.unit, fontSize = label.dpToSp(), maxLines = 1)
            }
        }
    }
}

/** 실외·실내 온도를 한 줄로: "Out: -5 °C / In: 22 °C". 칸 폭에 맞춰 숫자 크기를 줄인다. */
@Composable
private fun TempsTile(tile: DriveTile.Temps, valueDp: Float, modifier: Modifier) {
    Card(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = TileGrid.TILE_PADDING.dp)) {
            val label = labelDp(valueDp)
            val fitted = TileGrid.fitValue(maxWidth.value, "Out: °C / In:", tile.outside + tile.inside, "°C", label, valueDp)
            val l = label.dpToSp()
            val v = fitted.dpToSp()
            Row(
                Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("Out: ", fontSize = l, maxLines = 1)
                Text(tile.outside, fontSize = v, lineHeight = v, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(" °C / In: ", fontSize = l, maxLines = 1)
                Text(tile.inside, fontSize = v, lineHeight = v, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(" °C", fontSize = l, maxLines = 1)
            }
        }
    }
}

/** 타이어 공기압 네 개를 차 모양대로 앞 두 개, 뒤 두 개로. 라벨 오른쪽에 두 줄, 줄마다 값 글자의 55%. */
@Composable
private fun TireTile(kpa: List<Float?>?, valueDp: Float, modifier: Modifier) {
    // 타이어 공기압만은 SI(kPa) 대신 PSI로 보인다(사용자 결정). 데이터 모델은 kPa 그대로 두고 표시할 때만 바꾼다.
    fun v(i: Int) = kpa?.getOrNull(i)?.let { "%.0f".format(it / KPA_PER_PSI) } ?: "-"
    val size = (valueDp * 0.55f).dpToSp()
    val label = labelDp(valueDp).dpToSp()
    Card(modifier) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = TileGrid.TILE_PADDING.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tires", fontSize = label, maxLines = 1, modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text("${v(0)}  ${v(1)}", fontSize = size, lineHeight = size, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${v(2)}  ${v(3)}", fontSize = size, lineHeight = size, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Text(" psi", fontSize = label, maxLines = 1)
        }
    }
}

/** 목적지와 남은 시간. 아래 줄에 남은 거리, 교통 지연, 도착 시 배터리. 경로가 없으면 "No route". */
@Composable
private fun NavigationTile(tile: DriveTile.Navigation, valueDp: Float, modifier: Modifier) {
    val label = labelDp(valueDp).dpToSp()
    val value = valueDp.dpToSp()
    val detailDp = labelDp(valueDp, 0.33f)
    Card(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = TileGrid.TILE_PADDING.dp)) {
            // 칸이 두 줄을 담을 만큼 높으면 목적지 아래에 상세를, 낮으면(접은 Fold) 목적지 뒤에 이어 쓴다.
            val twoLines = maxHeight.value >= (labelDp(valueDp) + detailDp) * 1.6f
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val destination = tile.destination ?: "No route"
                    if (twoLines || tile.detail.isEmpty()) {
                        Text(destination, fontSize = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (tile.detail.isNotEmpty()) {
                        Text(
                            if (twoLines) tile.detail else "$destination · ${tile.detail}",
                            fontSize = (if (twoLines) detailDp else labelDp(valueDp)).dpToSp(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (twoLines) LocalContentColor.current.copy(alpha = 0.7f) else LocalContentColor.current,
                        )
                    }
                }
                Text(tile.minutes, fontSize = value, lineHeight = value, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(" min", fontSize = label, maxLines = 1)
            }
        }
    }
}

/** 재생 중인 음악: "아티스트 — 제목". 없으면 흐리게 "Nothing playing". */
@Composable
private fun NowPlayingTile(tile: DriveTile.NowPlaying, valueDp: Float, modifier: Modifier) {
    val label = labelDp(valueDp).dpToSp()
    Card(modifier) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = TileGrid.TILE_PADDING.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("♪", fontSize = label, maxLines = 1)
            Text(
                tile.text ?: "Nothing playing",
                fontSize = (valueDp * 0.6f).coerceAtLeast(MIN_LABEL_DP).dpToSp(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (tile.text == null) LocalContentColor.current.copy(alpha = 0.5f) else LocalContentColor.current,
                modifier = Modifier.weight(1f),
            )
            tile.source?.let {
                Text(it, fontSize = labelDp(valueDp, 0.33f).dpToSp(), maxLines = 1, color = LocalContentColor.current.copy(alpha = 0.6f))
            }
        }
    }
}

/** 연결 관리, 페어링, 지연 측정 같은 도구. 섹션 열 수는 폭을 따라간다. 측정은 이 화면을 닫아도 계속된다. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolsScreen(vm: SpikeViewModel, onClose: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = GAP), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val inner = maxWidth - GAP * 2
            val columns = ((inner + GAP) / (MIN_SECTION_WIDTH + GAP)).toInt().coerceAtLeast(1)
            val sectionWidth = (inner - GAP * (columns - 1)) / columns
            val section = Modifier.width(sectionWidth)
            FlowRow(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(GAP),
                horizontalArrangement = Arrangement.spacedBy(GAP),
                verticalArrangement = Arrangement.spacedBy(GAP),
            ) {
                Section("Connection", section) { ConnectionSection(vm) }
                Section("Pairing", section) { PairingSection(vm) }
                Section("Vehicle data (raw)", section) { VehicleDataSection(vm) }
                Section("Experiment", section) { ExperimentSection(vm) }
                Section("Report", section) { ReportSection(vm) }
            }
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
        "tires ${slow.tirePressureKpa?.value?.joinToString(" / ") { it?.let { v -> "%.0f".format(v / KPA_PER_PSI) } ?: "-" } ?: "-"} psi",
        style = small,
    )
    Text(
        "dest ${slow.destination.show()} (${slow.minutesToArrival.show("%.0f")} min, ${slow.routeDistanceKm.show("%.1f")} km, " +
            "traffic +${slow.trafficDelayMinutes.show("%.0f")} min, arrival ${slow.energyAtArrivalPercent.show("%.0f")} %)",
        style = small,
    )
    Text("odometer ${slow.odometerKm.show("%.1f")} km · heading ${slow.headingDegrees.show()}°", style = small)
    val c = slow.closures?.value
    Text(
        "doors FL ${c?.frontLeftDoorOpen.flag()} FR ${c?.frontRightDoorOpen.flag()} RL ${c?.rearLeftDoorOpen.flag()} " +
            "RR ${c?.rearRightDoorOpen.flag()} · frunk ${c?.frunkOpen.flag()} trunk ${c?.trunkOpen.flag()}",
        style = small,
    )
    Text(
        "windows ${c?.windowsOpen?.joinToString(" ") { it.flag() } ?: "-"} · locked ${c?.locked.flag()} · sentry ${c?.sentryArmed.flag()}",
        style = small,
    )
    val m = slow.media?.value
    Text("media ${m?.source ?: "-"}: ${m?.artist ?: "-"} — ${m?.title ?: "-"}", style = small)
}

/** 열림·참 = "1", 닫힘·거짓 = "0", 보고 안 됨 = "-". */
private fun Boolean?.flag(): String = when (this) {
    true -> "1"
    false -> "0"
    null -> "-"
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
    AutoLaunchRow()
}

/**
 * 차량 블루투스 연결 시 자동 실행 설정. 연결 상태는 시스템(CompanionDeviceManager)이 들고 있다.
 * 페어링된 기기 중 이름에 Tesla가 들어간 것이 하나면 바로 그 기기로 요청하고, 아니면 이름 목록에서 고르게 한다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutoLaunchRow() {
    val context = LocalContext.current
    var associated by remember { mutableStateOf(CarAutoLaunch.describeAssociated(context)) }
    var choices by remember { mutableStateOf<List<CarAutoLaunch.BondedDevice>>(emptyList()) }
    var error by remember { mutableStateOf("") }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        associated = CarAutoLaunch.describeAssociated(context)
    }
    fun associate(device: CarAutoLaunch.BondedDevice) {
        choices = emptyList()
        error = ""
        CarAutoLaunch.requestAssociation(
            context,
            device.address,
            onChooser = { chooser.launch(IntentSenderRequest.Builder(it).build()) },
            onError = { error = it },
        )
    }
    Text(
        if (associated.isEmpty()) "Auto-launch: off" else "Auto-launch on: ${associated.joinToString()}",
        style = MaterialTheme.typography.bodySmall,
    )
    if (error.isNotEmpty()) Text(error, style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val bonded = CarAutoLaunch.bondedDevices(context)
            val teslas = bonded.filter { it.looksLikeTesla }
            when {
                teslas.size == 1 -> associate(teslas.single())
                bonded.isEmpty() -> error = "No paired Bluetooth devices. Pair the car in Bluetooth settings first."
                else -> choices = bonded
            }
        }) { Text(if (associated.isEmpty()) "Set up auto-launch" else "Add car") }
        if (associated.isNotEmpty()) {
            OutlinedButton(onClick = {
                CarAutoLaunch.disassociateAll(context)
                associated = CarAutoLaunch.describeAssociated(context)
            }) { Text("Turn off auto-launch") }
        }
    }
    if (choices.isNotEmpty()) {
        Text("Choose the car:", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { device ->
                OutlinedButton(onClick = { associate(device) }) { Text(device.name) }
            }
        }
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
