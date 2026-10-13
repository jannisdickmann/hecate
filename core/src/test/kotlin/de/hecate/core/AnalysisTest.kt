package de.hecate.core

import de.hecate.core.model.Intensity
import de.hecate.core.model.Sample
import de.hecate.core.profile.Analysis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalysisTest {

    /** Due north at 2.5 m/s, a sample a second, climbing a metre every 100 s. */
    private fun north(seconds: Int, hr: (Int) -> Int? = { 150 }): List<Sample> {
        val degPerS = 2.5 / 111_195.0
        return (0..seconds).map { s ->
            Sample(s * 1000L, s * 1000L, 50.0 + s * degPerS, 8.9, altM = 100.0 + s / 100.0, hrBpm = hr(s))
        }
    }

    @Test
    fun `splits by the kilometre, with the remainder marked`() {
        val splits = Analysis.splits(north(1000))
        assertEquals(3, splits.size)
        assertEquals(400_000.0, splits[0].durationMs.toDouble(), 1_000.0)
        assertEquals(9.0, splits[0].kmh!!, 0.05)
        assertEquals(4.0, splits[0].netAltM!!, 0.05)
        assertTrue(splits[2].partial)
        assertEquals(500.0, splits[2].distanceM, 2.0)
        assertEquals(150.0, splits[0].avgHrBpm)
    }

    @Test
    fun `a remainder of a few metres is not a split`() {
        val splits = Analysis.splits(north(404))
        assertEquals(1, splits.size)
    }

    @Test
    fun `long recordings split by five kilometres`() {
        assertEquals(1_000.0, Analysis.splitLengthFor(20_000.0))
        assertEquals(5_000.0, Analysis.splitLengthFor(60_000.0))
    }

    @Test
    fun `time in zones adds up to the measured time`() {
        // Easy for the first half, peak for the second, against a 200 peak.
        val samples = north(600) { if (it < 300) 100 else 190 }
        val zones = Analysis.timeInZones(samples, peakHrBpm = 200)
        assertEquals(600_000L, zones.values.sum())
        assertEquals(299_000L, zones[Intensity.EASY])
        assertEquals(300_000L, zones[Intensity.PEAK])
        assertEquals(4, zones.size)
    }

    @Test
    fun `no heart rate, no zones`() {
        assertTrue(Analysis.timeInZones(north(100) { null }, 190).isEmpty())
    }

    @Test
    fun `a hole is not counted as time in any zone`() {
        val samples = north(10) + north(10).map { it.copy(activeMs = it.activeMs + 120_000) }
        assertEquals(20_000L, Analysis.timeInZones(samples, 190).values.sum())
    }

    @Test
    fun `moments are found by time and by distance`() {
        val moments = Analysis.moments(north(1000), max = 100)
        assertTrue(moments.size <= 102)
        assertEquals(1_000_000L, moments.last().activeMs)
        val m = assertNotNull(Analysis.atTime(moments, 400_000))
        assertEquals(1000.0, m.distanceM, 30.0)
        val d = assertNotNull(Analysis.atDistance(moments, 1000.0))
        assertEquals(400_000.0, d.activeMs.toDouble(), 10_000.0)
    }
}
