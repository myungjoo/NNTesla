package io.github.myungjoo.nntesla.vehicledata

import kotlinx.coroutines.flow.StateFlow

/**
 * A source of cluster data. Implementations must publish fast-path values (speed) as soon as
 * they are decoded: no buffering, batching or throttling between the transport and [fast].
 */
interface VehicleDataSource {
    /** Latency-critical data (speedometer). Target: P95 display latency <= 0.5 s. */
    val fast: StateFlow<FastState?>

    /** Everything else. Target: P95 display latency <= 3 s. */
    val slow: StateFlow<SlowState>
}

enum class ShiftState { P, R, N, D, UNKNOWN }

/**
 * All values use SI-based units (km/h, km, degrees Celsius, kPa, kW). Sources convert from
 * whatever unit the vehicle reports before publishing.
 *
 * @property speedKmh vehicle speed in km/h.
 * @property receivedAtNanos monotonic (System.nanoTime) time the value was decoded.
 */
data class FastState(
    val speedKmh: Float?,
    val shiftState: ShiftState,
    val powerKw: Int?,
    val vehicleTimestampMillis: Long?,
    val receivedAtNanos: Long,
)

/** A value with the monotonic time it was received; null value means "not reported". */
data class Timed<T>(val value: T, val receivedAtNanos: Long)

data class SlowState(
    val batteryLevelPercent: Timed<Int?>? = null,
    val batteryRangeKm: Timed<Float?>? = null,
    val insideTempCelsius: Timed<Float?>? = null,
    val outsideTempCelsius: Timed<Float?>? = null,
    val destination: Timed<String?>? = null,
    val minutesToArrival: Timed<Float?>? = null,
    /** Front-left, front-right, rear-left, rear-right. */
    val tirePressureKpa: Timed<List<Float?>>? = null,
)
