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
}
