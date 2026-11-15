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
 * @property speed raw speed as reported by the vehicle; [SpeedUnit] records the assumed unit.
 * @property receivedAtNanos monotonic (System.nanoTime) time the value was decoded.
 */
data class FastState(
    val speed: Float?,
    val speedUnit: SpeedUnit,
    val shiftState: ShiftState,
    val powerKw: Int?,
    val vehicleTimestampMillis: Long?,
    val receivedAtNanos: Long,
)

enum class SpeedUnit { MPH, KPH }

/** A value with the monotonic time it was received; null value means "not reported". */
data class Timed<T>(val value: T, val receivedAtNanos: Long)

data class SlowState(
    val batteryLevelPercent: Timed<Int?>? = null,
    val batteryRangeMiles: Timed<Float?>? = null,
    val insideTempCelsius: Timed<Float?>? = null,
    val outsideTempCelsius: Timed<Float?>? = null,
    val destination: Timed<String?>? = null,
    val minutesToArrival: Timed<Float?>? = null,
    val tirePressureBar: Timed<List<Float?>>? = null,
)
