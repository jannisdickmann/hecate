package de.hecate.core.map

import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon

/**
 * Clipping geometry to a tile.
 *
 * This is what makes a tile self-contained: every vertex of every feature in
 * tile C provably lies inside C, so coordinates can be tile-local and short, the
 * viewport query needs no per-feature bounding-box test, and two hundred metres
 * of motorway on screen can never drag in the other forty kilometres of it. It
 * costs one duplicated vertex per boundary crossing.
 */
object Clip {

    /** The contiguous pieces of an open polyline that lie inside [rect]. */
    fun polyline(points: List<LatLon>, rect: LatLonBounds): List<List<LatLon>> {
        val out = ArrayList<List<LatLon>>()
        var current: ArrayList<LatLon>? = null
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val t = liangBarsky(a, b, rect)
            if (t == null) {
                current = null
                continue
            }
            val (t0, t1) = t
            val p0 = Geo.interpolate(a, b, t0)
            val p1 = Geo.interpolate(a, b, t1)
            val open = current
            if (open != null && open.last() == p0) {
                open.add(p1)
            } else {
                val fresh = ArrayList<LatLon>(4)
                fresh.add(p0)
                fresh.add(p1)
                out.add(fresh)
                current = fresh
            }
            // The segment left the box before its far end, so whatever comes
            // next starts a new piece.
            if (t1 < 1.0) current = null
        }
        return out.filter { it.size >= 2 }
    }

    /**
     * Sutherland–Hodgman against the four edges of [rect].
     *
     * Returns a closed ring (last vertex repeating the first), or null when the
     * polygon misses the rectangle entirely. A polygon with a hole or a
     * self-intersection comes out wrong in the way this algorithm is famous
     * for — a degenerate edge along the boundary — which on a dim backdrop, for
     * a lake, is not worth a second algorithm.
     */
    fun polygon(points: List<LatLon>, rect: LatLonBounds): List<LatLon>? {
        var ring = points.dropLast(if (points.size >= 2 && points.first() == points.last()) 1 else 0)
        if (ring.size < 3) return null
        for (edge in 0..3) {
            if (ring.isEmpty()) return null
            val next = ArrayList<LatLon>(ring.size + 4)
            for (i in ring.indices) {
                val cur = ring[i]
                val prev = ring[(i + ring.size - 1) % ring.size]
                val curIn = inside(cur, edge, rect)
                val prevIn = inside(prev, edge, rect)
                if (curIn) {
                    if (!prevIn) next.add(intersect(prev, cur, edge, rect))
                    next.add(cur)
                } else if (prevIn) {
                    next.add(intersect(prev, cur, edge, rect))
                }
            }
            ring = next
        }
        if (ring.size < 3) return null
        return ring + ring.first()
    }

    private fun inside(p: LatLon, edge: Int, r: LatLonBounds): Boolean = when (edge) {
        0 -> p.lon >= r.minLon
        1 -> p.lon <= r.maxLon
        2 -> p.lat >= r.minLat
        else -> p.lat <= r.maxLat
    }

    private fun intersect(a: LatLon, b: LatLon, edge: Int, r: LatLonBounds): LatLon = when (edge) {
        0 -> atLon(a, b, r.minLon)
        1 -> atLon(a, b, r.maxLon)
        2 -> atLat(a, b, r.minLat)
        else -> atLat(a, b, r.maxLat)
    }

    private fun atLon(a: LatLon, b: LatLon, lon: Double): LatLon {
        val span = b.lon - a.lon
        val f = if (span == 0.0) 0.0 else (lon - a.lon) / span
        return LatLon(a.lat + (b.lat - a.lat) * f, lon)
    }

    private fun atLat(a: LatLon, b: LatLon, lat: Double): LatLon {
        val span = b.lat - a.lat
        val f = if (span == 0.0) 0.0 else (lat - a.lat) / span
        return LatLon(lat, a.lon + (b.lon - a.lon) * f)
    }

    /**
     * The parameter range of the segment a→b that lies inside the rectangle, or
     * null when none of it does.
     */
    fun liangBarsky(a: LatLon, b: LatLon, r: LatLonBounds): Pair<Double, Double>? {
        var t0 = 0.0
        var t1 = 1.0
        val dx = b.lon - a.lon
        val dy = b.lat - a.lat
        val checks = arrayOf(
            -dx to (a.lon - r.minLon),
            dx to (r.maxLon - a.lon),
            -dy to (a.lat - r.minLat),
            dy to (r.maxLat - a.lat),
        )
        for ((p, q) in checks) {
            if (p == 0.0) {
                if (q < 0.0) return null
                continue
            }
            val t = q / p
            if (p < 0.0) {
                if (t > t1) return null
                if (t > t0) t0 = t
            } else {
                if (t < t0) return null
                if (t < t1) t1 = t
            }
        }
        return if (t0 <= t1) t0 to t1 else null
    }
}
