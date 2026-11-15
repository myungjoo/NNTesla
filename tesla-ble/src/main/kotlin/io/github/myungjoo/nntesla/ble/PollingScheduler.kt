package io.github.myungjoo.nntesla.ble

import io.github.myungjoo.nntesla.protocol.StateCategory

/**
 * Drives the fast (speed) and slow paths over one BLE link.
 *
 * - Fast path: `GetVehicleData{drive}` re-issued the moment a slot frees up (closed loop, no
 *   fixed interval), so the sample period equals the request round trip.
 * - Slow path: each slow category is due every [Config.slowPeriodMillis]; due times are staggered
 *   across the period so at most one slow request interrupts the fast loop at a time.
 * - [Config.maxInFlight] = 1 serializes everything; 2 lets one slow request overlap a fast one
 *   (or two fast requests pipeline when nothing slow is due), if the vehicle allows it.
 *
 * Runs entirely on the client's handler thread.
 */
class PollingScheduler(
    private val client: TeslaVehicleClient,
    private val config: Config,
    private val onResult: (Kind, TeslaVehicleClient.Result) -> Unit,
) {
    data class Config(
        val maxInFlight: Int = 1,
        val slowEnabled: Boolean = true,
        val slowCategories: List<StateCategory> = listOf(StateCategory.CHARGE, StateCategory.CLIMATE, StateCategory.TIRE_PRESSURE),
        /** Request every slow category in one GetVehicleData instead of one category per request. */
        val slowBatched: Boolean = false,
        val slowPeriodMillis: Long = 2_500L,
        val fastTimeoutMillis: Long = 1_000L,
        val slowTimeoutMillis: Long = 3_000L,
    ) {
        init {
            require(maxInFlight in 1..4)
        }
    }

    enum class Kind { FAST, SLOW }

    private val fastCategories = setOf(StateCategory.DRIVE)
    private val slowGroups: List<Set<StateCategory>> =
        if (config.slowBatched) listOf(config.slowCategories.toSet()) else config.slowCategories.map { setOf(it) }
    private val slowDue = LongArray(slowGroups.size)
    private var fastInFlight = 0
    private var slowInFlight = 0
    private var running = false

    fun start() = client.handler.post {
        if (running) return@post
        running = true
        val now = System.nanoTime()
        val periodNanos = config.slowPeriodMillis * 1_000_000L
        for (i in slowDue.indices) slowDue[i] = now + periodNanos * i / slowDue.size
        fill()
    }

    fun stop() = client.handler.post { running = false }

    private fun fill() {
        while (running && fastInFlight + slowInFlight < config.maxInFlight) {
            val due = nextDueSlowGroup()
            val fastHoldsSlot = config.maxInFlight == 1 || fastInFlight >= 1
            if (due >= 0 && slowInFlight == 0 && fastHoldsSlot) sendSlow(due) else sendFast()
        }
    }

    private fun nextDueSlowGroup(): Int {
        if (!config.slowEnabled || slowGroups.isEmpty()) return -1
        val now = System.nanoTime()
        var best = -1
        for (i in slowDue.indices) {
            if (slowDue[i] <= now && (best < 0 || slowDue[i] < slowDue[best])) best = i
        }
        return best
    }

    private fun sendFast() {
        fastInFlight += 1
        client.requestState(fastCategories, config.fastTimeoutMillis) { result ->
            fastInFlight -= 1
            onResult(Kind.FAST, result)
            if (result.outcome is TeslaVehicleClient.Outcome.Failed && result.outcome.reason == "not ready") {
                running = false
            }
            fill()
        }
    }

    private fun sendSlow(group: Int) {
        slowInFlight += 1
        val now = System.nanoTime()
        val periodNanos = config.slowPeriodMillis * 1_000_000L
        // Keep the cadence; if we fell a whole period behind, restart the period from now.
        val next = slowDue[group] + periodNanos
        slowDue[group] = if (next > now) next else now + periodNanos
        client.requestState(slowGroups[group], config.slowTimeoutMillis) { result ->
            slowInFlight -= 1
            onResult(Kind.SLOW, result)
            fill()
        }
    }
}
