package io.github.myungjoo.nntesla.spike

import io.github.myungjoo.nntesla.ble.PollingScheduler.Kind
import io.github.myungjoo.nntesla.ble.TeslaVehicleClient
import io.github.myungjoo.nntesla.ble.TeslaVehicleClient.Outcome
import io.github.myungjoo.nntesla.vehicledata.FastState
import java.io.File

/**
 * Records every request of a spike run and derives the latency metrics.
 *
 * Display latency cannot be observed directly (we do not know when the vehicle value changed), so
 * the spike uses a conservative upper bound per displayed sample:
 *
 *     L_i = displayNanos_i - sendNanos(previous displayed sample of the same category)
 *
 * A change that happens just after the previous sample was taken is first visible in sample i,
 * which is on screen at displayNanos_i. The previous sample was taken no earlier than its send
 * time, so L_i bounds the change-to-display latency from above.
 *
 * Fast-path display time = time the speed text was drawn + one frame for composition to screen.
 * Slow-path display time = decode time + two frames (accuracy is irrelevant at a 3 s target).
 *
 * Thread-safe: results arrive on the BLE thread, draw marks on the main thread.
 */
class LatencyRecorder(private val frameNanos: Long) {
    class Row(
        val kind: Kind,
        val result: TeslaVehicleClient.Result,
        val fast: FastState?,
    ) {
        var displayNanos = 0L
        var latencyBoundNanos = 0L
    }

    private val rows = ArrayList<Row>()
    private val awaitingDraw = HashMap<Long, Row>() // FastState.receivedAtNanos -> row
    private val drawnEarly = HashMap<Long, Long>() // drawn before record() ran (race with UI)
    private var lastDisplayedFastSend = 0L
    private val lastDisplayedSlowSend = HashMap<String, Long>()
    private var startNanos = System.nanoTime()
    private var gaps = 0

    @Synchronized
    fun reset() {
        rows.clear()
        awaitingDraw.clear()
        drawnEarly.clear()
        lastDisplayedFastSend = 0L
        lastDisplayedSlowSend.clear()
        startNanos = System.nanoTime()
        gaps = 0
    }

    /**
     * The link dropped and polling paused. The outage is counted separately instead of being
     * folded into the latency bound of the first sample after reconnecting.
     */
    @Synchronized
    fun markGap() {
        gaps++
        awaitingDraw.clear()
        drawnEarly.clear()
        lastDisplayedFastSend = 0L
        lastDisplayedSlowSend.clear()
    }

    @Synchronized
    fun record(kind: Kind, result: TeslaVehicleClient.Result, fast: FastState?) {
        val row = Row(kind, result, fast)
        rows += row
        if (result.outcome !is Outcome.Ok) return
        if (kind == Kind.FAST && fast != null) {
            awaitingDraw[fast.receivedAtNanos] = row
            drawnEarly.remove(fast.receivedAtNanos)?.let { markDrawn(fast.receivedAtNanos, it) }
        } else if (kind == Kind.SLOW) {
            row.displayNanos = result.decodedNanos + 2 * frameNanos
            val key = result.categories.sorted().joinToString("+")
            lastDisplayedSlowSend[key]?.let { row.latencyBoundNanos = row.displayNanos - it }
            lastDisplayedSlowSend[key] = maxOf(lastDisplayedSlowSend[key] ?: 0L, result.sendNanos)
        }
    }

    /** Called from the speed text's draw pass the first time a sample is drawn. */
    @Synchronized
    fun markDrawn(sampleReceivedAtNanos: Long, drawNanos: Long) {
        val row = awaitingDraw.remove(sampleReceivedAtNanos)
        if (row == null) {
            drawnEarly[sampleReceivedAtNanos] = drawNanos
            if (drawnEarly.size > 64) drawnEarly.keys.removeAll { it < sampleReceivedAtNanos }
            return
        }
        row.displayNanos = drawNanos + frameNanos
        if (lastDisplayedFastSend != 0L) row.latencyBoundNanos = row.displayNanos - lastDisplayedFastSend
        lastDisplayedFastSend = maxOf(lastDisplayedFastSend, row.result.sendNanos)
        // Samples superseded before they were drawn never reach the screen.
        awaitingDraw.keys.removeAll { it < sampleReceivedAtNanos }
    }

