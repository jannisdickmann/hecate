package de.hecate.core.map.overpass

import de.hecate.core.Tuning
import de.hecate.core.map.LatLonBounds
import de.hecate.core.map.TileGrid
import de.hecate.core.map.TileId
import kotlin.math.roundToLong

/** One Overpass request's worth of ground, and the tiles it will produce. */
data class FetchChunk(val rows: IntRange, val cols: IntRange) {

    val tiles: List<TileId>
        get() = buildList { for (r in rows) for (c in cols) add(TileId(r, c)) }

    val tileCount: Int get() = (rows.last - rows.first + 1) * (cols.last - cols.first + 1)

    /** Tile-grid aligned by construction, which is what makes the same ground
     *  always produce the same query — and what keeps a request from being a
     *  description of a track. */
    val bounds: LatLonBounds
        get() = LatLonBounds(
            minLat = TileGrid.southOf(rows.first),
            minLon = TileGrid.westOf(cols.first),
            maxLat = TileGrid.southOf(rows.last + 1),
            maxLon = TileGrid.westOf(cols.last + 1),
        )
}

/**
 * Missing tiles into as few requests as possible.
 *
 * **The fetch unit is a rectangle of tiles, not a tile.** Twenty-four separate
 * tile queries for one route view would be three minutes of polite pauses; one
 * box covering all twenty-four is a single response in twenty seconds. Each
 * chunk is capped at [Tuning.MAP_MAX_CHUNK_DEG] a side — about 500 km², which
 * is where the public instances start timing out rather than answering slowly.
 *
 * Responses are split back into tiles and each tile is written separately, so a
 * chunk that fails half way leaves whole tiles behind rather than a corrupt
 * region — and a resumed run re-fetches only what is still missing.
 */
object FetchPlanner {

    /** 0.25° at 32 tiles per degree. */
    val MAX_TILES_PER_SIDE: Int = (Tuning.MAP_MAX_CHUNK_DEG * TileGrid.TILES_PER_DEGREE).toInt().coerceAtLeast(1)

    /**
     * Greedy maximal rectangles, largest first.
     *
     * Deterministic: it starts from the northernmost, westernmost tile still
     * missing, grows east while the row is contiguous, then grows south while
     * the whole span is present. That is not the optimal rectangle cover — that
     * problem is NP-hard and nobody would notice the difference — but it is
     * stable, which matters more: the same missing set always produces the same
     * requests.
     */
    fun plan(missing: Collection<TileId>, maxSide: Int = MAX_TILES_PER_SIDE): List<FetchChunk> {
        val remaining = HashSet(missing)
        val out = ArrayList<FetchChunk>()
        while (remaining.isNotEmpty()) {
            val start = remaining.minWith(compareBy({ -it.row }, { it.col }))
            var width = 1
            while (width < maxSide && remaining.contains(TileId(start.row, start.col + width))) width++
            var height = 1
            while (height < maxSide &&
                (0 until width).all { remaining.contains(TileId(start.row - height, start.col + it)) }
            ) {
                height++
            }
            val rows = (start.row - height + 1)..start.row
            val cols = start.col until (start.col + width)
            for (r in rows) for (c in cols) remaining.remove(TileId(r, c))
            out.add(FetchChunk(rows, cols))
        }
        return out.sortedWith(compareByDescending<FetchChunk> { it.tileCount }.thenBy { it.rows.last }.thenBy { it.cols.first })
    }

    /**
     * Roughly what a chunk will cost on the wire [bytes].
     *
     * From one real measurement — 44 km² of dense city, 1.9 MB gzipped — so
     * **44 KB per km² of urban ground**, and less in open country. This is the
     * weakest number in the whole feature and is used only to print an estimate
     * before a long prefetch; `karte --dry-run` exists so that the estimate can
     * be read before it is paid.
     */
    fun estimatedBytes(areaKm2: Double, coarse: Boolean): Long {
        val perKm2 = if (coarse) URBAN_BYTES_PER_KM2 / 7.0 else URBAN_BYTES_PER_KM2
        return (areaKm2 * perKm2).roundToLong()
    }

    private const val URBAN_BYTES_PER_KM2 = 44_000.0
}
