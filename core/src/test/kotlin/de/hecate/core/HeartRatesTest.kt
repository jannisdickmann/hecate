package de.hecate.core

import de.hecate.core.io.SessionCodec
import de.hecate.core.model.HeartRate
import de.hecate.core.model.HeartRates
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.Sample
import de.hecate.core.model.SessionHeader
import de.hecate.core.model.ExerciseType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Joining the two sensors' series.
 *
 * The bug behind this one lost data rather than mangling it: the recorder kept
 * one reading per delivery, so a 47-minute ride came back with 161 of them and
 * a chart with thirty minutes of holes. Nothing here can recover that — these
 * pin the join, so that what the sensor gives from now on arrives on the right
 * second.
 */
class HeartRatesTest {

    private fun sample(t: Long, hr: Int? = null) = Sample(
        tOffsetMs = t,
        activeMs = t,
        lat = 50.0,
        lon = 8.9,
        altM = 100.0,
        hrBpm = hr,
        paused = false,
    )

    @Test
    fun `each position takes the reading nearest it in time`() {
        val samples = List(5) { sample(it * 1000L) }
        val rates = listOf(
            HeartRate(400, 120),
            HeartRate(2600, 140),
            HeartRate(4100, 150),
        )
        val merged = HeartRates.merge(samples, rates)
        // 0 ms is nearest 400, 1000 is nearest 400, 2000 and 3000 nearest 2600,
        // 4000 nearest 4100.
        assertEquals(listOf(120, 120, 140, 140, 150), merged.map { it.hrBpm })
    }

    @Test
    fun `a reading too far away is not borrowed`() {
        val merged = HeartRates.merge(
            listOf(sample(0), sample(60_000)),
            listOf(HeartRate(0, 130)),
        )
        assertEquals(130, merged[0].hrBpm)
        assertNull(merged[1].hrBpm, "a reading a minute away describes a different part of the ride")
    }

    @Test
    fun `a session with no series of its own is untouched`() {
        // Every import, and every recording made before the watch wrote one.
        val samples = listOf(sample(0, 111), sample(1000, 112))
        assertEquals(samples, HeartRates.merge(samples, emptyList()))
    }

    @Test
    fun `an unordered series is still joined correctly`() {
        // Batches can arrive out of order; the file is append-only and keeps
        // whatever order they came in.
        val merged = HeartRates.merge(
            List(3) { sample(it * 1000L) },
            listOf(HeartRate(2000, 150), HeartRate(0, 120), HeartRate(1000, 130)),
        )
        assertEquals(listOf(120, 130, 150), merged.map { it.hrBpm })
    }

    @Test
    fun `the series survives the file format`() {
        val session = RecordedSession(
            header = SessionHeader(
                sessionId = "s1",
                startedAtUtcMs = 1_700_000_000_000,
                tzOffsetMinutes = 120,
                exerciseType = ExerciseType.BIKING,
            ),
            samples = List(3) { sample(it * 1000L) },
            heartRates = List(3) { HeartRate(it * 1000L, 130 + it) },
        )
        val text = SessionCodec.encodeSession(session)
        val back = SessionCodec.decodeSession(text.lineSequence())!!
        assertEquals(session.heartRates, back.heartRates)

        // And a reader that has never heard of the record still reads the rest:
        // the format's whole promise is that an unknown line is dropped, not
        // fatal.
        val withUnknown = text + "{\"k\":\"zz\",\"v\":{}}\n"
        val tolerant = SessionCodec.decodeSession(withUnknown.lineSequence())!!
        assertEquals(3, tolerant.samples.size)
        assertTrue(tolerant.heartRates.isNotEmpty())
    }
}
