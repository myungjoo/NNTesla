package io.github.myungjoo.nntesla.ble

import com.tesla.generated.carserver.vehicle.Vehicle
import io.github.myungjoo.nntesla.vehicledata.FastState
import io.github.myungjoo.nntesla.vehicledata.ShiftState
import io.github.myungjoo.nntesla.vehicledata.SlowState
import io.github.myungjoo.nntesla.vehicledata.SpeedUnit
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
                speed = when {
                    drive.hasSpeedFloat() -> drive.speedFloat
                    drive.hasSpeed() -> drive.speed.toFloat()
                    else -> null
                },
                // Unit is not documented upstream; MPH is assumed and must be confirmed in the spike.
                speedUnit = SpeedUnit.MPH,
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
        if (data.hasChargeState() || data.hasClimateState() || data.hasTirePressureState() || data.hasDriveState()) {
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
                batteryRangeMiles = Timed(if (c.hasBatteryRange()) c.batteryRange else null, t),
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
                tirePressureBar = Timed(
                    listOf(
                        if (p.hasTpmsPressureFl()) p.tpmsPressureFl else null,
                        if (p.hasTpmsPressureFr()) p.tpmsPressureFr else null,
                        if (p.hasTpmsPressureRl()) p.tpmsPressureRl else null,
                        if (p.hasTpmsPressureRr()) p.tpmsPressureRr else null,
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
            )
        }
        return s
    }
}
