package de.hecate.core.map

import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The model behind the dim street drawing that now sits under every track
 * (Appendix A.29).
 *
 * Everything here is deliberately small and free of Android: the classification,
 * the tile grid, the scene a viewport query returns. What draws it lives on the
 * phone, what fills it lives in [de.hecate.core.map.overpass], and both read
 * these types rather than inventing their own.
 */

/**
 * How much of the map a stored tile holds.
 *
 * Two levels, because the same ground is wanted at two very different budgets.
 * A whole-region view can legibly show water, rivers, rail and the two loudest
 * road classes and nothing else; drawing four thousand square kilometres of
 * residential streets into it produces a black scribble. So [GROB] is that
 * subset and is roughly a seventh of the bytes, and [FEIN] is everything.
 */
enum class Level(val dir: String) {
    GROB("grob"),
    FEIN("fein");

    companion object {
        fun of(ordinal: Int): Level? = entries.getOrNull(ordinal)
    }
}

/**
 * What a piece of OSM geometry became.
 *
 * The ids are a storage commitment: they are the order of the sections inside a
 * tile file, so inserting a class in the middle is a format version bump. See
 * [TileCodec].
 *
 * [minLevel] is the coarsest level a class is stored at — everything is in
 * [Level.FEIN]; only the five loudest things are also in [Level.GROB].
 */
enum class MapClass(
    val id: Int,
    val minLevel: Level,
    /** Back to front. The id is a storage order and the two are deliberately
     *  different: water has to be under everything and a motorway over
     *  everything, and the file wants water first for an unrelated reason
     *  (it is in every coarse tile). */
    val drawOrder: Int,
    val closed: Boolean = false,
) {
    WATER_AREA(0, Level.GROB, drawOrder = 1, closed = true),
    WATERWAY(1, Level.GROB, drawOrder = 2),
    RAIL(2, Level.GROB, drawOrder = 6),
    MOTORWAY(3, Level.GROB, drawOrder = 8),
    PRIMARY(4, Level.GROB, drawOrder = 7),
    MINOR(5, Level.FEIN, drawOrder = 5),
    SERVICE(6, Level.FEIN, drawOrder = 4),
    PATH(7, Level.FEIN, drawOrder = 3),

    /**
     * The faint built-up smudge — **derived, never classified**.
     *
     * Nothing in [Classify] returns it: [UrbanPatch] computes it at build time
     * from the outline of dense minor-road geometry, which is why it needs no
     * landuse data. It is a [MapClass] only because it is stored per tile like
     * one, and it is last so that giving it a home renumbered nothing.
     */
    URBAN(8, Level.GROB, drawOrder = 0, closed = true);

    /** True when this class belongs in a tile stored at [level]. */
    fun inLevel(level: Level): Boolean = level == Level.FEIN || minLevel == Level.GROB

    companion object {
        fun of(id: Int): MapClass? = entries.firstOrNull { it.id == id }

        /** Everything a tag can become: [URBAN] is not one of them. */
        val classified: List<MapClass> = entries.filter { it != URBAN }
    }
}

/**
 * What kind of settlement a label names, most important first.
 *
 * The rank is what decides a collision between two labels that want the same
 * pixels — a city beats a hamlet — and after it, population, and after that the
 * name, so the picture is a function of the data and not of iteration order.
 */
enum class PlaceClass(val id: Int, val rank: Int) {
    CITY(0, 0),
    TOWN(1, 1),
    VILLAGE(2, 2),
    SUBURB(3, 3),
    HAMLET(4, 4);

    companion object {
        fun of(id: Int): PlaceClass? = entries.firstOrNull { it.id == id }
    }
}

/** A named settlement, and how big it is when OSM bothered to say. */
data class MapPlace(
    val cls: PlaceClass,
    val at: LatLon,
    val name: String,
    val population: Int = 0,
)

/**
 * One drawn thing: a road, a river, the outline of a lake.
 *
 * Parallel primitive arrays rather than a list of points, for the same reason
 * [de.hecate.core.geo.Polyline] is: a street view is a few thousand of these and
 * a few hundred thousand vertices, and the scene is rebuilt whenever the
 * viewport changes.
 */
