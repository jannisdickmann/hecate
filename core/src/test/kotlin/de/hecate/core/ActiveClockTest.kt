package de.hecate.core

import de.hecate.core.model.ActiveClock
import de.hecate.core.model.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What a watch recording's own active clock did, and what it has to look like
 * afterwards.
 *
 * The bug this exists for produced a plausible chart rather than an error: a
 * clock that stands still reads as a series of zero-length intervals, which is
 * either no speed at all or an enormous one, and neither looks like a broken
 * timestamp.
 */
class ActiveClockTest {

    private fun sample(t: Long, a: Long, paused: Boolean = false) = Sample(
        tOffsetMs = t,
        activeMs = a,
        lat = 50.0,
        lon = 8.9,
        altM = 100.0,
        hrBpm = 130,
        paused = paused,
    )

    @Test
    fun `a clock that already runs is reproduced exactly`() {
        // What every takeout import looks like: one sample a second, active time
        // equal to elapsed time. Run over 62 real sessions this rule reproduced
        // the stored clock of every one of them to the millisecond, which is why
        // it can be applied to all sources rather than only to the broken one.
        val samples = List(60) { sample(it * 1000L, it * 1000L) }
        val repaired = ActiveClock.repair(samples)
        for (i in samples.indices) assertEquals(samples[i].activeMs, repaired[i].activeMs, "sample $i")
    }

    @Test
    fun `a clock that stands still is rebuilt from the sample times`() {
        // The watch's own: Health Services delivers the active duration as an
        // occasional checkpoint, so hundreds of consecutive samples carried the
        // same number.
        val samples = List(60) { sample(it * 1000L, if (it < 30) 0L else 30_000L) }
        val repaired = ActiveClock.repair(samples)
        assertEquals(59_000L, repaired.last().activeMs)
        for (i in 1 until repaired.size) {
            assertTrue(repaired[i].activeMs > repaired[i - 1].activeMs, "stood still at $i")
        }
    }

    @Test
    fun `a clock that steps backwards is rebuilt too`() {
        // Seen three times in one real ride, once by 34 minutes, after an
        // auto-pause.
        val samples = List(40) { sample(it * 1000L, if (it < 20) it * 1000L else 0L) }
        val repaired = ActiveClock.repair(samples)
        assertEquals(39_000L, repaired.last().activeMs)
        for (i in 1 until repaired.size) {
            assertTrue(repaired[i].activeMs >= repaired[i - 1].activeMs, "went backwards at $i")
        }
    }

    @Test
    fun `paused samples do not advance it`() {
        val samples = listOf(
            sample(0, 0),
            sample(1000, 0),
            sample(2000, 0, paused = true),
            sample(3000, 0, paused = true),
            sample(4000, 0),
            sample(5000, 0),
        )
        val repaired = ActiveClock.repair(samples)
        // Two seconds of riding, two of standing, one more of riding: the pause
        // itself and both of its edges are excluded, which is what "active"
        // means.
        assertEquals(2_000L, repaired.last().activeMs)
    }

    @Test
    fun `a hole longer than a dropout is not counted as riding`() {
        // An auto-pause writes no samples at all, so it appears only as a hole —
        // and a receiver dropout appears exactly the same way. Neither is time
        // this app can attribute to riding.
        val samples = listOf(
            sample(0, 0),
            sample(1000, 1000),
            sample(1000 + Tuning.MAX_INTERPOLATION_GAP_MS + 5_000, 0),
            sample(2000 + Tuning.MAX_INTERPOLATION_GAP_MS + 5_000, 0),
        )
        val repaired = ActiveClock.repair(samples)
        assertEquals(2_000L, repaired.last().activeMs)
    }

    @Test
    fun `an empty or single-sample recording is not a special case anywhere else`() {
        assertTrue(ActiveClock.repair(emptyList()).isEmpty())
        assertEquals(0L, ActiveClock.repair(listOf(sample(500, 99_000))).single().activeMs)
    }
}
