package de.hecate.core.map.overpass

import de.hecate.core.Tuning
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.geo.Simplify
import de.hecate.core.map.Classify
import de.hecate.core.map.Clip
import de.hecate.core.map.LatLonBounds
import de.hecate.core.map.Level
import de.hecate.core.map.MapClass
import de.hecate.core.map.MapFeature
import de.hecate.core.map.MapPlace
import de.hecate.core.map.Tile
import de.hecate.core.map.TileGrid
import de.hecate.core.map.TileId
import de.hecate.core.map.UrbanPatch
import kotlin.math.abs

/**
 * One Overpass response into a set of stored tiles.
 *
 * The pipeline, in the order it runs and for the reasons given in each step's
 * own file: **classify** ([Classify]) · **join** · **simplify**
 * ([Simplify]) · **clip** · **quantise and deflate** ([de.hecate.core.map.TileCodec]).
 *
 * The join is worth its own sentence. OSM splits a Bundesstraße at every change
 * of tag — a bridge, a speed limit, a village boundary — so the same road
 * arrives as thirty ways of four vertices each. Joining ways of the same class
 * that share an endpoint gives about a quarter fewer features and, more
 * importantly, lets Douglas–Peucker work over a whole chain rather than over its
 * pieces, where every piece's endpoints are pinned and nothing can be removed.
 */
object BasemapBuilder {

    /** Everything one chunk produced. */
    data class Result(
        val tiles: List<Tile>,
        /** The largest settlement in the response, which is the name a person
         *  would give the region. Null when the box holds no named place. */
        val largestPlace: String?,
        val featureCount: Int,
        val vertexCount: Int,
    )

