package de.hecate.core.matcher

import de.hecate.core.Tuning
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.geo.Polyline
import de.hecate.core.geo.cellKey
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Spatial index over every route's geometry: a flat hash of
 * [Tuning.GRID_CELL_M] cells to the routes that pass through them (§5.2).
 *
 * Built once per route bundle, queried once per sample. Answering "which routes
 * could possibly be relevant here" by scanning a handful of cells instead of all
 * route vertices is what keeps the matcher inside the 5 ms budget of NFR-3.
 *
 * The cell grid is a local equirectangular projection anchored at the centre of
 * the indexed geometry, which is exact enough over the tens of kilometres a
 * personal route set spans.
 */
class GridIndex private constructor(
    private val origin: LatLon,
    private val cells: Map<Long, LongArray>,
    private val cellM: Double,
) {

    /** Route ids whose geometry passes within roughly [radiusM] of the point. */
    fun candidatesNear(p: LatLon, radiusM: Double = Tuning.MATCH_RADIUS_M): Set<Long> {
        val local = Geo.toLocal(origin, p)
        val cx = floor(local[0] / cellM).toLong()
        val cy = floor(local[1] / cellM).toLong()
        val reach = ceil(radiusM / cellM).toInt()
        val out = LinkedHashSet<Long>()
        for (dy in -reach..reach) {
            for (dx in -reach..reach) {
                cells[cellKey(cx + dx, cy + dy)]?.let { ids -> for (id in ids) out.add(id) }
            }
        }
        return out
    }

    val isEmpty: Boolean get() = cells.isEmpty()

    companion object {

        fun build(
            geometries: Map<Long, Polyline>,
            cellM: Double = Tuning.GRID_CELL_M,
        ): GridIndex {
            if (geometries.isEmpty()) {
                return GridIndex(LatLon(0.0, 0.0), emptyMap(), cellM)
            }
            var sLat = 0.0
            var sLon = 0.0
            var n = 0
            for (pl in geometries.values) {
                for (i in 0 until pl.size) {
                    sLat += pl.lat[i]; sLon += pl.lon[i]; n++
                }
            }
            val origin = if (n == 0) LatLon(0.0, 0.0) else LatLon(sLat / n, sLon / n)

            val acc = HashMap<Long, MutableSet<Long>>()
            for ((routeId, pl) in geometries) {
                // Stamp every vertex and, for long segments, points along them, so a
                // straight kilometre of road does not leave holes between vertices.
                for (i in 0 until pl.size) {
                    stamp(acc, origin, cellM, pl.lat[i], pl.lon[i], routeId)
                    if (i + 1 < pl.size) {
                        val d = pl.cum[i + 1] - pl.cum[i]
                        if (d > cellM) {
                            val steps = ceil(d / cellM).toInt()
                            for (s in 1 until steps) {
                                val p = pl.pointAt(pl.cum[i] + d * s / steps)
                                stamp(acc, origin, cellM, p.lat, p.lon, routeId)
                            }
                        }
                    }
                }
            }
            val frozen = HashMap<Long, LongArray>(acc.size)
            for ((k, v) in acc) frozen[k] = v.toLongArray()
            return GridIndex(origin, frozen, cellM)
        }

        private fun stamp(
            acc: HashMap<Long, MutableSet<Long>>,
            origin: LatLon,
            cellM: Double,
            lat: Double,
            lon: Double,
            routeId: Long,
        ) {
            val local = Geo.toLocal(origin, LatLon(lat, lon))
            val cx = floor(local[0] / cellM).toLong()
            val cy = floor(local[1] / cellM).toLong()
            acc.getOrPut(cellKey(cx, cy)) { LinkedHashSet() }.add(routeId)
        }
    }
}