class MapFeature(
    val cls: MapClass,
    val lat: DoubleArray,
    val lon: DoubleArray,
    /** True for a closed ring — a lake, or the built-up outline — which is
     *  filled rather than stroked, and whose last vertex is its first. */
    val closedRing: Boolean = false,
) {
    val size: Int get() = lat.size

    override fun toString(): String = "${cls.name}[$size${if (closedRing) ", ring" else ""}]"
}

/**
 * The answer to one viewport query: what is on this ground, at this level of
 * detail, in lat/lon.
 *
 * Lat/lon rather than pixels on purpose. One decoded tile is read by the card,
 * the full-screen view and the route drawing, which have three different
 * projections between them; projecting here would mean either three caches or a
 * cache that is wrong for two of its readers.
 */
data class MapScene(
    val features: List<MapFeature>,
    val places: List<MapPlace>,
    /** The faint built-up smudge, drawn only when the streets themselves are
     *  not — see [BasemapQuery]. */
    val urbanPatches: List<MapFeature>,
) {
    val isEmpty: Boolean get() = features.isEmpty() && places.isEmpty() && urbanPatches.isEmpty()

    companion object {
        val EMPTY = MapScene(emptyList(), emptyList(), emptyList())
    }
}

/** A rectangle of ground, in degrees. Inclusive on both edges. */
data class LatLonBounds(
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    init {
        require(minLat <= maxLat && minLon <= maxLon) { "inverted bounds: $this" }
    }

    fun contains(p: LatLon): Boolean =
        p.lat in minLat..maxLat && p.lon in minLon..maxLon

    fun intersects(other: LatLonBounds): Boolean =
        minLat <= other.maxLat && maxLat >= other.minLat &&
            minLon <= other.maxLon && maxLon >= other.minLon

    fun expandedBy(metres: Double): LatLonBounds {
        val dLat = metres / Geo.metersPerDegLat()
        // At the latitude where the box is widest, so the margin is never short.
        val worst = max(kotlin.math.abs(minLat), kotlin.math.abs(maxLat))
        val dLon = metres / Geo.metersPerDegLon(worst).coerceAtLeast(1.0)
        return LatLonBounds(minLat - dLat, minLon - dLon, maxLat + dLat, maxLon + dLon)
    }

    fun union(other: LatLonBounds) = LatLonBounds(
        min(minLat, other.minLat), min(minLon, other.minLon),
        max(maxLat, other.maxLat), max(maxLon, other.maxLon),
    )

    /** Rough area [km²]. Used for reporting and for the transfer estimate only. */
    val areaKm2: Double
        get() {
            val midLat = (minLat + maxLat) / 2.0
            val h = (maxLat - minLat) * Geo.metersPerDegLat()
            val w = (maxLon - minLon) * Geo.metersPerDegLon(midLat)
            return h * w / 1_000_000.0
        }

    companion object {
        fun around(points: Iterable<LatLon>): LatLonBounds? {
            var minLat = Double.MAX_VALUE
            var minLon = Double.MAX_VALUE
            var maxLat = -Double.MAX_VALUE
            var maxLon = -Double.MAX_VALUE
            var any = false
            for (p in points) {
                any = true
                minLat = min(minLat, p.lat); maxLat = max(maxLat, p.lat)
                minLon = min(minLon, p.lon); maxLon = max(maxLon, p.lon)
            }
            return if (any) LatLonBounds(minLat, minLon, maxLat, maxLon) else null
        }
    }
}

/** Which tile. Row counts north from the equator, column east from Greenwich. */
data class TileId(val row: Int, val col: Int) : Comparable<TileId> {
    override fun compareTo(other: TileId): Int =
        if (row != other.row) row.compareTo(other.row) else col.compareTo(other.col)

    override fun toString(): String = "${row}_$col"
}

/**
 * The grid the whole map is cut on: **1/32° square in degrees**, which at
 * 49.5 °N is 3.5 km north–south by 2.2 km east–west, about 7.8 km².
 *
 * Degree-aligned and a power of two, which buys three things: a tile index is a
 * shift rather than a division, a tile's own origin is exactly representable in
 * binary so nothing drifts when coordinates are made tile-local, and the same
 * grid can snap the bounding boxes that are sent to Overpass — which is what
 * makes a request idempotent, and what keeps a request from describing a track
 * rather than a square of ground.
 *
 * **Tiles are not square on the ground** and no code may assume they are.
 */
object TileGrid {

    /** Tiles per degree, both axes. */
    const val TILES_PER_DEGREE: Int = 32

