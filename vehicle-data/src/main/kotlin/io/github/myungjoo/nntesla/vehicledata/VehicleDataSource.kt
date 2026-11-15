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
    /** 경로 남은 거리. */
    val routeDistanceKm: Timed<Float?>? = null,
    /** 교통 때문에 늘어난 시간. */
    val trafficDelayMinutes: Timed<Float?>? = null,
    /** 도착 시 예상 배터리. 차량 단위가 문서에 없어 %로 가정한다(docs/ble-data-inventory.md). */
    val energyAtArrivalPercent: Timed<Float?>? = null,
    val odometerKm: Timed<Float?>? = null,
    /** 진행 방향, 북쪽 0도에서 시계 방향. */
    val headingDegrees: Timed<Int?>? = null,
    val closures: Timed<Closures?>? = null,
    val media: Timed<Media?>? = null,
)

/** 도어·트렁크·창문·잠금 상태. null은 "차량이 보고하지 않음". 운전석은 왼쪽(좌핸들) 기준이다. */
data class Closures(
    val frontLeftDoorOpen: Boolean?,
    val frontRightDoorOpen: Boolean?,
    val rearLeftDoorOpen: Boolean?,
    val rearRightDoorOpen: Boolean?,
    val frunkOpen: Boolean?,
    val trunkOpen: Boolean?,
    /** Front-left, front-right, rear-left, rear-right. */
    val windowsOpen: List<Boolean?>,
    val locked: Boolean?,
    val sentryArmed: Boolean?,
) {
    val anyDoorOpen: Boolean get() = listOf(frontLeftDoorOpen, frontRightDoorOpen, rearLeftDoorOpen, rearRightDoorOpen).any { it == true }
    val anyLidOpen: Boolean get() = frunkOpen == true || trunkOpen == true
    val anyWindowOpen: Boolean get() = windowsOpen.any { it == true }
}

data class Media(val artist: String?, val title: String?, val source: String?)
