package de.hecate.core.geo

import de.hecate.core.Tuning
import kotlin.math.max
import kotlin.math.min

/**
 * An immutable open polyline with precomputed cumulative distances.
 *
 * Stored as parallel primitive arrays rather than a list of objects: the matcher
 * touches thousands of vertices per sample and NFR-3 budgets 5 ms for the whole
 * update across 50 routes.
 */
class Polyline(
    val lat: DoubleArray,
    val lon: DoubleArray,
    val cum: DoubleArray,
) {
    init {
        require(lat.size == lon.size && lat.size == cum.size) { "ragged polyline arrays" }
    }

    val size: Int get() = lat.size
    val lengthM: Double get() = if (size == 0) 0.0 else cum[size - 1]

    val minLat: Double = lat.minOrNull() ?: 0.0
    val maxLat: Double = lat.maxOrNull() ?: 0.0
    val minLon: Double = lon.minOrNull() ?: 0.0
    val maxLon: Double = lon.maxOrNull() ?: 0.0

    fun point(i: Int): LatLon = LatLon(lat[i], lon[i])

    /** Where a projection landed. [positionM] is measured along the polyline. */
    data class Hit(val positionM: Double, val distanceM: Double, val segment: Int)

    /**
     * Nearest point on the whole polyline. O(n); used at candidate admission and
     * by the clustering metric, never in the matcher's inner loop.
     */
    fun projectAll(p: LatLon): Hit = projectRange(p, 0, size - 2)

    /**
     * Nearest point within [fromM, toM] along the polyline, i.e. the windowed
     * projection of §5.6 step 2. The window is what keeps loops and out-and-backs
     * unambiguous: position advances monotonically instead of snapping to whatever
     * piece of geometry happens to be closest in space.
     */
    fun projectWindow(p: LatLon, fromM: Double, toM: Double): Hit {
        if (size < 2) return Hit(0.0, Double.MAX_VALUE, 0)
        val lo = segmentAt(max(0.0, fromM))
        val hi = segmentAt(min(lengthM, max(fromM, toM)))
        return projectRange(p, lo, hi)
    }

    /**
     * Find [p] on the route with nothing recent to go on — the first fix of a
     * workout, the first after a dropout — on geometry that may pass the same
     * spot more than once.
     *
     * The nearest point is the wrong question there. A lollipop walks its stem
     * out and back, so every point on it has two positions a few metres apart,
     * one early and one late; a closed loop puts its finish beside its start.
     * Which of the two is nearer is decided by GNSS noise, and choosing the late
     * one is not a small error: the position then runs backwards while the user
     * walks forwards, the route looks abandoned, and the matcher drops a route
     * the user is standing on.
     *
     * So the polyline is read as stretches — maximal runs of segments that pass
     * within [toleranceM] of the nearest distance — each one a distinct place the
     * route goes by here, and the first stretch that reaches [preferFromM] wins.
     * With nothing to go on that is 0, the start of the route, which is where
     * workouts begin; after a dropout it is where the user was last seen, since
     * they were moving forwards. Only when every tied stretch lies behind it does
     * the nearest one win, as before. A stretch [toleranceM] further away than
     * the nearest is a different street, not an ambiguity, and is never chosen.
     *
     * [minM] cuts off everything earlier, for callers that may only move forwards.
     */
    fun localise(p: LatLon, preferFromM: Double, toleranceM: Double, minM: Double = 0.0): Hit {
        if (size < 2) return Hit(0.0, Double.MAX_VALUE, 0)
        val first = segmentAt(max(0.0, minM))
        val last = size - 2
        val dist = DoubleArray(last - first + 1)
        val pos = DoubleArray(dist.size)
        var nearest = Double.MAX_VALUE
        var nearestIdx = 0
        for (k in dist.indices) {
            val i = first + k
            val pr = Geo.projectToSegment(p, LatLon(lat[i], lon[i]), LatLon(lat[i + 1], lon[i + 1]))
            dist[k] = pr.distanceM
            pos[k] = cum[i] + pr.t * (cum[i + 1] - cum[i])
            if (pr.distanceM < nearest) {
                nearest = pr.distanceM
                nearestIdx = k
            }
        }
        val limit = nearest + toleranceM
        var k = 0
        while (k < dist.size) {
            if (dist[k] > limit) { k++; continue }
            // One stretch: its nearest point, and how far along the route it runs.
            var best = k
            var end = k
            while (end + 1 < dist.size && dist[end + 1] <= limit) {
                end++
                if (dist[end] < dist[best]) best = end
            }
            if (cum[first + end + 1] >= preferFromM) return Hit(pos[best], dist[best], first + best)
            k = end + 1
        }
        return Hit(pos[nearestIdx], dist[nearestIdx], first + nearestIdx)
    }

    private fun projectRange(p: LatLon, firstSeg: Int, lastSeg: Int): Hit {
        if (size < 2) return Hit(0.0, Double.MAX_VALUE, 0)
        val a = firstSeg.coerceIn(0, size - 2)
        val b = lastSeg.coerceIn(a, size - 2)
        var bestD = Double.MAX_VALUE
        var bestPos = 0.0
        var bestSeg = a
        for (i in a..b) {
            val pr = Geo.projectToSegment(p, LatLon(lat[i], lon[i]), LatLon(lat[i + 1], lon[i + 1]))
            if (pr.distanceM < bestD) {
                bestD = pr.distanceM
                bestPos = cum[i] + pr.t * (cum[i + 1] - cum[i])
                bestSeg = i
            }
        }
        return Hit(bestPos, bestD, bestSeg)
    }

    /** Index of the segment containing distance [m] along the polyline. */
    fun segmentAt(m: Double): Int {
        if (size < 2) return 0
        if (m <= 0.0) return 0
        if (m >= lengthM) return size - 2
        var lo = 0
        var hi = size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) ushr 1
            if (cum[mid] <= m) lo = mid else hi = mid
        }
        return lo.coerceAtMost(size - 2)
    }

    /** Position on the polyline at distance [m] from the start. */
    fun pointAt(m: Double): LatLon {
        if (size == 0) return LatLon(0.0, 0.0)
        if (size == 1 || m <= 0.0) return point(0)
        if (m >= lengthM) return point(size - 1)
        val i = segmentAt(m)
        val span = cum[i + 1] - cum[i]
        val f = if (span <= 0.0) 0.0 else (m - cum[i]) / span
        return Geo.interpolate(point(i), point(i + 1), f)
    }

    companion object {
        fun of(points: List<LatLon>): Polyline {
            val n = points.size
            val la = DoubleArray(n)
            val lo = DoubleArray(n)
            val cu = DoubleArray(n)
            for (i in 0 until n) {
                la[i] = points[i].lat
                lo[i] = points[i].lon
                cu[i] = if (i == 0) 0.0 else cu[i - 1] + Geo.haversine(points[i - 1], points[i])
            }
            return Polyline(la, lo, cu)
        }

        val EMPTY = Polyline(DoubleArray(0), DoubleArray(0), DoubleArray(0))
    }
}

