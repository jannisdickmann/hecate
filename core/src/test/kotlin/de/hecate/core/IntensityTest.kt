package de.hecate.core

import de.hecate.core.model.Intensity
import de.hecate.core.profile.Series
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The zones colour a chart, so a wrong boundary produces a picture rather than
 * an error — the same failure mode as every other piece of arithmetic that is
 * only ever seen as a drawing.
 */
class IntensityTest {

    @Test
    fun `the boundaries are the fractions the constants name`() {
        val peak = 200
        // Exactly on a boundary belongs to the band above it: the bounds are
        // lower bounds, inclusive.
        assertEquals(Intensity.EASY, Intensity.of(139.0, peak))
        assertEquals(Intensity.MODERATE, Intensity.of(140.0, peak))
        assertEquals(Intensity.MODERATE, Intensity.of(159.0, peak))
        assertEquals(Intensity.HARD, Intensity.of(160.0, peak))
        assertEquals(Intensity.HARD, Intensity.of(179.0, peak))
        assertEquals(Intensity.PEAK, Intensity.of(180.0, peak))
    }

    @Test
    fun `a rate above the peak saturates rather than falling off the end`() {
        assertEquals(Intensity.PEAK, Intensity.of(210.0, 180))
    }

    @Test
    fun `no reading is not the same as an easy one`() {
        // Drawn in the neutral colour, not in the colour of a gentle day: a
        // recording from before the user owned a strap must not claim to have
        // been easy.
        assertNull(Intensity.of(null, 180))
        assertNull(Intensity.of(0.0, 180), "a sensor that lost the wrist reports zero")
        assertNull(Intensity.of(-5.0, 180))
        assertNull(Intensity.of(Double.NaN, 180))
    }

    @Test
    fun `a nonsensical peak yields nothing rather than dividing by it`() {
        assertNull(Intensity.of(150.0, 0))
    }

    @Test
    fun `smoothing settles a reading that keeps crossing a boundary`() {
        // A steady effort just under the Mittel/Intensiv line, with the sensor
        // reaching over it every few seconds. Unsmoothed, the drawing changes
        // colour eight times in forty seconds.
        val peak = 180
        val boundary = peak * Tuning.HR_ZONE_HARD_FRACTION // 144
        val raw = DoubleArray(40) { if (it % 5 == 0) boundary + 2 else boundary - 2 }
        assertEquals(8, raw.count { Intensity.of(it, peak) == Intensity.HARD })

        val smoothed = Series.rollingMedian(raw, Tuning.HR_MEDIAN_WINDOW)
        val bands = smoothed.map { Intensity.of(it, peak) }.distinct()
        assertEquals(listOf(Intensity.MODERATE), bands, "still flickering between $bands")

        // What it cannot do, and does not claim to: a reading balanced exactly on
        // the line has no band, and a filter cannot invent one. Half a window
        // either way is half a window either way.
        val onTheLine = DoubleArray(40) { if (it % 2 == 0) boundary + 1 else boundary - 1 }
        assertEquals(
            2,
            Series.rollingMedian(onTheLine, Tuning.HR_MEDIAN_WINDOW)
                .map { Intensity.of(it, peak) }.distinct().size,
        )
    }

    @Test
    fun `smoothing leaves a real effort alone`() {
        // A median is the identity on a monotonic run, so a ramp from an easy
        // start to a maximal finish still ends where it ended.
        val ramp = DoubleArray(60) { 110.0 + it }
        val smoothed = Series.rollingMedian(ramp, Tuning.HR_MEDIAN_WINDOW)
        assertEquals(Intensity.of(ramp.last(), 180), Intensity.of(smoothed.last(), 180))
        assertEquals(Intensity.of(ramp.first(), 180), Intensity.of(smoothed.first(), 180))
    }

    @Test
    fun `the default peak is the one the settings field starts at`() {
        assertEquals(Intensity.of(150.0), Intensity.of(150.0, Tuning.DEFAULT_PEAK_HR_BPM))
    }
}
