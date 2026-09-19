package de.hecate.core

import de.hecate.core.profile.Series
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Holding a heart rate across the holes between readings.
 *
 * The bound is the whole point: without one this is "assume the last thing you
 * heard, for ever", which draws an hour of effort from one reading at the start.
 */
class SeriesHoldTest {

    private val N = Double.NaN

    @Test
    fun `a hole shorter than the bound takes the last reading`() {
        val values = doubleArrayOf(120.0, N, N, N, 140.0)
        val times = longArrayOf(0, 1000, 2000, 3000, 4000)
        val held = Series.hold(values, times, maxGapMs = 30_000)
        assertEquals(listOf(120.0, 120.0, 120.0, 120.0, 140.0), held.toList())
    }

    @Test
    fun `a hole longer than the bound stays a hole`() {
        val values = doubleArrayOf(120.0, N, N, 140.0)
        val times = longArrayOf(0, 10_000, 40_000, 41_000)
        val held = Series.hold(values, times, maxGapMs = 30_000)
        assertEquals(120.0, held[1])
        assertTrue(held[2].isNaN(), "a reading 40 s old is not a reading")
        assertEquals(140.0, held[3])
    }

    @Test
    fun `nothing is invented before the first reading`() {
        val values = doubleArrayOf(N, N, 130.0)
        val held = Series.hold(values, longArrayOf(0, 1000, 2000), maxGapMs = 30_000)
        assertTrue(held[0].isNaN())
        assertTrue(held[1].isNaN())
        assertEquals(130.0, held[2])
    }

    @Test
    fun `the bound is measured in time, not in samples`() {
        // The same three holes, sampled a second apart and a minute apart: one
        // is a gap between readings, the other is a strap that came off.
        val dense = Series.hold(
            doubleArrayOf(120.0, N, N, N),
            longArrayOf(0, 1000, 2000, 3000),
            maxGapMs = 30_000,
        )
        val sparse = Series.hold(
            doubleArrayOf(120.0, N, N, N),
            longArrayOf(0, 60_000, 120_000, 180_000),
            maxGapMs = 30_000,
        )
        assertTrue(dense.all { !it.isNaN() })
        assertTrue(sparse.drop(1).all { it.isNaN() })
    }
}
