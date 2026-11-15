package io.github.myungjoo.nntesla.spike

import org.junit.Assert.assertEquals
import org.junit.Test

class CompassTest {
    @Test
    fun namesEightDirections() {
        assertEquals("N", Compass.name(0))
        assertEquals("N", Compass.name(22))
        assertEquals("NE", Compass.name(23))
        assertEquals("E", Compass.name(90))
        assertEquals("S", Compass.name(180))
        assertEquals("NW", Compass.name(337))
        assertEquals("N", Compass.name(338))
        assertEquals("N", Compass.name(360))
        assertEquals("W", Compass.name(-90))
        assertEquals("-", Compass.name(null))
    }
}
