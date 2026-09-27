package de.hecate.core.model

/**
 * Putting a sensor's own series onto the times something else was measured at.
 *
 * [HeartRates] joins by nearest-within-tolerance, which is right for a rate: it
 * is a state of the body that either was or was not measured near a position,
 * and stretching one over a gap is a decision for the display to make later.
 *
 * Altitude is not like that, and this exists because it is not. The barometer on
 * a Pixel Watch 4 reports every 20 to 60 seconds, irregularly, while positions
 * arrive every second; a nearest-within-five-seconds join leaves six samples in
 * seven with no altitude, and a profile drawn from the seventh is a row of dots.
 * But the ground between two readings is not unknown — it is *between them*, and
 * a barometer that says 260 m and then 275 m a minute later was on a slope the
 * whole time. Interpolation states that, and it is the only way to get a profile
 * whose x-axis is distance rather than "wherever a reading happened to land".
 *
 * What it must not do is invent ground across a hole. Past [maxGapMs] the two
 * readings stop being two ends of one slope and become two facts with something
 * unrecorded between them, and the series returns NaN rather than a straight
 * line through it.
 */
object Readings {

    /**
     * [readings] evaluated at each time in [atMs], NaN where nothing is near
     * enough.
     *
     * Between two readings no more than [maxGapMs] apart, the value is linear in
     * time. Before the first and after the last, the nearest reading stands for
     * up to [maxGapMs] — a barometer does not stop being right the moment the
     * recording starts, and the alternative is losing the opening and closing
     * minute of every profile.
     *
     * [readings] must be sorted by time; [atMs] need not be, and a caller with
     * sorted times pays one pass rather than a search per entry.
     */
    fun interpolatedAt(readings: List<Reading>, atMs: LongArray, maxGapMs: Long): DoubleArray {
        val out = DoubleArray(atMs.size) { Double.NaN }
        if (readings.isEmpty()) return out
        if (readings.size == 1) {
            val only = readings[0]
            for (i in atMs.indices) {
                if (kotlin.math.abs(atMs[i] - only.tOffsetMs) <= maxGapMs) out[i] = only.value
            }
            return out
        }

        // One moving index over the readings rather than a binary search per
        // time: a long ride is thousands of each, and the caller's times are
        // sorted in every path that matters.
        var i = 0
        for (k in atMs.indices) {
            val t = atMs[k]
            if (t < readings[0].tOffsetMs) {
                if (readings[0].tOffsetMs - t <= maxGapMs) out[k] = readings[0].value
                continue
            }
            val last = readings[readings.size - 1]
            if (t >= last.tOffsetMs) {
                if (t - last.tOffsetMs <= maxGapMs) out[k] = last.value
                continue
            }
            // Advance to the bracket containing t. Reset when the caller's times
            // are not sorted, so the result never depends on their order.
            if (readings[i].tOffsetMs > t) i = 0
            while (i + 1 < readings.size && readings[i + 1].tOffsetMs <= t) i++
            val a = readings[i]
            val b = readings[i + 1]
            val span = b.tOffsetMs - a.tOffsetMs
            if (span <= 0L) {
                out[k] = a.value
                continue
            }
            if (span > maxGapMs) {
                // Too far apart to be one slope — but a sample sitting on, or
                // just after, one of the two readings is still described by it.
                // Falling straight to NaN here lost the reading at the very
                // instant it was taken, which is the one time it is certainly
                // right, and left the minute either side of a dropout blank when
                // the barometer had in fact reported.
                val nearer = if (t - a.tOffsetMs <= b.tOffsetMs - t) a else b
                if (kotlin.math.abs(t - nearer.tOffsetMs) <= maxGapMs) out[k] = nearer.value
                continue
            }
            val f = (t - a.tOffsetMs).toDouble() / span.toDouble()
            out[k] = a.value + (b.value - a.value) * f
        }
        return out
    }

    /** The reading nearest [tMs], or null when the nearest is further away than
     *  [toleranceMs]. For series where holding is not wanted. */
    fun nearestWithin(readings: List<Reading>, tMs: Long, toleranceMs: Long): Reading? =
        readings.minByOrNull { kotlin.math.abs(it.tOffsetMs - tMs) }
            ?.takeIf { kotlin.math.abs(it.tOffsetMs - tMs) <= toleranceMs }
}
