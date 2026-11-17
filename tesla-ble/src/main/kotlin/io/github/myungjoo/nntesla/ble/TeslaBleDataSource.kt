package io.github.myungjoo.nntesla.ble

import android.util.Log
import com.tesla.generated.carserver.vehicle.Vehicle
import io.github.myungjoo.nntesla.vehicledata.Closures
import io.github.myungjoo.nntesla.vehicledata.FastState
import io.github.myungjoo.nntesla.vehicledata.Media
import io.github.myungjoo.nntesla.vehicledata.ShiftState
import io.github.myungjoo.nntesla.vehicledata.SlowState
import io.github.myungjoo.nntesla.vehicledata.Timed
import io.github.myungjoo.nntesla.vehicledata.VehicleDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Maps `GetVehicleData` replies to the source-agnostic cluster model. [publish] runs on the BLE
 * thread and writes the StateFlows directly, so there is no extra thread hop before the UI.
 */
class TeslaBleDataSource : VehicleDataSource {
    private val fastFlow = MutableStateFlow<FastState?>(null)
    private val slowFlow = MutableStateFlow(SlowState())
    override val fast: StateFlow<FastState?> = fastFlow
    override val slow: StateFlow<SlowState> = slowFlow

    /** Returns the published fast state if [data] carried drive state, else null. */
    fun publish(data: Vehicle.VehicleData, receivedAtNanos: Long): FastState? {
        var published: FastState? = null
        if (data.hasDriveState()) {
            val drive = data.driveState
            val state = FastState(
                // The vehicle's unit is not documented upstream; mph is assumed (see
                // docs/ble-data-inventory.md) and must be confirmed against the car's display.
                speedKmh = when {
                    drive.hasSpeedFloat() -> drive.speedFloat * KM_PER_MILE
                    drive.hasSpeed() -> drive.speed.toFloat() * KM_PER_MILE
                    else -> null
                },
                shiftState = when (drive.shiftState.typeCase) {
                    Vehicle.ShiftState.TypeCase.P -> ShiftState.P
                    Vehicle.ShiftState.TypeCase.R -> ShiftState.R
                    Vehicle.ShiftState.TypeCase.N -> ShiftState.N
                    Vehicle.ShiftState.TypeCase.D -> ShiftState.D
                    else -> ShiftState.UNKNOWN
                },
                powerKw = if (drive.hasPower()) drive.power else null,
                vehicleTimestampMillis = if (drive.hasTimestamp()) {
                    drive.timestamp.seconds * 1000 + drive.timestamp.nanos / 1_000_000
                } else {
                    null
                },
                receivedAtNanos = receivedAtNanos,
            )
            fastFlow.value = state
            published = state
        }
        if (data.hasChargeState() || data.hasClimateState() || data.hasTirePressureState() || data.hasDriveState() ||
            data.hasClosuresState() || data.hasLocationState() || data.hasMediaState()
        ) {
            slowFlow.value = mergeSlow(slowFlow.value, data, receivedAtNanos)
        }
        return published
    }

