package de.hecate.core.map

import de.hecate.core.Tuning
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.geo.cellKey
import kotlin.math.abs
import kotlin.math.floor

/**
 * Which ground is worth a map: the places the user has actually been, not a box
 * around their life.
 *
 * The recordings in one real history are **not one area**. They run from 45.5 to
 * 50.0 °N and 7.3 to 11.9 °E and fall into twelve disjoint clusters — a home
 * region, a second one, a handful of holidays. A single box around all of it is
 * about 175,000 km²; the twelve with a three-kilometre margin are 4,677. That
 * ratio is the entire reason this file exists.
 *
 * The clustering is a grid, not a metric clusterer: positions are binned at
 * [Tuning.MAP_CLUSTER_GRID_M] and adjacent bins are joined. Two rides on
 * opposite sides of a town land in bins that touch and become one region; a
 * holiday five hundred kilometres away does not. It needs no parameters beyond
 * the bin size and it is O(n).
 *
 * **The grid is fixed in degrees**, not derived from the data, and that is the
 * property the whole feature rests on: the region boxes — and therefore the
 * Overpass queries — are a function of *which ground* was ridden and not of how
 * many times or in what order. A new recording two hundred metres outside an
 * existing cluster produces the same snapped box, hence the same query, hence
 * nothing to fetch. If the grid moved with the data, every ride would
 * invalidate the map.
 */
object Coverage {

    /** One patch of ground worth fetching. */
    data class Region(
        /** Snapped to the tile grid, which is what makes the query idempotent. */
        val bounds: LatLonBounds,
        /** How many positions fell inside. Only used for reporting. */
        val positions: Int,
        val name: String,
    ) {
        val tiles: List<TileId> get() = TileGrid.tilesIn(bounds)
    }

    /** Square in degrees rather than on the ground: 0.0449°, which is 5 km
     *  north–south everywhere and 3.2 km east–west at 50 °N. Grouping does not
     *  care, and a grid that does not move is worth more than a square one. */
    private val CELL_DEG: Double = Tuning.MAP_CLUSTER_GRID_M / Geo.metersPerDegLat()

    /**
     * The regions [positions] fall into.
     *
     * [marginM] is grown around each cluster before the box is snapped outwards
     * to whole tiles.
     */
    fun regions(
        positions: Iterable<LatLon>,
        marginM: Double = Tuning.MAP_REGION_MARGIN_M,
        minPositions: Int = Tuning.MAP_MIN_CLUSTER_SAMPLES,
    ): List<Region> {
        val bins = HashMap<Long, ArrayList<LatLon>>()
        for (p in positions) {
            if (!p.lat.isFinite() || !p.lon.isFinite()) continue
            if (abs(p.lat) > 90.0 || abs(p.lon) > 180.0) continue
            bins.getOrPut(key(cellOf(p.lat), cellOf(p.lon))) { ArrayList() }.add(p)
        }
        if (bins.isEmpty()) return emptyList()

        // Flood fill over occupied bins, eight-connected so a ride that crosses
        // a bin corner does not split a town in two.
        val seen = HashSet<Long>()
        val out = ArrayList<Region>()
        for (start in bins.keys) {
            if (!seen.add(start)) continue
            val queue = ArrayDeque<Long>()
            queue.add(start)
            val members = ArrayList<LatLon>()
            while (queue.isNotEmpty()) {
                val cell = queue.removeFirst()
                members.addAll(bins[cell] ?: continue)
                val r = row(cell)
                val c = col(cell)
                for (dr in -1..1) for (dc in -1..1) {
                    if (dr == 0 && dc == 0) continue
                    val n = key(r + dr, c + dc)
                    if (bins.containsKey(n) && seen.add(n)) queue.add(n)
                }
            }
            if (members.size < minPositions) continue
            val raw = LatLonBounds.around(members) ?: continue
            out.add(
                Region(
                    bounds = TileGrid.snap(raw.expandedBy(marginM)),
                    positions = members.size,
                    name = nameOf(raw),
                ),
            )
        }
        // Two clusters whose margins overlap are one region: fetching both would
        // ask Overpass for the same ground twice and store the overlap twice.
        return merge(out).sortedByDescending { it.positions }
    }

    /** Repeatedly fold intersecting regions together until none do. */
    private fun merge(regions: List<Region>): List<Region> {
        var current = regions
        while (true) {
            val next = ArrayList<Region>()
            var merged = false
            for (r in current) {
                val hit = next.indexOfFirst { it.bounds.intersects(r.bounds) }
                if (hit < 0) {
                    next.add(r)
                } else {
                    val a = next[hit]
                    val union = a.bounds.union(r.bounds)
                    next[hit] = Region(
                        bounds = union,
                        positions = a.positions + r.positions,
                        name = if (a.positions >= r.positions) a.name else r.name,
                    )
                    merged = true
                }
            }
            current = next
            if (!merged) return current
        }
    }

    /**
     * A name for a region before anything is known about it.
     *
     * Coordinates, not a guess: nothing on this machine can turn a box into
     * the name of a range of hills, and a wrong name is worse than a dull one. Once the region has
     * been fetched, [de.hecate.core.map.overpass.BasemapBuilder] replaces this
     * with the largest place inside it, which is the name a person would use.
     */
    fun nameOf(b: LatLonBounds): String {
        val lat = (b.minLat + b.maxLat) / 2.0
        val lon = (b.minLon + b.maxLon) / 2.0
        return "%.2f %s %.2f %s".format(
            java.util.Locale.GERMANY,
            abs(lat), if (lat >= 0) "N" else "S",
            abs(lon), if (lon >= 0) "O" else "W",
        )
    }

    private fun cellOf(deg: Double): Int = floor(deg / CELL_DEG).toInt()
    private fun key(row: Int, col: Int): Long = cellKey(row.toLong(), col.toLong())
    private fun row(key: Long): Int = (key shr 32).toInt()
    private fun col(key: Long): Int = key.toInt()
}
