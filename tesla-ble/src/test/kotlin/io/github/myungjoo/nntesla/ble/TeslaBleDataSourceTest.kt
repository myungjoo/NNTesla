package io.github.myungjoo.nntesla.ble

import com.tesla.generated.carserver.vehicle.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeslaBleDataSourceTest {
    @Test
    fun mapsRouteOdometerAndHeading() {
        val source = TeslaBleDataSource()
        val data = Vehicle.VehicleData.newBuilder()
            .setDriveState(
                Vehicle.DriveState.newBuilder()
                    .setActiveRouteDestination("Seoul Station")
                    .setActiveRouteMinutesToArrival(37f)
                    .setActiveRouteMilesToArrival(10f)
                    .setActiveRouteTrafficMinutesDelay(5f)
                    .setActiveRouteEnergyAtArrival(61f)
                    .setOdometerInHundredthsOfAMile(1_000_000),
            )
            .setLocationState(Vehicle.LocationState.newBuilder().setHeading(270))
            .build()
        source.publish(data, receivedAtNanos = 1L)
        val s = source.slow.value
        assertEquals(16.09344f, s.routeDistanceKm!!.value!!, 0.001f)
        assertEquals(5f, s.trafficDelayMinutes!!.value!!, 0f)
        assertEquals(61f, s.energyAtArrivalPercent!!.value!!, 0f)
        // 10,000 mi = 16,093.44 km.
        assertEquals(16093.44f, s.odometerKm!!.value!!, 0.1f)
        assertEquals(270, s.headingDegrees!!.value)
    }

    @Test
    fun mapsClosures() {
        val source = TeslaBleDataSource()
        val data = Vehicle.VehicleData.newBuilder()
            .setClosuresState(
                Vehicle.ClosuresState.newBuilder()
                    .setDoorOpenDriverFront(false)
                    .setDoorOpenPassengerRear(true)
                    .setDoorOpenTrunkRear(false)
                    .setWindowOpenDriverFront(true)
                    .setLocked(false),
            )
            .build()
        source.publish(data, receivedAtNanos = 1L)
        val c = source.slow.value.closures!!.value!!
        assertEquals(false, c.frontLeftDoorOpen)
        assertEquals(true, c.rearRightDoorOpen)
        assertNull("not reported stays null", c.frontRightDoorOpen)
        assertTrue(c.anyDoorOpen)
        assertFalse(c.anyLidOpen)
        assertTrue(c.anyWindowOpen)
        assertEquals(false, c.locked)
        assertNull(c.sentryArmed)
    }

    @Test
    fun mapsMedia() {
        val source = TeslaBleDataSource()
        val data = Vehicle.VehicleData.newBuilder()
            .setMediaState(
                Vehicle.MediaState.newBuilder()
                    .setNowPlayingArtist("Artist")
                    .setNowPlayingTitle("")
                    .setNowPlayingSource(Vehicle.MediaSourceType.MediaSourceType_Spotify),
            )
            .build()
        source.publish(data, receivedAtNanos = 1L)
        val m = source.slow.value.media!!.value!!
        assertEquals("Artist", m.artist)
        assertNull("empty title is treated as missing", m.title)
        assertEquals("Spotify", m.source)
    }

    private fun location(heading: Int?): Vehicle.VehicleData {
        val l = Vehicle.LocationState.newBuilder()
        if (heading != null) l.setHeading(heading)
        return Vehicle.VehicleData.newBuilder().setLocationState(l).build()
    }

    /** 회귀: 차량이 heading을 빼고 보낸 응답마다 방향이 "-"로 깜빡였다. */
    @Test
    fun missingHeadingKeepsLastValue() {
        val source = TeslaBleDataSource()
        source.publish(location(90), receivedAtNanos = 0L)
        source.publish(location(null), receivedAtNanos = 2_500_000_000L)
        assertEquals(90, source.slow.value.headingDegrees!!.value)
        source.publish(location(null), receivedAtNanos = 5_000_000_000L)
        assertEquals(90, source.slow.value.headingDegrees!!.value)
        source.publish(location(95), receivedAtNanos = 7_500_000_000L)
        assertEquals(95, source.slow.value.headingDegrees!!.value)
    }

    @Test
    fun headingBecomesUnknownAfterHoldTime() {
        val source = TeslaBleDataSource()
        source.publish(location(90), receivedAtNanos = 0L)
        // 직전 값의 시각 기준이라, heading 없는 응답이 이어져도 10초가 지나면 모름으로 바뀐다.
        source.publish(location(null), receivedAtNanos = 7_500_000_000L)
        assertEquals(90, source.slow.value.headingDegrees!!.value)
        source.publish(location(null), receivedAtNanos = 10_000_000_000L)
        assertNull(source.slow.value.headingDegrees!!.value)
    }

    @Test
    fun missingHeadingWithoutHistoryIsUnknown() {
        val source = TeslaBleDataSource()
        source.publish(location(null), receivedAtNanos = 0L)
        assertNull(source.slow.value.headingDegrees!!.value)
    }

    @Test
    fun northIsAValidHeading() {
        val source = TeslaBleDataSource()
        source.publish(location(0), receivedAtNanos = 0L)
        assertEquals(0, source.slow.value.headingDegrees!!.value)
    }
}
