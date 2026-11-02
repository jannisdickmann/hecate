package de.hecate.core

import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.map.Coverage
import de.hecate.core.map.TileGrid
import de.hecate.core.map.overpass.FetchPlanner
import de.hecate.core.map.TileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which ground is worth a map.
 *
 * The property the whole feature rests on is in the last test here: the same
 * ground always produces the same box, so the same query, so nothing to fetch.
 * If the grid moved with the data, every recording would invalidate the map.
 */
class CoverageTest {

    private fun blob(centre: LatLon, n: Int, spreadDeg: Double = 0.01): List<LatLon> =
        List(n) { i ->
            LatLon(
                centre.lat + spreadDeg * kotlin.math.sin(i * 1.7),
                centre.lon + spreadDeg * kotlin.math.cos(i * 2.3),
            )
        }

    @Test
    fun `two distant blobs are two regions, each with its margin`() {
        val city = LatLon(49.60, 11.00)
        val lake = LatLon(45.60, 10.60)
        val regions = Coverage.regions(blob(city, 500) + blob(lake, 400))

        assertEquals(2, regions.size)
        for (r in regions) {
            assertTrue(r.bounds.contains(if (r.bounds.contains(city)) city else lake))
            // Three kilometres of margin, and then out to whole tiles — so the
            // box is at least that much bigger than the blob on every side.
            val height = (r.bounds.maxLat - r.bounds.minLat) * Geo.metersPerDegLat()
            assertTrue(height > 2 * Tuning.MAP_REGION_MARGIN_M, "region only ${height}m tall")
        }
        assertTrue(regions.none { it.bounds.contains(city) && it.bounds.contains(lake) })
    }

    @Test
    fun `two nearby blobs become one region rather than two overlapping ones`() {
        val regions = Coverage.regions(
            blob(LatLon(49.60, 11.00), 400) + blob(LatLon(49.63, 11.03), 400),
        )
        assertEquals(1, regions.size)
    }

    /** A handful of samples in the Gulf of Guinea is a broken fix, not a place
     *  that was ridden through. */
    @Test
    fun `a stray of ten samples is rejected`() {
        val regions = Coverage.regions(blob(LatLon(49.60, 11.00), 500) + blob(LatLon(0.0, 0.0), 10, 0.001))
        assertEquals(1, regions.size)
        assertTrue(regions.single().bounds.contains(LatLon(49.60, 11.00)))
    }

    @Test
    fun `nothing in gives nothing out`() {
        assertTrue(Coverage.regions(emptyList()).isEmpty())
        assertTrue(Coverage.regions(listOf(LatLon(Double.NaN, 11.0))).isEmpty())
    }

    /**
     * The idempotence the Overpass manners rest on: one more ride, two hundred
     * metres further out, must not change the box — or every ride would cost a
     * re-fetch of the ground around it.
     */
    @Test
    fun `a new sample two hundred metres outside a cluster asks the same question`() {
        val centre = LatLon(49.60, 11.00)
        val ridden = blob(centre, 500, spreadDeg = 0.004)
        val before = Coverage.regions(ridden)
        // Two hundred metres beyond the furthest point ever recorded — which is
        // what one new ride down a road on the edge of town looks like. The
        // three-kilometre margin and the snap to whole tiles absorb it, so the
        // box, the query and therefore the stored map are unchanged.
        val extra = Geo.fromLocal(LatLon(ridden.maxOf { it.lat }, centre.lon), 0.0, 200.0)
        val after = Coverage.regions(ridden + listOf(extra))

        assertEquals(1, after.size)
        assertEquals(before.single().bounds, after.single().bounds)
        assertEquals(before.single().tiles, after.single().tiles)
    }

    @Test
    fun `the box is snapped to whole tiles`() {
        val r = Coverage.regions(blob(LatLon(49.60, 11.00), 500)).single()
        assertEquals(TileGrid.snap(r.bounds), r.bounds)
        assertEquals(r.bounds, TileGrid.tilesIn(r.bounds).map { TileGrid.bounds(it) }.reduce { a, b -> a.union(b) })
    }

    // ------------------------------------------------------------- the planner

    @Test
    fun `contiguous missing tiles become one request`() {
        val tiles = buildList { for (r in 10..13) for (c in 20..23) add(TileId(r, c)) }
        val chunks = FetchPlanner.plan(tiles)
        assertEquals(1, chunks.size)
        assertEquals(16, chunks.single().tileCount)
        assertEquals(tiles.sorted(), chunks.single().tiles.sorted())
    }

    @Test
    fun `a chunk never exceeds a quarter of a degree a side`() {
        val tiles = buildList { for (r in 0..40) for (c in 0..40) add(TileId(r, c)) }
        for (chunk in FetchPlanner.plan(tiles)) {
            assertTrue(chunk.rows.last - chunk.rows.first + 1 <= FetchPlanner.MAX_TILES_PER_SIDE)
            assertTrue(chunk.cols.last - chunk.cols.first + 1 <= FetchPlanner.MAX_TILES_PER_SIDE)
            assertTrue(chunk.bounds.maxLat - chunk.bounds.minLat <= Tuning.MAP_MAX_CHUNK_DEG + 1e-9)
        }
    }

    @Test
    fun `every missing tile is covered exactly once, largest chunk first`() {
        val tiles = buildList {
            for (r in 0..9) for (c in 0..9) if ((r * 7 + c * 3) % 4 != 0) add(TileId(r, c))
        }
        val chunks = FetchPlanner.plan(tiles)
        val covered = chunks.flatMap { it.tiles }
        assertEquals(tiles.size, covered.size, "a tile was fetched twice or not at all")
        assertEquals(tiles.toSet(), covered.toSet())
        for (i in 1 until chunks.size) {
            assertTrue(chunks[i - 1].tileCount >= chunks[i].tileCount, "chunks are not largest-first")
        }
    }

    @Test
    fun `the same missing set always produces the same requests`() {
        val tiles = buildList { for (r in 0..8) for (c in 0..8) if ((r + c) % 3 != 0) add(TileId(r, c)) }
        assertEquals(FetchPlanner.plan(tiles), FetchPlanner.plan(tiles.shuffled(kotlin.random.Random(3))))
    }

    @Test
    fun `chunk boxes are tile-grid aligned`() {
        for (chunk in FetchPlanner.plan(listOf(TileId(1588, 352), TileId(1588, 353), TileId(1589, 352)))) {
            val b = chunk.bounds
            assertEquals(b, TileGrid.snap(b))
        }
    }
}
