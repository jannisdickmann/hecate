package de.hecate.core

import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.map.BasemapQuery
import de.hecate.core.map.Clip
import de.hecate.core.map.LatLonBounds
import de.hecate.core.map.Level
import de.hecate.core.map.MapClass
import de.hecate.core.map.MapFeature
import de.hecate.core.map.MapPlace
import de.hecate.core.map.PlaceClass
import de.hecate.core.map.Tile
import de.hecate.core.map.TileCache
import de.hecate.core.map.TileGrid
import de.hecate.core.map.TileId
import de.hecate.core.map.TileStore
import de.hecate.core.map.overpass.BasemapBuilder
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The viewport query: which ground, at which density, and what happens at a tile
 * seam.
 *
 * The seam is the interesting one. Features are clipped into their tiles at
 * build time, which is what makes a tile self-contained — and the price is that
 * a road crossing a boundary is two features. They have to meet.
 */
class BasemapQueryTest {

    private fun tempStore(): TileStore =
        TileStore(Files.createTempDirectory("karte").toFile().also(File::deleteOnExit))

    /** A road running west to east straight across a tile boundary. */
    private fun roadAcrossSeam(): Pair<TileStore, Double> {
        val store = tempStore()
        val seamLon = TileGrid.westOf(TileGrid.colOf(11.01) + 1)
        val lat = 49.62
        val points = List(200) { LatLon(lat, seamLon - 0.02 + it * 0.0002) }
        val result = BasemapBuilder.build(
            response = de.hecate.core.map.overpass.OsmResponse(
                elements = listOf(
                    de.hecate.core.map.overpass.OsmElement(
                        type = "way", id = 1,
                        geometry = points.map { de.hecate.core.map.overpass.OsmPoint(it.lat, it.lon) },
                        tags = mapOf("highway" to "primary"),
                    ),
                ),
            ),
            level = Level.GROB,
            wanted = TileGrid.tilesIn(LatLonBounds(lat - 0.01, seamLon - 0.03, lat + 0.01, seamLon + 0.03)),
        )
        for (t in result.tiles) store.write(t)
        return store to seamLon
    }

    @Test
    fun `a box across a tile seam returns both halves, and they meet`() {
        val (store, seamLon) = roadAcrossSeam()
        val bounds = LatLonBounds(49.615, seamLon - 0.005, 49.625, seamLon + 0.005)
        val scene = BasemapQuery.query(store, bounds, dpPerM = 0.01, cache = TileCache())

        val pieces = scene.features.filter { it.cls == MapClass.PRIMARY }
        assertEquals(2, pieces.size, "the road should be in two tiles, got ${pieces.size}")

        val west = pieces.minByOrNull { it.lon[0] }!!
        val east = pieces.maxByOrNull { it.lon[0] }!!
        val endOfWest = LatLon(west.lat[west.size - 1], west.lon[west.size - 1])
        val startOfEast = LatLon(east.lat[0], east.lon[0])
        // One quantisation unit is 0.85 m; the two ends are the same point on
        // the seam, written twice.
        assertTrue(
            Geo.haversine(endOfWest, startOfEast) < 1.5,
            "the halves are ${Geo.haversine(endOfWest, startOfEast)} m apart at the seam",
        )
        assertTrue(kotlin.math.abs(endOfWest.lon - seamLon) < 1e-5)
    }

    @Test
    fun `ground that was never fetched is an empty scene, not an exception`() {
        val store = tempStore()
        val scene = BasemapQuery.query(
            store,
            LatLonBounds(45.4, 10.2, 45.5, 10.3),
            dpPerM = 0.05,
            cache = TileCache(),
        )
        assertTrue(scene.isEmpty)
    }

