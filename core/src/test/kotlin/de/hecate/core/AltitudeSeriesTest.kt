package de.hecate.core

import de.hecate.core.io.SessionCodec
import de.hecate.core.io.SessionRecord
import de.hecate.core.model.Altitudes
import de.hecate.core.model.Reading
import de.hecate.core.model.Readings
import de.hecate.core.model.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The barometer reports far more slowly than positions arrive, which is the
 * whole reason altitude is a series (#6). These are the properties that makes it
 * worth being one.
 */
class AltitudeSeriesTest {

    private fun sample(tMs: Long) = Sample(tOffsetMs = tMs, activeMs = tMs, lat = 49.9, lon = 10.9)

    @Test
    fun `a reading every thirty seconds still gives every second an altitude`() {
        // The measured cadence: one reading per 30 s against a position a second,
        // here over a twenty-minute climb of 3 m per reading.
        val readings = (0..40).map { Reading(it * 30_000L, 260.0 + it * 3.0) }
        val samples = (0..1_200).map { sample(it * 1_000L) }

        val merged = Altitudes.merge(samples, readings)

        assertEquals(samples.size, merged.size)
        assertTrue(merged.all { it.altM != null }, "every sample inside the covered span")
        // In the interior a monotonic run passes through a centred median
        // unchanged, so the interpolation is the true ramp: halfway through a
        // half-minute is halfway up that reading's 3 m.
        assertEquals(290.0, merged.first { it.tOffsetMs == 300_000L }.altM!!, 1e-6)
        assertEquals(291.5, merged.first { it.tOffsetMs == 315_000L }.altM!!, 1e-6)
    }

    @Test
    fun `a series too short to median is used as measured rather than flattened`() {
        // Three readings and a three-wide window: the window is the whole series
        // at every index, so a median would make every reading the middle one and
        // a ninety-second walk would come back dead flat at its own average
        // height.
        val readings = (0..2).map { Reading(it * 30_000L, 260.0 + it * 3.0) }
        val merged = Altitudes.merge(listOf(sample(0L), sample(60_000L)), readings)

        assertEquals(260.0, merged[0].altM!!, 1e-6)
        assertEquals(266.0, merged[1].altM!!, 1e-6)
    }

    @Test
    fun `a hole longer than the gap is left as a hole rather than drawn through`() {
        // Not a quiet sensor — an absent one. The bound exists for a
        // subscription that died or a recording resumed much later, so the test
        // states it in those terms: two readings an hour apart against a
        // ten-minute gap.
        val readings = listOf(Reading(0L, 260.0), Reading(3_600_000L, 400.0))
        val samples = (0..3_600 step 60).map { sample(it * 1_000L) }

        val merged = Altitudes.merge(samples, readings)

        // Either end stands for as long as the gap allows, and the middle — where
        // nothing was recorded — says so instead of inventing a ramp.
        assertNotNull(merged.first { it.tOffsetMs == 0L }.altM)
        assertNull(merged.first { it.tOffsetMs == 1_800_000L }.altM)
        assertNotNull(merged.first { it.tOffsetMs == 3_600_000L }.altM)
    }

    @Test
    fun `a long silence on flat ground still yields a profile`() {
        // The measured pattern: the sensor reports on change, so a flat stretch
        // is quiet. Seven readings across nineteen minutes is a real session, and
        // it has to come back as a profile rather than as nothing.
        val readings = (0..6).map { Reading(it * 163_000L, 260.0) }
        val samples = (0..978).map { sample(it * 1_000L) }

        val merged = Altitudes.merge(samples, readings)

        assertTrue(merged.all { it.altM != null }, "quiet is not the same as absent")
    }

    @Test
    fun `one bad reading is outvoted instead of interpolating into a ramp`() {
        // Long enough for the median to have neighbours to outvote with; the
        // short-series case is the test above, where nothing is done at all.
        val readings = (0..10).map { i ->
            // The altimeter losing its mind for exactly one reading.
            Reading(i * 30_000L, if (i == 5) 1_500.0 else 260.0)
        }
        val merged = Altitudes.merge(listOf(sample(150_000L)), readings)

        assertEquals(260.0, merged[0].altM!!, 0.001)
    }

    @Test
    fun `an import with no series keeps the altitude it already had`() {
        val samples = listOf(sample(0L).copy(altM = 300.0))
        assertEquals(300.0, Altitudes.merge(samples, emptyList())[0].altM)
    }

    @Test
    fun `a value no place on earth has is not a reading`() {
        val readings = listOf(Reading(0L, Double.MAX_VALUE), Reading(1_000L, Double.NaN))
        assertNull(Altitudes.merge(listOf(sample(0L)), readings)[0].altM)
    }

    @Test
    fun `interpolation does not depend on the order the times are asked in`() {
        val readings = listOf(Reading(0L, 100.0), Reading(10_000L, 110.0))
        val forwards = Readings.interpolatedAt(readings, longArrayOf(0, 5_000, 10_000), 60_000L)
        val backwards = Readings.interpolatedAt(readings, longArrayOf(10_000, 5_000, 0), 60_000L)

        assertEquals(forwards[1], backwards[1], 1e-9)
        assertEquals(forwards[0], backwards[2], 1e-9)
    }

    @Test
    fun `the new lines survive the session file`() {
        val text = listOf(
            SessionCodec.encodeLine(SessionRecord.AltitudeLine(Reading(1_000L, 260.5))),
            SessionCodec.encodeLine(SessionRecord.SpeedLine(Reading(1_000L, 4.25))),
        ).joinToString("\n")

        val back = text.lineSequence().mapNotNull { SessionCodec.decodeLineOrNull(it) }.toList()
        assertEquals(260.5, (back[0] as SessionRecord.AltitudeLine).v.value)
        assertEquals(4.25, (back[1] as SessionRecord.SpeedLine).v.value)
    }

}
