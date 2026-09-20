package de.hecate.core.geo

/**
 * Douglas–Peucker line simplification.
 *
 * There was none in this repository before the basemap needed one, and
 * [Resampler] is not it: resampling makes the number of points proportional to
 * *length*, which is exactly right for the matcher — every comparison it makes
 * assumes evenly spaced points — and exactly wrong for a drawing, where what
 * costs bytes is a straight kilometre of Bundesstraße and what must be kept is
 * the roundabout at the end of it. Douglas–Peucker makes the point count
 * proportional to curvature instead.
 *
 * Two implementation notes, both learned the hard way rather than chosen:
 *
 * - **Iterative, with an explicit stack.** The obvious recursive form recurses
 *   once per retained vertex in the worst case, and a main-stem river arrives
 *   from Overpass with tens of thousands of vertices in near-sorted order —
 *   which is that worst case. A stack overflow inside a map build would be a
 *   crash that only happens on big regions, i.e. only on the real data.
 * - **Distance through [Geo.projectToSegment]**, which clamps *t* into [0,1].
 *   That matters on a closed ring, where the first and last vertex coincide and
 *   the unclamped perpendicular distance to the degenerate closing segment is
 *   undefined.
 *
 * Written against plain positions rather than against anything map-shaped, so
 * the route bundle can use it later.
 */
object Simplify {

    /**
     * The subsequence of [points] that stays within [toleranceM] of the
     * original everywhere. Endpoints are always kept.
     */
    fun douglasPeucker(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        val n = points.size
        if (n <= 2 || toleranceM <= 0.0) return points
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true

        // first/last of each pending span, as two parallel stacks of indices.
        val stack = IntArray(2 * n)
        var top = 0
        stack[top++] = 0
        stack[top++] = n - 1

        while (top > 0) {
            val last = stack[--top]
            val first = stack[--top]
            if (last <= first + 1) continue
            var worst = -1
            var worstD = toleranceM
            val a = points[first]
            val b = points[last]
            for (i in first + 1 until last) {
                val d = Geo.projectToSegment(points[i], a, b).distanceM
                if (d > worstD) {
                    worstD = d
                    worst = i
                }
            }
            if (worst < 0) continue
            keep[worst] = true
            stack[top++] = first
            stack[top++] = worst
            stack[top++] = worst
            stack[top++] = last
        }

        val out = ArrayList<LatLon>(n)
        for (i in 0 until n) if (keep[i]) out.add(points[i])
        return out
    }

    /**
     * As [douglasPeucker], for a closed ring whose last vertex repeats its
     * first.
     *
     * A ring needs a floor under it that an open line does not. Simplifying a
     * small lake at 10 m can legitimately reduce it to its two endpoints, which
     * is not a smaller lake but no lake at all — a degenerate shape that a fill
     * draws as nothing. Below four vertices the ring is rebuilt from evenly
     * spaced originals instead, which keeps a small lake as a small quadrilateral
     * rather than losing it.
     */
    fun ring(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        if (points.size < 4) return points
        val simplified = douglasPeucker(points, toleranceM)
        if (simplified.size >= 4) return simplified
        val step = (points.size - 1) / 3.0
        val out = ArrayList<LatLon>(4)
        for (i in 0..2) out.add(points[(i * step).toInt().coerceIn(0, points.size - 1)])
        out.add(points.last())
        return out
    }
}
