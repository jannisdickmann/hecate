package de.hecate.core.cluster

import de.hecate.core.Tuning
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.geo.Polyline
import de.hecate.core.geo.Track
import de.hecate.core.geo.cellKey
import kotlin.math.floor

/**
 * The symmetric track-similarity metric (§5.7).
 *
 * `coverage(A,B) = min(fracWithin(A→B), fracWithin(B→A))` where `fracWithin` is
 * the fraction of A's evenly-spaced points that lie within [Tuning.CLUSTER_TOL_M]
 * of B's polyline. Because both tracks are resampled to a fixed spacing, "fraction
 * of points" is a fraction of *length*: a 5 km run that shares 4 km with a 10 km
 * run scores 0.8 one way and 0.4 the other, and the min correctly says they are
 * not the same route.
 *
 * Taking the minimum is what makes a shortcut a separate route rather than a
 * member of the long version (§8.1).
 */
object CoverageMetric {

    /**
     * Fraction of [a]'s points within [tolM] of [b].
     *
     * Uses a coarse bucket index over [b] so the inner loop stays near-linear;
     * a naive O(n·m) is 1.4 M distance evaluations for two hour-long tracks, which
     * would make re-clustering from scratch on every ingest genuinely slow.
     */
    fun fracWithin(a: List<LatLon>, b: Polyline, tolM: Double = Tuning.CLUSTER_TOL_M): Double {
        if (a.isEmpty() || b.size < 2) return 0.0
        val idx = SegmentIndex(b, tolM)
        var hits = 0
        for (p in a) if (idx.within(p, tolM)) hits++
        return hits.toDouble() / a.size
    }

    /** The symmetric coverage of two tracks, in [0,1]. */
    fun coverage(a: Track, b: Track, tolM: Double = Tuning.CLUSTER_TOL_M): Double =
        minOf(fracWithin(a.points, b.polyline, tolM), fracWithin(b.points, a.polyline, tolM))

    /**
     * Whether the two tracks traverse their shared geometry the same way round.
     *
     * Projects A's points onto B in order and counts how often the projection
     * advances. Two runs of the same street in opposite directions cover each
     * other perfectly, so without this test the reverse of a route would merge
     * into it — which US-3 explicitly forbids.
     */
    fun sameDirection(a: Track, b: Track, tolM: Double = Tuning.CLUSTER_TOL_M): Boolean {
        val bp = b.polyline
        if (bp.size < 2 || a.points.size < 3) return true
        // Through the same index [fracWithin] uses, and for the same reason. This
        // used to call Polyline.projectAll once per point of A, which scans every
        // segment of B: O(n·m), and its own KDoc says so. One pair of hour-long
        // rides is then 3 M segment projections, and re-clustering is every pair —
        // 65 imported sessions took over half an hour of CPU on an emulator and
        // had not finished, so the routes never appeared at all.
        val idx = SegmentIndex(bp, tolM)
        var prevPos = Double.NaN
        var forward = 0
        var backward = 0
        for (p in a.points) {
            val hit = idx.project(p, tolM)
            if (hit == null || hit.distanceM > tolM) { prevPos = Double.NaN; continue }
            if (!prevPos.isNaN()) {
                val d = hit.positionM - prevPos
                if (d > 0.0) forward++ else if (d < 0.0) backward++
            }
            prevPos = hit.positionM
        }
        val total = forward + backward
        if (total == 0) return true
        return forward.toDouble() / total >= Tuning.DIRECTION_AGREE_MIN
    }

    /**
     * Clustering distance: `1 - coverage`, except that tracks of different
     * exercise types or opposite directions are infinitely far apart and can
     * never be merged.
     */
    fun distance(a: Track, b: Track, tolM: Double = Tuning.CLUSTER_TOL_M): Double {
        if (a.exerciseType != b.exerciseType) return 1.0
        val cov = coverage(a, b, tolM)
        if (cov <= 0.0) return 1.0
        if (!sameDirection(a, b, tolM) || !sameDirection(b, a, tolM)) return 1.0
        return 1.0 - cov
    }

    /**
     * A bucket grid over a polyline's vertices, sized so that a point within
     * [tolM] of the polyline is guaranteed to find a vertex in the 3x3
     * neighbourhood of its own bucket. Vertices are 10 m apart after resampling,
     * so testing vertices instead of segments costs at most 5 m of tolerance —
     * absorbed by choosing CLUSTER_TOL_M generously.
     */
    private class SegmentIndex(val poly: Polyline, tolM: Double) {
        private val cell = maxOf(tolM, Tuning.RESAMPLE_M)
        private val origin = LatLon(poly.lat.firstOrNull() ?: 0.0, poly.lon.firstOrNull() ?: 0.0)
        private val buckets = HashMap<Long, MutableList<Int>>()

        init {
            for (i in 0 until poly.size) {
                val k = keyOf(poly.lat[i], poly.lon[i])
                buckets.getOrPut(k) { ArrayList(4) }.add(i)
            }
        }

        private fun keyOf(lat: Double, lon: Double): Long {
            val l = Geo.toLocal(origin, LatLon(lat, lon))
            return cellKey(floor(l[0] / cell).toLong(), floor(l[1] / cell).toLong())
        }

        fun within(p: LatLon, tolM: Double): Boolean {
            forEachNearbyVertex(p) { i ->
                if (Geo.haversine(p.lat, p.lon, poly.lat[i], poly.lon[i]) <= tolM) return true
            }
            return false
        }

        /**
         * Where [p] projects onto the polyline, or null when nothing is near
         * enough to matter.
         *
         * Only the segments incident to a nearby vertex are projected onto, which
         * is what makes this near-constant instead of a scan of the whole
         * polyline. It shares [within]'s candidate set exactly — the same 3x3
         * neighbourhood, so the two halves of [distance] cannot disagree about
         * which points of A lie on B — and therefore inherits the same slack: a
         * point that is within [tolM] of a segment but farther than that from
         * either of its endpoints can be missed, by at most half a resampling
         * step. For [sameDirection] that costs a borderline point or two out of
         * several hundred in a ratio, which is well inside what
         * [Tuning.DIRECTION_AGREE_MIN] is set to tolerate.
         */
        fun project(p: LatLon, tolM: Double): Polyline.Hit? {
            var bestD = Double.MAX_VALUE
            var bestPos = 0.0
            var bestSeg = -1
            forEachNearbyVertex(p) { v ->
                // A vertex is the end of the segment before it and the start of
                // the one after; both are candidates.
                for (seg in maxOf(0, v - 1)..minOf(v, poly.size - 2)) {
                    val pr = Geo.projectToSegment(p, poly.point(seg), poly.point(seg + 1))
                    if (pr.distanceM < bestD) {
                        bestD = pr.distanceM
                        bestPos = poly.cum[seg] + pr.t * (poly.cum[seg + 1] - poly.cum[seg])
                        bestSeg = seg
                    }
                }
            }
            return if (bestSeg < 0) null else Polyline.Hit(bestPos, bestD, bestSeg)
        }

        /** The vertices in the 3x3 bucket neighbourhood of [p]. */
        private inline fun forEachNearbyVertex(p: LatLon, body: (Int) -> Unit) {
            val l = Geo.toLocal(origin, p)
            val cx = floor(l[0] / cell).toLong()
            val cy = floor(l[1] / cell).toLong()
            for (dy in -1..1) for (dx in -1..1) {
                val list = buckets[cellKey(cx + dx, cy + dy)] ?: continue
                for (i in list) body(i)
            }
        }
    }
}