/**
 * Resampling to a fixed spacing.
 *
 * Every comparison in the system — the coverage metric, the profile bins, the
 * matcher's route geometry — assumes evenly spaced points, so that "fraction of
 * points within tolerance" is a fraction of *length* and not a fraction of
 * however often the GPS happened to report.
 */
object Resampler {

    /**
     * Resample a single continuous run of positions to [spacingM].
     * A run must contain no dropouts; callers split first.
     */
    fun resample(points: List<LatLon>, spacingM: Double = Tuning.RESAMPLE_M): List<LatLon> {
        if (points.size < 2) return points
        val out = ArrayList<LatLon>(points.size)
        out.add(points[0])
        var carry = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val seg = Geo.haversine(a, b)
            if (seg <= 0.0) continue
            var d = spacingM - carry
            while (d <= seg) {
                out.add(Geo.interpolate(a, b, d / seg))
                d += spacingM
            }
            carry = (carry + seg) % spacingM
        }
        val last = points.last()
        if (Geo.haversine(out.last(), last) > spacingM * 0.5) out.add(last)
        return out
    }

    /**
     * Resample with an accompanying scalar carried along (active time, in practice),
     * linearly interpolated at every emitted point. Returns positions and values in
     * parallel lists of equal length.
     */
    fun resampleWithValue(
        points: List<LatLon>,
        values: DoubleArray,
        spacingM: Double = Tuning.RESAMPLE_M,
    ): Pair<List<LatLon>, DoubleArray> {
        val (p, a, _) = resampleWithValues(points, values, values, spacingM)
        return p to a
    }

    /**
     * As [resampleWithValue] with a second scalar carried alongside.
     *
     * One call rather than two because both series have to land on exactly the
     * same emitted points: active time and altitude are read together per point
     * for the rest of their lives, and two passes agreeing only because the
     * arithmetic happens to be deterministic is not a guarantee worth resting on.
     *
     * A NaN in either series propagates through the interpolation, which is what
     * is wanted — altitude is optional, and a point next to a missing one cannot
     * be interpolated into existence.
     */
    fun resampleWithValues(
        points: List<LatLon>,
        first: DoubleArray,
        second: DoubleArray,
        spacingM: Double = Tuning.RESAMPLE_M,
    ): Triple<List<LatLon>, DoubleArray, DoubleArray> {
        require(points.size == first.size && points.size == second.size) { "points/values length mismatch" }
        if (points.size < 2) return Triple(points, first, second)
        val outP = ArrayList<LatLon>(points.size)
        val outA = ArrayList<Double>(points.size)
        val outB = ArrayList<Double>(points.size)
        outP.add(points[0]); outA.add(first[0]); outB.add(second[0])
        var carry = 0.0
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val seg = Geo.haversine(a, b)
            if (seg <= 0.0) continue
            var d = spacingM - carry
            while (d <= seg) {
                val f = d / seg
                outP.add(Geo.interpolate(a, b, f))
                outA.add(first[i] + (first[i + 1] - first[i]) * f)
                outB.add(second[i] + (second[i + 1] - second[i]) * f)
                d += spacingM
            }
            carry = (carry + seg) % spacingM
        }
        if (Geo.haversine(outP.last(), points.last()) > spacingM * 0.5) {
            outP.add(points.last()); outA.add(first.last()); outB.add(second.last())
        }
        return Triple(outP, outA.toDoubleArray(), outB.toDoubleArray())
    }
}
