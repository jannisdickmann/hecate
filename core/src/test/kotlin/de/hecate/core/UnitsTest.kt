package de.hecate.core

import de.hecate.core.display.Units
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The shared distance and duration strings, pinned to what the phone and the
 * watch printed before either used this: same boundaries, same rounding, a
 * comma for the decimal point whatever this laptop's locale.
 */
class UnitsTest {

    @Test
    fun `distance switches to kilometres at exactly one`() {
        assertEquals("0 m", Units.distance(0.0))
        assertEquals("840 m", Units.distance(840.4))
        assertEquals("1000 m", Units.distance(999.6))
        assertEquals("1,00 km", Units.distance(1000.0))
        assertEquals("4,20 km", Units.distance(4199.9))
        assertEquals("123,46 km", Units.distance(123_456.0))
    }

    @Test
    fun `duration grows an hour field only when it needs one`() {
        assertEquals("0:00", Units.duration(0))
        assertEquals("0:59", Units.duration(59_999))
        assertEquals("23:11", Units.duration(23 * 60_000L + 11_000))
        assertEquals("59:59", Units.duration(3_599_999))
        assertEquals("1:00:00", Units.duration(3_600_000))
        assertEquals("1:04:22", Units.duration(3_862_000))
        assertEquals("12:00:05", Units.duration(43_205_000))
    }

    @Test
    fun `a negative duration reads as zero`() {
        assertEquals("0:00", Units.duration(-5_000))
    }
}