    /**
     * Every band is a floor — zooming in never takes something away — **except**
     * the urban patch, which closes when the streets it stands in for open.
     * This is the one entry in the table somebody will later "fix".
     */
    @Test
    fun `the level of detail is monotone, except for the urban patch`() {
        val densities = listOf(0.0, 0.005, 0.01, 0.029, 0.031, 0.05, 0.09, 0.11, 0.2, 1.0)
        for (i in 1 until densities.size) {
            val looser = BasemapQuery.classesAt(densities[i - 1])
            val tighter = BasemapQuery.classesAt(densities[i])
            assertTrue(tighter.containsAll(looser), "classes shrank between ${densities[i - 1]} and ${densities[i]}")
            assertTrue(
                BasemapQuery.placesAt(densities[i]).containsAll(BasemapQuery.placesAt(densities[i - 1])),
                "places shrank between ${densities[i - 1]} and ${densities[i]}",
            )
        }
        assertTrue(BasemapQuery.urbanPatchAt(0.01), "the patch should be on at whole-region zoom")
        assertFalse(BasemapQuery.urbanPatchAt(0.05), "the patch should be off once streets are drawn")
    }

    @Test
    fun `the coarse level answers everything below the street band`() {
        assertEquals(Level.GROB, BasemapQuery.levelAt(0.0))
        assertEquals(Level.GROB, BasemapQuery.levelAt(Tuning.MAP_LOD_STREETS - 1e-9))
        assertEquals(Level.FEIN, BasemapQuery.levelAt(Tuning.MAP_LOD_STREETS))
        assertEquals(Level.FEIN, BasemapQuery.levelAt(1.0))
    }

    /**
     * A coarse tile stands in for a detailed one that has not arrived yet.
     * Fewer roads is a quieter map, not a wrong one, and the alternative is a
     * hole in the middle of the picture while a fetch runs.
     */
    @Test
    fun `a coarse tile stands in while the detailed one is missing`() {
        val store = tempStore()
        val id = TileId(TileGrid.rowOf(49.62), TileGrid.colOf(11.01))
        val b = TileGrid.bounds(id)
        store.write(
            Tile(
                id, Level.GROB, 1L,
                listOf(
                    MapFeature(
                        MapClass.MOTORWAY,
                        doubleArrayOf(b.minLat + 0.001, b.minLat + 0.02),
                        doubleArrayOf(b.minLon + 0.001, b.minLon + 0.02),
                    ),
                ),
                listOf(MapPlace(PlaceClass.CITY, LatLon(b.minLat + 0.01, b.minLon + 0.01), "Stadt", 115_000)),
                emptyList(),
            ),
        )
        val scene = BasemapQuery.query(store, b, dpPerM = 0.2, cache = TileCache())
        assertEquals(1, scene.features.size)
        assertEquals("Stadt", scene.places.single().name)
    }

    @Test
    fun `the cache remembers that there is nothing there`() {
        val store = tempStore()
        val cache = TileCache()
        var loads = 0
        val id = TileId(1, 1)
        repeat(5) {
            cache.get(Level.GROB, id) { l, t -> loads++; store.readOrDiscard(l, t) }
        }
        assertEquals(1, loads, "an absent tile was looked up $loads times")
    }

    @Test
    fun `a segment that misses the tile entirely contributes nothing`() {
        val rect = LatLonBounds(49.0, 11.0, 49.1, 11.1)
        assertEquals(null, Clip.liangBarsky(LatLon(48.0, 11.05), LatLon(48.5, 11.05), rect))
        assertTrue(Clip.polyline(listOf(LatLon(48.0, 11.05), LatLon(48.5, 11.05)), rect).isEmpty())
    }

    @Test
    fun `a polyline that leaves and re-enters comes back as two pieces`() {
        val rect = LatLonBounds(49.0, 11.0, 49.1, 11.1)
        val points = listOf(
            LatLon(49.05, 11.01),
            LatLon(49.05, 11.05),
            LatLon(48.90, 11.06), // out the bottom
            LatLon(49.05, 11.07), // and back in
            LatLon(49.05, 11.09),
        )
        val pieces = Clip.polyline(points, rect)
        assertEquals(2, pieces.size)
        for (piece in pieces) {
            for (p in piece) assertTrue(rect.contains(p), "$p escaped $rect")
        }
    }
}