    /** The quantisation of a stored coordinate: 2^-17 degrees, which is 0.85 m
     *  north–south and 0.55 m east–west at 50 °N — an order of magnitude below
     *  the simplification tolerance and two below GNSS noise, so it is never the
     *  step that loses anything. */
    const val UNITS_PER_DEGREE: Int = 1 shl 17

    /** Consequently 4096 units across a tile, on both axes. */
    const val UNITS_PER_TILE: Int = UNITS_PER_DEGREE / TILES_PER_DEGREE

    const val DEGREES_PER_TILE: Double = 1.0 / TILES_PER_DEGREE

    fun rowOf(lat: Double): Int = floor(lat * TILES_PER_DEGREE).toInt()
    fun colOf(lon: Double): Int = floor(lon * TILES_PER_DEGREE).toInt()

    /**
     * The last row a box reaches, with its **north edge exclusive**.
     *
     * A box's top edge belongs to the tile below it, not to the one it is the
     * floor of. Without this, a box that is already a whole number of tiles
     * picks up a spare row above it — and then snapping it again picks up
     * another, which is a fetch that grows by a row every time it is planned.
     */
    fun lastRowOf(lat: Double): Int = kotlin.math.ceil(lat * TILES_PER_DEGREE).toInt() - 1

    fun lastColOf(lon: Double): Int = kotlin.math.ceil(lon * TILES_PER_DEGREE).toInt() - 1

    fun tileAt(p: LatLon): TileId = TileId(rowOf(p.lat), colOf(p.lon))

    /** South edge of a row [deg]. Exact in binary. */
    fun southOf(row: Int): Double = row * DEGREES_PER_TILE

    /** West edge of a column [deg]. Exact in binary. */
    fun westOf(col: Int): Double = col * DEGREES_PER_TILE

    fun bounds(id: TileId) = LatLonBounds(
        minLat = southOf(id.row),
        minLon = westOf(id.col),
        maxLat = southOf(id.row + 1),
        maxLon = westOf(id.col + 1),
    )

    /** Every tile the box touches, row-major. A box sitting exactly on the grid
     *  covers exactly its own tiles and not a spare row above and column beside
     *  them — see [lastRowOf]. */
    fun tilesIn(b: LatLonBounds): List<TileId> {
        val firstRow = rowOf(b.minLat)
        val firstCol = colOf(b.minLon)
        val out = ArrayList<TileId>()
        for (row in firstRow..maxOf(firstRow, lastRowOf(b.maxLat))) {
            for (col in firstCol..maxOf(firstCol, lastColOf(b.maxLon))) out.add(TileId(row, col))
        }
        return out
    }

    /**
     * The box grown outwards to whole tiles.
     *
     * This is the shape a fetch asks for, and the reason two fetches of the same
     * ground are the same query — which in turn is the reason a new recording
     * two hundred metres outside a cluster costs nothing. Snapping an
     * already-snapped box must therefore be a no-op, and that is the one
     * property of this function worth testing.
     */
    fun snap(b: LatLonBounds): LatLonBounds {
        val firstRow = rowOf(b.minLat)
        val firstCol = colOf(b.minLon)
        return LatLonBounds(
            minLat = southOf(firstRow),
            minLon = westOf(firstCol),
            maxLat = southOf(maxOf(firstRow, lastRowOf(b.maxLat)) + 1),
            maxLon = westOf(maxOf(firstCol, lastColOf(b.maxLon)) + 1),
        )
    }

    /** Tile-local x of a longitude, in quantisation units, clamped into the tile. */
    fun localX(col: Int, lon: Double): Int =
        Math.round((lon - westOf(col)) * UNITS_PER_DEGREE).toInt().coerceIn(0, UNITS_PER_TILE)

    /** Tile-local y of a latitude. Grows north, which is the opposite of a
     *  bitmap row and the same as a map's y axis. */
    fun localY(row: Int, lat: Double): Int =
        Math.round((lat - southOf(row)) * UNITS_PER_DEGREE).toInt().coerceIn(0, UNITS_PER_TILE)

    fun lonOf(col: Int, x: Int): Double = westOf(col) + x.toDouble() / UNITS_PER_DEGREE
    fun latOf(row: Int, y: Int): Double = southOf(row) + y.toDouble() / UNITS_PER_DEGREE
}
