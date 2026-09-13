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
}
