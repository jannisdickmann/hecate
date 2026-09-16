package de.hecate.core.model

import de.hecate.core.Tuning

/**
 * Joining the heart-rate series to the positions.
 *
 * The two are measured by different sensors on different schedules and arrive
 * from Health Services in different batches, so they are recorded separately
 * ([HeartRate]) and brought together here — once, on the way in, because
 * everything downstream wants one row per position with the effort that was
 * being made at it.
 *
 * The join is by time and nothing else: each position takes the nearest reading
 * within [Tuning.HR_MERGE_TOLERANCE_MS]. Nearest rather than last, because a
 * reading a second after a position describes that position better than one four
 * seconds before it; bounded, because a reading a minute away describes a
 * different part of the ride, and stretching one over a gap is the display's
 * decision to make later and to be able to stop making (`Series.hold`).
 *
 * What this cannot do is invent the readings a previous version of the recorder
 * discarded. A session written before the watch kept the series carries whatever
 * single reading each delivery happened to end with, and that is all there is.
 */
object HeartRates {

    /**
     * [samples] with their heart rates taken from [rates] where one is near
     * enough in time.
     *
     * A sample keeps its own rate where no reading is close: an import has no
     * separate series at all and must come through unchanged, and so must a
     * recording made before the watch wrote one.
     */
    fun merge(
        samples: List<Sample>,
        rates: List<HeartRate>,
        toleranceMs: Long = Tuning.HR_MERGE_TOLERANCE_MS,
    ): List<Sample> {
        if (rates.isEmpty() || samples.isEmpty()) return samples
        val sorted = rates.sortedBy { it.tOffsetMs }

        val out = ArrayList<Sample>(samples.size)
        // Both lists are in time order, so one pass with a moving index rather
        // than a binary search per sample: a long ride is thousands of each.
        var i = 0
        for (s in samples) {
            while (i + 1 < sorted.size && sorted[i + 1].tOffsetMs <= s.tOffsetMs) i++
            val before = sorted[i].takeIf { it.tOffsetMs <= s.tOffsetMs }
            val after = sorted.getOrNull(if (before == null) i else i + 1)
            val nearest = listOfNotNull(before, after)
                .minByOrNull { kotlin.math.abs(it.tOffsetMs - s.tOffsetMs) }
            val within = nearest?.takeIf { kotlin.math.abs(it.tOffsetMs - s.tOffsetMs) <= toleranceMs }
            out.add(if (within == null) s else s.copy(hrBpm = within.bpm))
        }
        return out
    }
}
