package de.hecate.core.profile

import de.hecate.core.geo.Geo
import de.hecate.core.model.Altitudes
import de.hecate.core.model.Intensity
import de.hecate.core.model.Sample

/**
 * The arithmetic behind the recording screen's analysis cards: splits, time in
 * each effort band, and the index that ties a moment to a place.
 *
 * Every function here reads samples that have already been through
 * [de.hecate.core.model.ActiveClock.repair], with paused ones dropped — the
 * same series the charts are drawn from, so a split and the chart above it
 * agree about when a kilometre ended.
 */
object Analysis {

    /** One stretch of fixed length. [partial] for the remainder at the end. */
    data class Split(
        val index: Int,
        val fromM: Double,
        val toM: Double,
        val durationMs: Long,
        val avgHrBpm: Double?,
        /** Altitude at the end minus altitude at the start, or null where
         *  either end has none. Net rather than climbed, because a kilometre's
         *  climb from a barometer is mostly its noise. */
        val netAltM: Double?,
        val partial: Boolean,
    ) {
        val distanceM: Double get() = toM - fromM
        val kmh: Double? get() = if (durationMs > 0) Geo.kmh(distanceM, durationMs) else null
    }

    /** One kilometre on foot, five on wheels or skis once a recording is long
     *  enough that kilometres would be a wall of rows. */
    fun splitLengthFor(totalM: Double): Double = if (totalM > LONG_RECORDING_M) 5_000.0 else 1_000.0

    private const val LONG_RECORDING_M = 25_000.0

    /** A remainder shorter than this fraction of a split is not shown: forty
     *  metres to the car is not a split. */
    private const val MIN_PARTIAL_FRACTION = 0.1

    fun splits(samples: List<Sample>, lengthM: Double = splitLengthFor(distanceOf(samples))): List<Split> {
        if (samples.size < 2 || lengthM <= 0) return emptyList()
        val cum = cumulative(samples)
        val out = ArrayList<Split>()
        var fromM = 0.0
        var fromMs = samples[0].activeMs
        var fromAlt = altAt(samples, 0)
        var hrSum = 0.0
        var hrN = 0
        var next = lengthM
        for (i in 1 until samples.size) {
            samples[i].hrBpm?.takeIf { it > 0 }?.let { hrSum += it; hrN++ }
            while (cum[i] >= next) {
                // Interpolated to the metre the boundary was crossed at, so a
                // split does not inherit a whole sample interval of noise.
                val f = if (cum[i] > cum[i - 1]) (next - cum[i - 1]) / (cum[i] - cum[i - 1]) else 1.0
                val atMs = samples[i - 1].activeMs + ((samples[i].activeMs - samples[i - 1].activeMs) * f).toLong()
                val alt = altAt(samples, i)
                out += Split(
                    index = out.size,
                    fromM = fromM,
                    toM = next,
                    durationMs = atMs - fromMs,
                    avgHrBpm = if (hrN > 0) hrSum / hrN else null,
                    netAltM = if (alt != null && fromAlt != null) alt - fromAlt else null,
                    partial = false,
                )
                fromM = next; fromMs = atMs; fromAlt = alt
                hrSum = 0.0; hrN = 0
                next += lengthM
            }
        }
        val total = cum.last()
        if (total - fromM >= lengthM * MIN_PARTIAL_FRACTION) {
            val alt = altAt(samples, samples.size - 1)
            out += Split(
                index = out.size,
                fromM = fromM,
                toM = total,
                durationMs = samples.last().activeMs - fromMs,
                avgHrBpm = if (hrN > 0) hrSum / hrN else null,
                netAltM = if (alt != null && fromAlt != null) alt - fromAlt else null,
                partial = true,
            )
        }
        return out
    }

