package de.hecate.core.map

import de.hecate.core.Tuning
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon

/**
 * The faint built-up smudge behind a whole-region view.
 *
 * At the zoom where a county fits on a phone, the useful question is "where are
 * the towns", and the honest answer is not four thousand square kilometres of
 * residential streets — that is a black scribble. It is a pale shape where the
 * streets are dense.
 *
 * Derived rather than fetched: the convex outline of the minor-road geometry
 * already in a tile, computed at build time. That costs nothing extra on the
 * wire, needs no landuse or building data, and is the reason both of those could
 * be dropped from the query.
 *
 * One hull per tile, which is coarse — a tile is 7.8 km², so a town's edge is
 * square wherever the tile boundary is. At the only zoom this is ever drawn at,
 * a tile is under a centimetre across, and the alternative (clustering inside
 * the tile) buys detail nobody can see.
 */
object UrbanPatch {

    /**
     * The patch for one tile, or null when there is not enough street in it to
     * call it built up.
     *
     * [minor] is the tile's own minor-road geometry, already clipped to the
     * tile.
     */
    fun of(tile: TileId, minor: List<MapFeature>): MapFeature? {
        var length = 0.0
        var points = 0
        for (f in minor) {
            points += f.size
            for (i in 1 until f.size) {
                length += Geo.haversine(f.lat[i - 1], f.lon[i - 1], f.lat[i], f.lon[i])
            }
        }
        if (length < Tuning.MAP_URBAN_MIN_LENGTH_M || points < 3) return null

        val hull = convexHull(buildList(points) { for (f in minor) for (i in 0 until f.size) add(LatLon(f.lat[i], f.lon[i])) })
        if (hull.size < 3) return null
        val closed = hull + hull.first()
        val bounds = TileGrid.bounds(tile)
        // The hull is built from vertices that are already inside the tile, so
        // it cannot leave it; clamping is belt and braces against a quantisation
        // rounding at the very edge.
        return MapFeature(
            cls = MapClass.URBAN,
            lat = DoubleArray(closed.size) { closed[it].lat.coerceIn(bounds.minLat, bounds.maxLat) },
            lon = DoubleArray(closed.size) { closed[it].lon.coerceIn(bounds.minLon, bounds.maxLon) },
            closedRing = true,
        )
    }

    /**
     * Andrew's monotone chain, in degrees.
     *
     * Degrees rather than metres is safe here and only here: a hull is a
     * combinatorial fact about which points are extreme, and inside one tile the
     * anisotropy of a degree is a constant scaling that cannot change which
     * points those are — a constant scale of one axis maps a convex hull onto
     * the convex hull of the scaled points.
     */
    fun convexHull(points: List<LatLon>): List<LatLon> {
        if (points.size < 3) return points
        val sorted = points.distinct().sortedWith(compareBy({ it.lon }, { it.lat }))
        if (sorted.size < 3) return sorted

        fun cross(o: LatLon, a: LatLon, b: LatLon): Double =
            (a.lon - o.lon) * (b.lat - o.lat) - (a.lat - o.lat) * (b.lon - o.lon)

        val lower = ArrayList<LatLon>()
        for (p in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(p)
        }
        val upper = ArrayList<LatLon>()
        for (p in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(p)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }
}
