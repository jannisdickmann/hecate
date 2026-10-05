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
    class MemberCurve(val timesS: DoubleArray, val hr: DoubleArray, val alt: DoubleArray)

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
        val alt = arrayOfNulls<Double>(bins)

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

            // The same median over members, for the ground rather than the effort.
            // Deliberately not smoothed or made monotone afterwards as the times
            // are: a route climbs and descends, and forcing it either way would
            // draw a hill that is not there.
            var ak = 0
            for (c in curves) {
                val v = c.alt[b]
                if (!v.isNaN()) scratch[ak++] = v
            }
            alt[b] = if (ak == 0) null else scratch.copyOf(ak).also { it.sort() }.let { quantile(it, 0.5) }
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
            medianAltM = alt.toList(),
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
        val alts = DoubleArray(bins) { Double.NaN }
        if (track.size < 2) return MemberCurve(times, hrs, alts)

        val hrLookup = hrSamples?.filter { it.hrBpm != null }?.sortedBy { it.activeMs }
        // Once per member, before any bin reads off it. The altimeter's settling
        // is systematic — every recording of a route starts in the same place and
        // mis-reads it the same way — so the median *across* members below cannot
        // remove it. It has to come out of each member first.
        val altitude = Elevation.despike(track.altM)

        var lastPos = -1.0
        var lost = true
        var runIdx = 0
        for (i in track.points.indices) {
            // A new run means the member was somewhere unobserved in between, so
            // the projection may jump — but on ground the route covers twice
            // (a lollipop's stem, a loop's start and finish) to the pass that
            // continues from where it was, not to whichever is nearer. Taking the
            // nearest put members of "Abendrunde" on the way home a few hundred
            // metres in, and left the profile's median flat for most of a
            // kilometre.
            if (runIdx + 1 < track.runStarts.size && i == track.runStarts[runIdx + 1]) {
                runIdx++
                lost = true
            }
            val p: LatLon = track.points[i]
            var hit = if (lost) {
                geometry.localise(p, lastPos.coerceAtLeast(0.0), Tuning.LOCALISE_TIE_M)
            } else {
                geometry.projectWindow(p, lastPos, lastPos + Tuning.SEARCH_WINDOW_M)
            }
            // Off the route within reach of the window: either the member is on
            // a stretch of its own, or it has come back further along than the
            // window looks. Looked for over the rest of the route, forwards
            // only. Without this a member that took a variant for a few hundred
            // metres was never found again until the next GPS gap — one commute
            // was timed for its first 830 m of 17 km, and every member lost
            // like that left the median over the bins after it to a different
            // set of riders, which is what put 40–100 s steps into the last few
            // hundred metres of every commute route.
            if (!lost && hit.distanceM > Tuning.CLUSTER_TOL_M * 2) {
                val rejoined = geometry.localise(p, lastPos, Tuning.LOCALISE_TIE_M, minM = lastPos)
                if (rejoined.distanceM <= Tuning.CLUSTER_TOL_M * 2) hit = rejoined
            }
            if (hit.distanceM > Tuning.CLUSTER_TOL_M * 2) continue
            val pos = if (lost) hit.positionM else maxOf(hit.positionM, lastPos)
            lost = false
            lastPos = pos

            val b = (pos / binM).toInt().coerceIn(0, bins - 1)
            val t = track.timeS[i]
            // First arrival wins: a lap that passes a bin twice is timed on the
            // first pass, matching what a runner means by "at kilometre 3".
            if (times[b].isNaN()) {
                times[b] = t
                hrLookup?.let { hrs[b] = nearestHr(it, (t * 1000).toLong()) }
                alts[b] = altitude.getOrElse(i) { Double.NaN }
            }
        }
        // Between two bins this member did reach, it passed every bin in between,
        // at a time between the two. Left as gaps, those bins were medians over
        // a different set of members than their neighbours — a ride at 6 m/s
        // skips a 10 m bin every few seconds, and a member off the geometry for a
        // stretch skips hundreds — so the median jumped between one subset of
        // riders and another, and [makeMonotone] then held every upward jump as a
        // step: the profile of "Arbeit zurück" rose 89 s within one bin, and
        // ahead/behind with it.
        fillInterior(times)
        extendEnds(times, (Tuning.PROFILE_END_EXTEND_M / binM).toInt())
        return MemberCurve(times, hrs, alts)
    }

    /**
     * Carry a member that stopped just short of the route's end — or started
     * just past its start — the rest of the way, at its own pace.
     *
     * The route's ends are wherever its reference ride happened to start and
     * stop recording, and every other member starts and stops a few metres
     * either side. Left alone, the last bins were reached only by the members
     * that stopped furthest along, and on one commute that was the two slowest
     * of seven: the median rose 157 s over the final 10 m. The first bins had
     * the same problem the other way round.
     *
     * At the end, the missing bins are extrapolated at the pace of the member's
     * last [maxBins] reached. At the start, the whole curve moves later by the
     * time the member would have taken over the bins it did not record, so time
     * is measured from the route's start for everyone — the profile's version
     * of measuring a live workout from where the user joined. Only within
     * [maxBins] of either end: a member that is missing more than that did not
     * ride that part, and says nothing about it.
     */
    internal fun extendEnds(v: DoubleArray, maxBins: Int) {
        val first = v.indexOfFirst { !it.isNaN() }
        val last = v.indexOfLast { !it.isNaN() }
        if (first < 0 || last - first < 2) return

        val tailMissing = v.size - 1 - last
        if (tailMissing in 1..maxBins) {
            val from = maxOf(first, last - maxBins)
            val pace = (v[last] - v[from]) / (last - from)
            if (pace > 0.0) for (k in 1..tailMissing) v[last + k] = v[last] + pace * k
        }

        if (first in 1..maxBins) {
            val to = minOf(last, first + maxBins)
            val pace = (v[to] - v[first]) / (to - first)
            if (pace > 0.0) {
                val shift = pace * first - v[first]
                for (i in first..v.lastIndex) if (!v[i].isNaN()) v[i] += shift
                for (i in 0 until first) v[i] = pace * i
            }
        }
    }

    /** Linear interpolation over NaN runs between known values; the ends are left
     *  alone, since a member that never got there says nothing about them. */
    internal fun fillInterior(v: DoubleArray) {
        var last = -1
        for (i in v.indices) {
            if (v[i].isNaN()) continue
            if (last >= 0 && i > last + 1) {
                val span = i - last
                for (k in 1 until span) v[last + k] = v[last] + (v[i] - v[last]) * k / span
            }
            last = i
        }
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
