package de.hecate.core.profile

/**
 * The one piece of series arithmetic two different measurements needed.
 *
 * [rollingMedian] was written for altitude — see [Elevation.despike], which is
 * still where the reasoning about window widths and what a median does to a hill
 * lives — and is here because heart rate wants exactly the same treatment for a
 * different reason: not because it has spikes in it, but because the screen
 * quantises it into four bands, and a reading sitting on a boundary crosses it
 * every few seconds.
 *
 * The two properties that make a median the right filter for both are worth
 * restating: it is the *identity* on a monotonic run, so a real climb and a real
 * effort pass through untouched, and it removes any excursion narrower than half
 * the window outright, which no amount of averaging does.
 */
object Series {

    /**
     * A rolling median over [values], ignoring NaN, [window] wide.
     *
     * At the ends the window is slid inward rather than truncated, so every
     * output is the median of a full window. A truncated window at index 0
     * contains only the samples that are settling and its median settles with
     * them; a full one reaching forward contains the good values that outvote
     * them.
     *
     * NaN marks "nothing was measured here" and stays NaN: a gap is not a value
     * to be filled in from its neighbours.
     */
    fun rollingMedian(values: DoubleArray, window: Int): DoubleArray {
        val n = values.size
        if (n < 3 || window < 3) return values
        val w = minOf(window, n)
        val out = DoubleArray(n)
        val scratch = DoubleArray(w)
        for (i in 0 until n) {
            if (values[i].isNaN()) { out[i] = Double.NaN; continue }
            // Centred where there is room, slid inward at the ends, always w wide.
            val from = (i - w / 2).coerceIn(0, n - w)
            var k = 0
            for (j in from until from + w) if (!values[j].isNaN()) scratch[k++] = values[j]
            if (k == 0) { out[i] = values[i]; continue }
            val slice = scratch.copyOf(k)
            slice.sort()
            out[i] = if (k % 2 == 1) slice[k / 2] else (slice[k / 2 - 1] + slice[k / 2]) / 2.0
        }
        return out
    }

    /**
     * Carry the last reading forward across holes shorter than [maxGapMs].
     *
     * A heart rate is not a per-second measurement that sometimes fails: it is a
     * quantity that is sampled when the sensor has something to say, and on a
     * watch recording that turns out to be about a third of the samples — the
     * rate rides along on whichever position sample shared its batch. Read as a
     * series with holes, that produced a chart of forty disconnected bars and a
     * track drawn mostly in the no-reading colour, both of which say "no data"
     * about a ride that was measured throughout.
     *
     * Holding is bounded, and the bound is the whole honesty of it: within
     * [maxGapMs] the previous reading is still a fair description of the effort,
     * and beyond it the strap really has stopped reporting and the gap is the
     * fact worth drawing. See [de.hecate.core.Tuning.HR_HOLD_MS].
     *
     * [tMs] is each value's own time, so the bound is in seconds of riding
     * rather than in samples — the two differ by an order of magnitude between a
     * 1 Hz recording and a batched one.
     */
    fun hold(values: DoubleArray, tMs: LongArray, maxGapMs: Long): DoubleArray {
        require(values.size == tMs.size) { "a value per time, ${values.size} vs ${tMs.size}" }
        val out = values.copyOf()
        var last = Double.NaN
        var lastAt = 0L
        for (i in values.indices) {
            if (!values[i].isNaN()) {
                last = values[i]
                lastAt = tMs[i]
            } else if (!last.isNaN() && tMs[i] - lastAt <= maxGapMs) {
                out[i] = last
            }
        }
        return out
    }

    /**
     * Thin a series down to at most [max] points for drawing, keeping its shape.
     *
     * An hour-long ride is 3600 samples and a phone chart is a few hundred
     * pixels wide, so most of them land on a pixel that is already painted.
     * Every bucket keeps its **highest and lowest** point rather than an
     * average, because the shape of a ridge — or of a sprint, or of the moment a
     * heart rate topped out — is the whole reason to look at the chart, and
     * averaging is exactly what flattens it. The two are emitted in the order
     * they were travelled, so the line never doubles back on itself.
     *
     * Generic over the point type, with [value] saying what is being plotted and
     * [order] saying which came first, because every chart in this app wants the
     * identical treatment and the surest way to make two charts disagree is to
     * give them two copies of this.
     */
    fun <T> thin(points: List<T>, max: Int, value: (T) -> Double, order: (T) -> Double): List<T> {
        if (points.size <= max || max < 4) return points
        val buckets = max / 2
        val per = points.size.toDouble() / buckets
        val out = ArrayList<T>(max)
        for (b in 0 until buckets) {
            val from = (b * per).toInt()
            val to = (((b + 1) * per).toInt()).coerceAtMost(points.size)
            if (from >= to) continue
            var lo = points[from]
            var hi = points[from]
            for (i in from until to) {
                val p = points[i]
                if (value(p) < value(lo)) lo = p
                if (value(p) > value(hi)) hi = p
            }
            if (order(lo) <= order(hi)) { out.add(lo); out.add(hi) } else { out.add(hi); out.add(lo) }
        }
        return out
    }
}
