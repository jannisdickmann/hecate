package de.hecate.phone.map

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.hecate.core.geo.LatLon
import de.hecate.core.map.LatLonBounds
import de.hecate.core.map.Level
import de.hecate.core.map.MapClass
import de.hecate.core.map.MapFeature
import de.hecate.core.map.MapPlace
import de.hecate.core.map.PlaceClass
import de.hecate.core.map.Tile
import de.hecate.core.map.TileGrid
import de.hecate.core.map.TileId
import de.hecate.core.map.TileStore
import de.hecate.core.map.overpass.OverpassClient
import de.hecate.core.map.overpass.OverpassException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The phone's half of the basemap: the store on disk, the import, and the
 * delete.
 *
 * As in `ArchiveRepositoryTest`, a `file://` Uri stands in for the `content://`
 * one a real picker hands over — which exercises everything but the picker
 * itself. The fetch is behind [OverpassClient], so nothing here opens a socket.
 */
@RunWith(RobolectricTestRunner::class)
class BasemapRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var repository: BasemapRepository

    private val id = TileId(TileGrid.rowOf(49.62), TileGrid.colOf(11.01))
    private val bounds = TileGrid.bounds(id)

    @Before
    fun setUp() {
        File(context.filesDir, TileStore.DIR_NAME).deleteRecursively()
        repository = BasemapRepository(context, OverpassClient { error("no fetching in this test") })
    }

    @After
    fun tearDown() {
        File(context.filesDir, TileStore.DIR_NAME).deleteRecursively()
    }

    /** A store holding one tile, as a ZIP the user could have been handed. */
    private fun bundle(corrupt: Boolean = false): Uri {
        val dir = File.createTempFile("karte-src", "").let { it.delete(); it.mkdirs(); it }
        val source = TileStore(dir)
        source.write(
            Tile(
                id, Level.FEIN, 1_758_000_000_000L,
                listOf(
                    MapFeature(
                        MapClass.MOTORWAY,
                        doubleArrayOf(bounds.minLat + 0.001, bounds.minLat + 0.02),
                        doubleArrayOf(bounds.minLon + 0.001, bounds.minLon + 0.02),
                    ),
                ),
                listOf(MapPlace(PlaceClass.VILLAGE, LatLon(bounds.minLat + 0.01, bounds.minLon + 0.01), "Möhrendorf", 4_200)),
                emptyList(),
            ),
        )
        source.refreshIndex()
        val zip = File.createTempFile("karte", ".zip", context.cacheDir)
        zip.outputStream().use { source.packInto(it) }
        if (corrupt) {
            // Not a ZIP at all, which is what picking the wrong file looks like.
            zip.writeBytes("das ist keine Karte".toByteArray())
        }
        return Uri.fromFile(zip)
    }

    @Test
    fun `an empty store yields an empty scene and no exception`(): Unit = runBlocking {
        assertTrue(repository.scene(bounds, dpPerM = 0.05).isEmpty)
        assertTrue(repository.stats.value.isEmpty)
    }

    @Test
    fun `a bundle built elsewhere is taken, and then drawn`(): Unit = runBlocking {
        assertEquals(1, repository.import(bundle()))

        val stats = repository.stats.value
        assertEquals(1, stats.tiles)
        assertTrue(stats.bytes > 0)

        val scene = repository.scene(bounds, dpPerM = 0.05)
        assertEquals(1, scene.features.size)
        assertEquals(MapClass.MOTORWAY, scene.features.single().cls)
        assertEquals("Möhrendorf", scene.places.single().name)
    }

    /**
     * The one that matters: a file that is not a map must leave what is
     * installed exactly as it was, rather than half replacing it.
     */
    @Test
    fun `a corrupt import leaves the installed store untouched`(): Unit = runBlocking {
        repository.import(bundle())
        val before = repository.stats.value

        runCatching { repository.import(bundle(corrupt = true)) }

        assertEquals(before.tiles, repository.stats.value.tiles)
        assertEquals(1, repository.scene(bounds, dpPerM = 0.05).features.size)
    }

    @Test
    fun `delete empties the store and the picture goes back to empty ground`(): Unit = runBlocking {
        repository.import(bundle())
        assertFalse(repository.stats.value.isEmpty)

        repository.delete()

        assertTrue(repository.stats.value.isEmpty)
        assertTrue(repository.scene(bounds, dpPerM = 0.05).isEmpty)
        assertFalse(File(context.filesDir, TileStore.DIR_NAME).exists())
    }

    /**
     * A failed fetch is silent by design — a mapless view is exactly the picture
     * this app drew before the feature existed, and a snackbar about scenery on
     * every train journey would be worse than no map.
     */
    @Test
    fun `a fetch that fails leaves the view mapless rather than broken`(): Unit = runBlocking {
        val failing = BasemapRepository(context, OverpassClient { throw OverpassException("kein Netz") })
        failing.request(LatLonBounds(49.60, 11.00, 49.62, 11.03), dpPerM = 0.05)
        // Nothing thrown, nothing stored, and the next query is the same empty
        // ground it was before.
        assertTrue(failing.scene(bounds, dpPerM = 0.05).isEmpty)
    }

    @Test
    fun `a tile written under a different level is not read as the wrong one`(): Unit = runBlocking {
        repository.import(bundle())
        // The bundle holds only a detailed tile. A coarse view falls back to
        // `grob`, finds nothing there — and the detailed tile is not silently
        // promoted, because that would draw a street grid across a county.
        assertTrue(repository.store.present(Level.GROB).isEmpty())
        assertEquals(listOf(id), repository.store.present(Level.FEIN))
    }
}
