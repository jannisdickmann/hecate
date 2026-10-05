package de.hecate.core.model

import de.hecate.core.Tuning

/**
 * The active-time clock a series of samples is read on, derived rather than
 * believed.
 *
 * [Sample.activeMs] is supposed to be the elapsed active duration at that
 * sample, and on a takeout import it is exactly that. On a recording from the
 * watch it is neither: Health Services reports the active duration as a
 * *checkpoint*, a value that was true at some instant, and it does not arrive
 * with every batch — so the recorder stamped hundreds of consecutive samples
 * with the same number, and after an auto-pause with a smaller one than before.
 * Measured on two real rides: four fifths of all sample intervals had no time in
 * them at all, and the clock ran backwards three times, once by 34 minutes.
 *
 * Nothing noticed for a long while, because everything that read it read it as a
 * *total* — and the total, written once in the footer at the end of a session,
 * came from a checkpoint that was current by then and is right. What reads it as
 * a series is newer: the speed chart (A.23), and the per-bin median times of a
 * route profile, which is how "am I faster than usual" is answered.
 *
 * So the clock is rebuilt here, from the one thing on a sample that is beyond
 * doubt: [Sample.tOffsetMs], which the watch takes from a monotonic clock and
 * which nothing can make jump. Time between two consecutive samples counts
 * unless it is one of the two things it cannot be attributed to:
 *
 * - **A paused sample at either end.** That is a stretch the user stopped, and
 *   it is what "active" excludes in the first place.
 * - **A hole longer than [Tuning.MAX_INTERPOLATION_GAP_MS].** An auto-pause
 *   writes no samples at all, so it appears only as such a hole; a receiver
 *   dropout appears the same way, and the app cannot tell them apart. Counting
 *   it would invent active seconds for a workout that may have been standing
 *   still; not counting it under-counts a genuine dropout by its own length,
 *   which is the smaller and the quieter error — and it is what every other part
 *   of this app already does with a gap that size.
 *
 * The rule is not a guess: run over 62 real sessions it reproduces the stored
 * per-sample clock of every takeout import *exactly*, to the millisecond, and
 * lands within a quarter of a percent of the footer's own total on both watch
 * recordings — where the last sample's own figure was out by a factor of four.
 */
object ActiveClock {

    /**
     * The same samples, with [Sample.activeMs] replaced by a clock that only
     * ever advances.
     *
     * Applied on the way out, every time, rather than at ingest: what the watch
     * said stays in the database and in every backup, exactly as raw altitude
     * does (A.20), so this can be changed later without a migration and without
     * anybody having to trust that it was right the first time.
     */
    fun repair(samples: List<Sample>): List<Sample> {
        if (samples.isEmpty()) return samples
        val out = ArrayList<Sample>(samples.size)
        var active = 0L
        out.add(samples[0].copy(activeMs = 0L))
        for (i in 1 until samples.size) {
            active += elapsed(samples[i - 1], samples[i])
            out.add(samples[i].copy(activeMs = active))
        }
        return out
    }

    /** How much active time lies between two consecutive samples, in ms. Public
     *  so the live matcher can run the very clock its route profiles were
     *  measured on, one sample at a time. */
    fun elapsed(previous: Sample, next: Sample): Long {
        if (previous.paused || next.paused) return 0L
        val dt = next.tOffsetMs - previous.tOffsetMs
        if (dt <= 0 || dt > Tuning.MAX_INTERPOLATION_GAP_MS) return 0L
        return dt
    }
}
