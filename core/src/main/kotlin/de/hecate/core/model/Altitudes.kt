package de.hecate.core.model

import de.hecate.core.Tuning
import de.hecate.core.profile.Series

/**
 * Joining the barometer's series to the positions.
 *
 * The counterpart of [HeartRates], done once on the way in for the same reason —
 * everything downstream wants one row per position with the height it was at —
 * and by a different rule, because the two sensors are not alike.
 *
 * A heart rate is joined by nearest-within-tolerance and left absent when nothing
 * is near: a rate is a state of the body that either was or was not measured
 * there, and a gap in the middle is honest. Altitude cannot be treated that way
 * and still produce a profile. Measured on a Pixel Watch 4, ABSOLUTE_ELEVATION
 * arrives about once every half-minute — 57 s, 27 s and 2 s apart in one
 * ninety-second stretch — while positions arrive every second, so the same rule
 * fills roughly one sample in seven and a chart drawn from those is a row of
 * dots against a distance axis.
 *
 * The difference that justifies the difference: the ground between two readings
 * is not unknown. A barometer that says 260 m and 275 m a minute later was on a
 * slope the whole time, and saying so is the only way to get an altitude against
 * every position. So the series is interpolated, bounded by
 * [Tuning.ALTITUDE_INTERPOLATION_GAP_MS], past which two readings stop being the
 * ends of one slope and the samples between them are left without an altitude.
 *
 * Spikes are removed from the *readings* before any of that, over
 * [Tuning.ALTITUDE_READING_MEDIAN_WINDOW] of them. It has to happen here rather
 * than downstream: a single bad reading interpolates into a ramp tens of samples
 * long, and a median over the samples would then be smoothing something that is
 * already smooth and could no longer see the impulse that caused it.
 */
object Altitudes {

    /**
     * [samples] with their altitudes taken from [readings].
     *
     * Samples come through unchanged when there is no series — every import
     * carries an altitude per position instead, and so does every recording made
     * before the watch asked Health Services for the data type. Where there is
     * one it replaces the column outright rather than filling gaps in it: the
     * series is every reading the sensor gave, and a watch recording's column
     * holds nothing else worth keeping.
     */
    fun merge(
        samples: List<Sample>,
        readings: List<Reading>,
        maxGapMs: Long = Tuning.ALTITUDE_INTERPOLATION_GAP_MS,
    ): List<Sample> {
        if (readings.isEmpty() || samples.isEmpty()) return samples

        val plausible = readings
            .filter { it.value.isFinite() && it.value >= Tuning.MIN_ALTITUDE_M && it.value <= Tuning.MAX_ALTITUDE_M }
            .sortedBy { it.tOffsetMs }
        if (plausible.isEmpty()) return samples

        // A median needs more readings than its window has room for. The window
        // slides inward at the ends rather than truncating — which is what makes
        // it remove the altimeter settling at the start — so a series no longer
        // than the window has the *same* window everywhere, and every reading
        // comes out as the median of the whole series: a two-minute walk would
        // get a dead-flat profile at its own average height. Below that length
        // there is nothing a median can tell apart, so nothing is done.
        val cleaned = if (plausible.size > Tuning.ALTITUDE_READING_MEDIAN_WINDOW) {
            val despiked = Series.rollingMedian(
                DoubleArray(plausible.size) { plausible[it].value },
                Tuning.ALTITUDE_READING_MEDIAN_WINDOW,
            )
            plausible.mapIndexed { i, r -> r.copy(value = despiked[i]) }
        } else {
            plausible
        }

        val at = Readings.interpolatedAt(
            cleaned,
            LongArray(samples.size) { samples[it].tOffsetMs },
            maxGapMs,
        )
        return samples.mapIndexed { i, s ->
            val alt = at[i]
            if (alt.isNaN()) s.copy(altM = null) else s.copy(altM = alt)
        }
    }
}