    fun build(
        response: OsmResponse,
        level: Level,
        wanted: Collection<TileId>,
        builtAtUtcMs: Long = System.currentTimeMillis(),
    ): Result {
        val wantedSet = wanted.toSet()
        val byClass = LinkedHashMap<MapClass, MutableList<List<LatLon>>>()
        val rings = LinkedHashMap<MapClass, MutableList<List<LatLon>>>()

        for (element in response.elements) {
            if (element.type != "way") continue
            val geometry = element.geometry ?: continue
            if (geometry.size < 2) continue
            val points = geometry.map { LatLon(it.lat, it.lon) }
            val closed = isClosed(points)
            val length = Geo.pathLength(points)
            val area = if (closed) areaOf(points) else 0.0
            val cls = Classify.wayClass(element.tags, length, area) ?: continue
            if (!cls.inLevel(level)) continue
            if (cls.closed) {
                if (!closed) continue
                rings.getOrPut(cls) { ArrayList() }.add(points)
            } else {
                byClass.getOrPut(cls) { ArrayList() }.add(points)
            }
        }

        val places = response.elements.mapNotNull { e ->
            if (e.type != "node") return@mapNotNull null
            val lat = e.lat ?: return@mapNotNull null
            val lon = e.lon ?: return@mapNotNull null
            val name = e.tags["name"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val cls = Classify.placeClass(e.tags) ?: return@mapNotNull null
            MapPlace(cls, LatLon(lat, lon), name, Classify.population(e.tags["population"]))
        }

        // Per tile, built up as the features are clipped into it.
        val features = HashMap<TileId, MutableList<MapFeature>>()
        var featureCount = 0
        var vertexCount = 0

        for ((cls, ways) in byClass) {
            val tolerance = toleranceOf(cls)
            for (chain in join(ways)) {
                val simplified = Simplify.douglasPeucker(chain, tolerance)
                if (simplified.size < 2) continue
                featureCount++
                vertexCount += simplified.size
                distribute(simplified, cls, closedRing = false, wantedSet, features)
            }
        }
        for ((cls, polygons) in rings) {
            val tolerance = toleranceOf(cls)
            for (polygon in polygons) {
                val simplified = Simplify.ring(polygon, tolerance)
                if (simplified.size < 4) continue
                featureCount++
                vertexCount += simplified.size
                distribute(simplified, cls, closedRing = true, wantedSet, features)
            }
        }

        val placesByTile = HashMap<TileId, MutableList<MapPlace>>()
        for (p in places) {
            val id = TileGrid.tileAt(p.at)
            if (id !in wantedSet) continue
            placesByTile.getOrPut(id) { ArrayList() }.add(p)
        }

        val tiles = ArrayList<Tile>(wantedSet.size)
        for (id in wantedSet.sorted()) {
            val own = features[id].orEmpty()
            val ownPlaces = placesByTile[id].orEmpty()
            // Derived here rather than fetched: the outline of the minor roads
            // already in this tile. Only ever meaningful at the detailed level,
            // which is the only one that holds minor roads.
            val patch = UrbanPatch.of(id, own.filter { it.cls == MapClass.MINOR })
            // Written even when all three are empty. "Nothing here" is an answer
            // worth storing: without it, open country is re-fetched on every
            // visit for as long as the user keeps running through it.
            tiles.add(Tile(id, level, builtAtUtcMs, own, ownPlaces, listOfNotNull(patch)))
        }

        // Only places that actually fell inside the fetched tiles. The query
        // asks for place nodes over a box grown by 15 km — so that a city just
        // outside it still gets its label — and naming the region from that
        // wider set named a valley after the neighbouring city.
        val largest = placesByTile.values.flatten().maxWithOrNull(
            compareBy<MapPlace> { -it.cls.rank }.thenBy { it.population },
        )?.name
        return Result(tiles, largest, featureCount, vertexCount)
    }

    private fun distribute(
        points: List<LatLon>,
        cls: MapClass,
        closedRing: Boolean,
        wanted: Set<TileId>,
        into: MutableMap<TileId, MutableList<MapFeature>>,
    ) {
        val box = LatLonBounds.around(points) ?: return
        for (id in TileGrid.tilesIn(box)) {
            if (id !in wanted) continue
            val rect = TileGrid.bounds(id)
            val pieces = if (closedRing) {
                listOfNotNull(Clip.polygon(points, rect))
            } else {
                Clip.polyline(points, rect)
            }
            for (piece in pieces) {
                if (piece.size < (if (closedRing) 4 else 2)) continue
                into.getOrPut(id) { ArrayList() }.add(
                    MapFeature(
                        cls = cls,
                        lat = DoubleArray(piece.size) { piece[it].lat },
                        lon = DoubleArray(piece.size) { piece[it].lon },
                        closedRing = closedRing,
                    ),
                )
            }
        }
    }

    private fun toleranceOf(cls: MapClass): Double = when (cls) {
        MapClass.MOTORWAY -> Tuning.MAP_SIMPLIFY_MOTORWAY_M
        MapClass.PRIMARY -> Tuning.MAP_SIMPLIFY_PRIMARY_M
        MapClass.RAIL -> Tuning.MAP_SIMPLIFY_RAIL_M
        MapClass.MINOR -> Tuning.MAP_SIMPLIFY_MINOR_M
        MapClass.WATER_AREA -> Tuning.MAP_SIMPLIFY_WATER_M
        MapClass.WATERWAY -> Tuning.MAP_SIMPLIFY_PATH_M
        MapClass.SERVICE -> Tuning.MAP_SIMPLIFY_SERVICE_M
        MapClass.PATH -> Tuning.MAP_SIMPLIFY_PATH_M
        MapClass.URBAN -> Tuning.MAP_SIMPLIFY_WATER_M
    }

    // -------------------------------------------------------------------- join

    /**
     * Chains of ways that share an endpoint, longest first.
     *
     * Endpoints are compared as exact doubles, which is right rather than
     * fragile: two OSM ways that meet share a *node*, so Overpass prints the
     * same decimal twice and the two parse to the same bits. A tolerance here
     * would join two roads that merely pass close to one another.
     */
    fun join(ways: List<List<LatLon>>): List<List<LatLon>> {
        if (ways.size <= 1) return ways
        val used = BooleanArray(ways.size)
        val byEnd = HashMap<LatLon, MutableList<Int>>()
        for ((i, w) in ways.withIndex()) {
            if (w.size < 2) continue
            byEnd.getOrPut(w.first()) { ArrayList() }.add(i)
            byEnd.getOrPut(w.last()) { ArrayList() }.add(i)
        }

        fun partner(at: LatLon, exclude: Int): Int? {
            val candidates = byEnd[at]?.filter { it != exclude && !used[it] } ?: return null
            // Only where it is unambiguous. At a junction of three roads of the
            // same class there is no "the next piece", and picking one would
            // join two roads that are not one road.
            return if (candidates.size == 1) candidates.single() else null
        }

        val out = ArrayList<List<LatLon>>(ways.size)
        for (i in ways.indices) {
            if (used[i] || ways[i].size < 2) continue
            used[i] = true
            val chain = ArrayList(ways[i])
            while (true) {
                val next = partner(chain.last(), -1) ?: break
                used[next] = true
                val w = ways[next]
                if (w.first() == chain.last()) chain.addAll(w.subList(1, w.size))
                else chain.addAll(w.asReversed().subList(1, w.size))
                if (chain.first() == chain.last()) break
            }
            while (true) {
                val prev = partner(chain.first(), -1) ?: break
                used[prev] = true
                val w = ways[prev]
                val piece = if (w.last() == chain.first()) w.subList(0, w.size - 1) else w.asReversed().subList(0, w.size - 1)
                chain.addAll(0, piece)
                if (chain.first() == chain.last()) break
            }
            out.add(chain)
        }
        return out
    }

    // ----------------------------------------------------------------- measure

    private fun isClosed(points: List<LatLon>): Boolean =
        points.size >= 4 && points.first() == points.last()

    /** Shoelace in the local plane, which is accurate to well under a percent
     *  over anything small enough to be one OSM way. */
    private fun areaOf(points: List<LatLon>): Double {
        if (points.size < 4) return 0.0
        val origin = points.first()
        var sum = 0.0
        var prev = Geo.toLocal(origin, points[0])
        for (i in 1 until points.size) {
            val cur = Geo.toLocal(origin, points[i])
            sum += prev[0] * cur[1] - cur[0] * prev[1]
            prev = cur
        }
        return abs(sum) / 2.0
    }
}