    private fun mergeSlow(old: SlowState, data: Vehicle.VehicleData, t: Long): SlowState {
        var s = old
        if (data.hasChargeState()) {
            val c = data.chargeState
            s = s.copy(
                batteryLevelPercent = Timed(if (c.hasBatteryLevel()) c.batteryLevel else null, t),
                batteryRangeKm = Timed(if (c.hasBatteryRange()) c.batteryRange * KM_PER_MILE else null, t),
            )
        }
        if (data.hasClimateState()) {
            val c = data.climateState
            s = s.copy(
                insideTempCelsius = Timed(if (c.hasInsideTempCelsius()) c.insideTempCelsius else null, t),
                outsideTempCelsius = Timed(if (c.hasOutsideTempCelsius()) c.outsideTempCelsius else null, t),
            )
        }
        if (data.hasTirePressureState()) {
            val p = data.tirePressureState
            s = s.copy(
                // The proto reports bar.
                tirePressureKpa = Timed(
                    listOf(
                        if (p.hasTpmsPressureFl()) p.tpmsPressureFl * KPA_PER_BAR else null,
                        if (p.hasTpmsPressureFr()) p.tpmsPressureFr * KPA_PER_BAR else null,
                        if (p.hasTpmsPressureRl()) p.tpmsPressureRl * KPA_PER_BAR else null,
                        if (p.hasTpmsPressureRr()) p.tpmsPressureRr * KPA_PER_BAR else null,
                    ),
                    t,
                ),
            )
        }
        if (data.hasDriveState()) {
            // Navigation rides along in DriveState, so it refreshes at the fast-path rate for free.
            val d = data.driveState
            s = s.copy(
                destination = Timed(if (d.hasActiveRouteDestination()) d.activeRouteDestination else null, t),
                minutesToArrival = Timed(if (d.hasActiveRouteMinutesToArrival()) d.activeRouteMinutesToArrival else null, t),
                routeDistanceKm = Timed(if (d.hasActiveRouteMilesToArrival()) d.activeRouteMilesToArrival * KM_PER_MILE else null, t),
                trafficDelayMinutes = Timed(if (d.hasActiveRouteTrafficMinutesDelay()) d.activeRouteTrafficMinutesDelay else null, t),
                energyAtArrivalPercent = Timed(if (d.hasActiveRouteEnergyAtArrival()) d.activeRouteEnergyAtArrival else null, t),
                odometerKm = Timed(
                    if (d.hasOdometerInHundredthsOfAMile()) d.odometerInHundredthsOfAMile / 100f * KM_PER_MILE else null,
                    t,
                ),
            )
        }
        if (data.hasLocationState()) {
            val l = data.locationState
            val last = s.headingDegrees
            // 차량이 위치 응답에서 heading을 빼고 보낼 때가 있다(0°=정북을 생략하는지, GPS 상태에 따라 빼는지는
            // 아직 모른다). 그때마다 "모름"으로 덮으면 방향 표시가 깜빡이므로 직전 값을 유지하고,
            // [HEADING_HOLD_NANOS]보다 오래되면 그때 모름으로 바꾼다.
            val heading: Timed<Int?> = when {
                l.hasHeading() -> Timed<Int?>(l.heading, t)
                last?.value != null && t - last.receivedAtNanos < HEADING_HOLD_NANOS -> last
                else -> Timed(null, t)
            }
            if (!l.hasHeading()) logMissingHeading(l, last, t)
            s = s.copy(headingDegrees = heading)
        }
        if (data.hasClosuresState()) {
            val c = data.closuresState
            s = s.copy(
                closures = Timed(
                    Closures(
                        frontLeftDoorOpen = if (c.hasDoorOpenDriverFront()) c.doorOpenDriverFront else null,
                        frontRightDoorOpen = if (c.hasDoorOpenPassengerFront()) c.doorOpenPassengerFront else null,
                        rearLeftDoorOpen = if (c.hasDoorOpenDriverRear()) c.doorOpenDriverRear else null,
                        rearRightDoorOpen = if (c.hasDoorOpenPassengerRear()) c.doorOpenPassengerRear else null,
                        frunkOpen = if (c.hasDoorOpenTrunkFront()) c.doorOpenTrunkFront else null,
                        trunkOpen = if (c.hasDoorOpenTrunkRear()) c.doorOpenTrunkRear else null,
                        windowsOpen = listOf(
                            if (c.hasWindowOpenDriverFront()) c.windowOpenDriverFront else null,
                            if (c.hasWindowOpenPassengerFront()) c.windowOpenPassengerFront else null,
                            if (c.hasWindowOpenDriverRear()) c.windowOpenDriverRear else null,
                            if (c.hasWindowOpenPassengerRear()) c.windowOpenPassengerRear else null,
                        ),
                        locked = if (c.hasLocked()) c.locked else null,
                        sentryArmed = if (c.hasSentryModeState()) {
                            c.sentryModeState.typeCase.let {
                                it != Vehicle.ClosuresState.SentryModeState.TypeCase.OFF &&
                                    it != Vehicle.ClosuresState.SentryModeState.TypeCase.TYPE_NOT_SET
                            }
                        } else {
                            null
                        },
                    ),
                    t,
                ),
            )
        }
        if (data.hasMediaState()) {
            val m = data.mediaState
            s = s.copy(
                media = Timed(
                    Media(
                        artist = if (m.hasNowPlayingArtist()) m.nowPlayingArtist.ifEmpty { null } else null,
                        title = if (m.hasNowPlayingTitle()) m.nowPlayingTitle.ifEmpty { null } else null,
                        source = if (m.hasNowPlayingSource()) m.nowPlayingSource.name.removePrefix("MediaSourceType_") else null,
                    ),
                    t,
                ),
            )
        }
        return s
    }

    /** 원인 확인용: heading이 빠진 위치 응답의 나머지 GPS 필드와 직전 방향. */
    private fun logMissingHeading(l: Vehicle.LocationState, last: Timed<Int?>?, t: Long) {
        val age = last?.let { "%.1fs".format((t - it.receivedAtNanos) / 1e9) } ?: "-"
        Log.i(
            TAG,
            "location without heading: last=${last?.value} ($age ago), " +
                "geo_heading=${if (l.hasGeoHeading()) l.geoHeading else "-"}, " +
                "gps_as_of=${if (l.hasGpsAsOf()) l.gpsAsOf else "-"}, " +
                "estimated_gps_valid=${if (l.hasEstimatedGpsValid()) l.estimatedGpsValid else "-"}",
        )
    }

    private companion object {
        const val TAG = "NNTesla.Data"

        /** heading이 빠진 응답이 와도 직전 방향을 유지하는 시간. 위치는 약 2.5초마다 요청한다. */
        const val HEADING_HOLD_NANOS = 10_000_000_000L
        const val KM_PER_MILE = 1.609344f
        const val KPA_PER_BAR = 100f
    }
}
