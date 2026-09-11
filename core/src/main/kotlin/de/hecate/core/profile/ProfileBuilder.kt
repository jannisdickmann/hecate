package de.hecate.core.profile

import de.hecate.core.Tuning
import de.hecate.core.geo.LatLon
import de.hecate.core.geo.Polyline
import de.hecate.core.geo.Track
import de.hecate.core.model.RouteProfile
import de.hecate.core.model.Sample

/**
 * Builds the per-position statistics a route needs for ahead/behind and ETA
 * (§5.7): for each 10 m bin along the route geometry, the median cumulative
 * *active* time at which members reached that bin, plus p25/p75 and median heart
 * rate.
 *
 * Members are projected onto the route monotonically — a member's position along
 * the route may only advance — so that an out-and-back or a lap does not fold its
 * second half onto its first.
 */
object ProfileBuilder {

    /** One member's arrival time at each bin; NaN where the member never got there. */
    class MemberCurve(val timesS: DoubleArray, val hr: DoubleArray)

    /**
     * @param geometry the route polyline (already resampled)
     * @param members tracks belonging to the route
     * @param hrBySession optional per-session heart-rate samples, used to fill the
     *        HR curve. Tracks carry time but not HR, so the raw samples are passed
     *        alongside.
     */
    fun build(
        routeId: Long,
        geometry: Polyline,
        members: List<Track>,
        hrBySession: Map<String, List<Sample>> = emptyMap(),
        binM: Double = Tuning.RESAMPLE_M,
    ): RouteProfile {
        val bins = (geometry.lengthM / binM).toInt() + 1
        if (bins <= 1 || members.isEmpty()) {
            return RouteProfile(routeId, binM, emptyList())
        }

        val curves = members.map { memberCurve(geometry, it, bins, binM, hrBySession[it.sessionId]) }

        val median = DoubleArray(bins)
        val p25 = DoubleArray(bins)
        val p75 = DoubleArray(bins)
        val hr = arrayOfNulls<Double>(bins)

        val scratch = DoubleArray(curves.size)
        for (b in 0 until bins) {
            var k = 0
            for (c in curves) {
                val v = c.timesS[b]
                if (!v.isNaN()) scratch[k++] = v
            }
            if (k == 0) {
                median[b] = Double.NaN; p25[b] = Double.NaN; p75[b] = Double.NaN
            } else {
                val slice = scratch.copyOf(k)
                slice.sort()
                median[b] = quantile(slice, 0.50)
                p25[b] = quantile(slice, 0.25)
                p75[b] = quantile(slice, 0.75)
            }
            var hk = 0
            for (c in curves) {
                val v = c.hr[b]
                if (!v.isNaN()) scratch[hk++] = v
            }
            hr[b] = if (hk == 0) null else scratch.copyOf(hk).also { it.sort() }.let { quantile(it, 0.5) }
        }

        // Bins nobody reached (start-of-route offsets, dropouts) are filled by
        // interpolating between the neighbours that were reached, then forced
        // monotone: cumulative time cannot decrease along a route.
        fillGaps(median)
        fillGaps(p25)
        fillGaps(p75)
        makeMonotone(median)
        makeMonotone(p25)
        makeMonotone(p75)

        return RouteProfile(
            routeId = routeId,
            binM = binM,
            medianCumTimeS = median.toList(),
            p25S = p25.toList(),
            p75S = p75.toList(),
            medianHr = hr.toList(),
        )
    }

    /**
     * Project one member onto the route and record, for each bin it reached, the
     * active time at which it got there.
     */
    internal fun memberCurve(
        geometry: Polyline,
        track: Track,
        bins: Int,
        binM: Double,
        hrSamples: List<Sample>?,
    ): MemberCurve {
        val times = DoubleArray(bins) { Double.NaN }
        val hrs = DoubleArray(bins) { Double.NaN }
        if (track.size < 2) return MemberCurve(times, hrs)

        val hrLookup = hrSamples?.filter { it.hrBpm != null }?.sortedBy { it.activeMs }

        var lastPos = -1.0
        var runIdx = 0
        for (i in track.points.indices) {
            // A new run means the member was somewhere unobserved in between;
            // allow the projection to jump forward by searching from lastPos on.
            if (runIdx + 1 < track.runStarts.size && i == track.runStarts[runIdx + 1]) {
                runIdx++
                lastPos = -1.0
            }
            val p: LatLon = track.points[i]
            val hit = if (lastPos < 0.0) {
                geometry.projectAll(p)
            } else {
                geometry.projectWindow(p, lastPos, lastPos + Tuning.SEARCH_WINDOW_M)
            }
            if (hit.distanceM > Tuning.CLUSTER_TOL_M * 2) continue
            val pos = if (lastPos < 0.0) hit.positionM else maxOf(hit.positionM, lastPos)
            lastPos = pos

            val b = (pos / binM).toInt().coerceIn(0, bins - 1)
            val t = track.timeS[i]
            // First arrival wins: a lap that passes a bin twice is timed on the
            // first pass, matching what a runner means by "at kilometre 3".
            if (times[b].isNaN()) {
                times[b] = t
                hrLookup?.let { hrs[b] = nearestHr(it, (t * 1000).toLong()) }
            }
        }
        return MemberCurve(times, hrs)
    }

    private fun nearestHr(samples: List<Sample>, activeMs: Long): Double {
        if (samples.isEmpty()) return Double.NaN
        var lo = 0
        var hi = samples.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (samples[mid].activeMs < activeMs) lo = mid + 1 else hi = mid
        }
        val a = samples[lo]
        val b = samples[(lo - 1).coerceAtLeast(0)]
        val pick = if (kotlin.math.abs(a.activeMs - activeMs) <= kotlin.math.abs(b.activeMs - activeMs)) a else b
        return pick.hrBpm?.toDouble() ?: Double.NaN
    }

    /** Linear interpolation over NaN runs; edges are extended flat. */
    internal fun fillGaps(v: DoubleArray) {
        val n = v.size
        var first = -1
        for (i in 0 until n) if (!v[i].isNaN()) { first = i; break }
        if (first < 0) return
        for (i in 0 until first) v[i] = v[first]
        var last = first
        for (i in first + 1 until n) {
            if (v[i].isNaN()) continue
            if (i > last + 1) {
                val span = i - last
                for (k in 1 until span) v[last + k] = v[last] + (v[i] - v[last]) * k / span
            }
            last = i
        }
        for (i in last + 1 until n) v[i] = v[last]
    }

    internal fun makeMonotone(v: DoubleArray) {
        for (i in 1 until v.size) if (v[i] < v[i - 1]) v[i] = v[i - 1]
    }

    /** Linear-interpolated quantile of a sorted array. */
    internal fun quantile(sorted: DoubleArray, q: Double): Double {
        if (sorted.isEmpty()) return Double.NaN
        if (sorted.size == 1) return sorted[0]
        val x = q * (sorted.size - 1)
        val i = x.toInt()
        if (i >= sorted.size - 1) return sorted.last()
        val f = x - i
        return sorted[i] * (1 - f) + sorted[i + 1] * f
    }
}