    /**
     * How long was spent in each effort band, by active time.
     *
     * A segment counts toward the band of the mean of its two ends — the same
     * rule the drawings colour by — and a segment longer than [maxGapMs] is a
     * hole, not a minute at whatever the rate was either side of it. Empty when
     * the recording has no heart rate at all.
     */
    fun timeInZones(samples: List<Sample>, peakHrBpm: Int, maxGapMs: Long = 30_000): Map<Intensity, Long> {
        val out = LinkedHashMap<Intensity, Long>()
        for (i in 1 until samples.size) {
            val dt = samples[i].activeMs - samples[i - 1].activeMs
            if (dt <= 0 || dt > maxGapMs) continue
            val a = samples[i - 1].hrBpm?.takeIf { it > 0 }?.toDouble()
            val b = samples[i].hrBpm?.takeIf { it > 0 }?.toDouble()
            val hr = if (a != null && b != null) (a + b) / 2 else a ?: b
            val band = Intensity.of(hr, peakHrBpm) ?: continue
            out[band] = (out[band] ?: 0L) + dt
        }
        return if (out.isEmpty()) emptyMap() else Intensity.entries.associateWith { out[it] ?: 0L }
    }

    /** One moment of a recording: when, how far, where, and what was measured. */
    data class Moment(
        val activeMs: Long,
        val tOffsetMs: Long,
        val distanceM: Double,
        val lat: Double,
        val lon: Double,
        val altM: Double?,
        val hrBpm: Double?,
    )

    /**
     * The recording as a list of moments, thinned to at most [max], for a
     * cursor to look things up in. Both [Moment.activeMs] and
     * [Moment.distanceM] are non-decreasing, so either can be searched.
     */
    fun moments(samples: List<Sample>, max: Int = 1_500): List<Moment> {
        if (samples.isEmpty()) return emptyList()
        val cum = cumulative(samples)
        val step = ((samples.size + max - 1) / max).coerceAtLeast(1)
        val out = ArrayList<Moment>(samples.size / step + 2)
        var i = 0
        while (i < samples.size) {
            out += moment(samples, cum, i)
            i += step
        }
        if ((samples.size - 1) % step != 0) out += moment(samples, cum, samples.size - 1)
        return out
    }

    private fun moment(samples: List<Sample>, cum: DoubleArray, i: Int) = Moment(
        activeMs = samples[i].activeMs,
        tOffsetMs = samples[i].tOffsetMs,
        distanceM = cum[i],
        lat = samples[i].lat,
        lon = samples[i].lon,
        altM = altAt(samples, i),
        hrBpm = samples[i].hrBpm?.takeIf { it > 0 }?.toDouble(),
    )

    /** The moment closest to [activeMs]; null for an empty list. */
    fun atTime(moments: List<Moment>, activeMs: Long): Moment? = nearest(moments) { it.activeMs.toDouble() - activeMs }

    /** The moment closest to [distanceM]. */
    fun atDistance(moments: List<Moment>, distanceM: Double): Moment? = nearest(moments) { it.distanceM - distanceM }

    private inline fun nearest(moments: List<Moment>, crossinline diff: (Moment) -> Double): Moment? {
        if (moments.isEmpty()) return null
        var lo = 0
        var hi = moments.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (diff(moments[mid]) < 0) lo = mid + 1 else hi = mid
        }
        val prev = moments.getOrNull(lo - 1)
        return if (prev != null && kotlin.math.abs(diff(prev)) <= kotlin.math.abs(diff(moments[lo]))) prev else moments[lo]
    }

    private fun distanceOf(samples: List<Sample>): Double = if (samples.isEmpty()) 0.0 else cumulative(samples).last()

    private fun cumulative(samples: List<Sample>): DoubleArray {
        val cum = DoubleArray(samples.size)
        for (i in 1 until samples.size) {
            cum[i] = cum[i - 1] + Geo.haversine(samples[i - 1].lat, samples[i - 1].lon, samples[i].lat, samples[i].lon)
        }
        return cum
    }

    private fun altAt(samples: List<Sample>, i: Int): Double? = samples[i].altM?.takeIf { Altitudes.isPlausible(it) }
}