    @Synchronized
    fun snapshot(): Report {
        val now = System.nanoTime()
        val fastRows = rows.filter { it.kind == Kind.FAST }
        val slowRows = rows.filter { it.kind == Kind.SLOW }
        val fastOk = fastRows.filter { it.result.outcome is Outcome.Ok }

        // Vehicle-side freshness: does DriveState.timestamp advance on every sample?
        val stamps = fastOk.mapNotNull { it.fast?.vehicleTimestampMillis }
        var unchanged = 0
        val changeIntervals = ArrayList<Double>()
        var lastChangeAt: Long? = null
        for (i in 1 until stamps.size) {
            if (stamps[i] == stamps[i - 1]) {
                unchanged++
            } else {
                lastChangeAt?.let { changeIntervals += (stamps[i] - it).toDouble() }
                lastChangeAt = stamps[i]
            }
        }

        val slowByGroup = slowRows.groupBy { it.result.categories.sorted().joinToString("+") }
        return Report(
            elapsedSeconds = (now - startNanos) / 1e9,
            linkDrops = gaps,
            fast = pathStats(fastRows, FAST_TARGET_MS),
            fastDrawn = fastOk.count { it.displayNanos != 0L },
            vehicleTimestampUnchangedPercent = if (stamps.size > 1) 100.0 * unchanged / (stamps.size - 1) else null,
            vehicleTimestampChangeMs = Percentiles.of(changeIntervals),
            slow = slowByGroup.mapValues { (_, r) -> pathStats(r, SLOW_TARGET_MS) },
        )
    }

    private fun pathStats(rows: List<Row>, targetMs: Double): PathStats {
        val ok = rows.filter { it.result.outcome is Outcome.Ok }
        return PathStats(
            requests = rows.size,
            ok = ok.size,
            timeouts = rows.count { it.result.outcome is Outcome.Timeout },
            failures = rows.count { it.result.outcome is Outcome.Failed },
            lastFailure = (rows.lastOrNull { it.result.outcome is Outcome.Failed }?.result?.outcome as? Outcome.Failed)?.reason,
            rttMs = Percentiles.of(ok.map { (it.result.completeNanos - it.result.sendNanos) / 1e6 }),
            writeMs = Percentiles.of(ok.filter { it.result.writtenNanos != 0L }.map { (it.result.writtenNanos - it.result.sendNanos) / 1e6 }),
            responseBytes = Percentiles.of(ok.map { it.result.responseBytes.toDouble() }),
            latencyBoundMs = Percentiles.of(ok.filter { it.latencyBoundNanos > 0 }.map { it.latencyBoundNanos / 1e6 }),
            targetMs = targetMs,
        )
    }

    @Synchronized
    fun exportCsv(file: File) {
        file.bufferedWriter().use { w ->
            w.write(
                "kind,categories,send_ns,written_ns,first_rx_ns,complete_ns,decoded_ns,display_ns," +
                    "request_bytes,response_bytes,outcome,speed_kmh,shift,vehicle_ts_ms,latency_bound_ms\n",
            )
            for (r in rows) {
                val res = r.result
                val outcome = when (val o = res.outcome) {
                    is Outcome.Ok -> "ok"
                    is Outcome.Timeout -> "timeout"
                    is Outcome.Failed -> "failed:" + o.reason.replace(',', ';')
                }
                w.write(
                    listOf(
                        r.kind, res.categories.sorted().joinToString("+"), res.sendNanos, res.writtenNanos,
                        res.firstRxNanos, res.completeNanos, res.decodedNanos, r.displayNanos,
                        res.requestBytes, res.responseBytes, outcome, r.fast?.speedKmh ?: "", r.fast?.shiftState ?: "",
                        r.fast?.vehicleTimestampMillis ?: "",
                        if (r.latencyBoundNanos > 0) "%.2f".format(r.latencyBoundNanos / 1e6) else "",
                    ).joinToString(",") + "\n",
                )
            }
        }
    }

    companion object {
        const val FAST_TARGET_MS = 500.0
        const val SLOW_TARGET_MS = 3000.0
    }
}

data class Report(
    val elapsedSeconds: Double,
    val linkDrops: Int,
    val fast: PathStats,
    val fastDrawn: Int,
    val vehicleTimestampUnchangedPercent: Double?,
    val vehicleTimestampChangeMs: Percentiles?,
    val slow: Map<String, PathStats>,
)

data class PathStats(
    val requests: Int,
    val ok: Int,
    val timeouts: Int,
    val failures: Int,
    val lastFailure: String?,
    val rttMs: Percentiles?,
    val writeMs: Percentiles?,
    val responseBytes: Percentiles?,
    val latencyBoundMs: Percentiles?,
    val targetMs: Double,
) {
    /** Spike verdict: P95 of the latency bound must not exceed the target. */
    val pass: Boolean? get() = latencyBoundMs?.let { it.p95 <= targetMs }
}

data class Percentiles(val n: Int, val p50: Double, val p95: Double, val p99: Double, val max: Double) {
    companion object {
        fun of(values: List<Double>): Percentiles? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            // Nearest-rank percentile.
            fun pick(q: Double) = sorted[(kotlin.math.ceil(q * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
            return Percentiles(sorted.size, pick(0.50), pick(0.95), pick(0.99), sorted.last())
        }
    }
}
