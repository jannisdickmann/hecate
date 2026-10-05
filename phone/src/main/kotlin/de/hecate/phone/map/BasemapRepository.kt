package de.hecate.phone.map

import android.content.Context
import android.net.Uri
import android.util.Log
import de.hecate.core.map.BasemapQuery
import de.hecate.core.map.LatLonBounds
import de.hecate.core.map.Level
import de.hecate.core.map.MapScene
import de.hecate.core.map.TileCache
import de.hecate.core.map.TileGrid
import de.hecate.core.map.TileId
import de.hecate.core.map.TileStore
import de.hecate.core.map.overpass.BasemapBuilder
import de.hecate.core.map.overpass.FetchPlanner
import de.hecate.core.map.overpass.OsmJson
import de.hecate.core.map.overpass.OverpassClient
import de.hecate.core.map.overpass.OverpassQuery
import de.hecate.core.map.overpass.UrlOverpassClient
import de.hecate.phone.io.CacheFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The dim OpenStreetMap ground, on the phone (Appendix A.29).
 *
 * Everything interesting is in `:core` — the codec, the viewport query, the
 * fetch planner, the manners. This owns the directory, the decoded-tile cache,
 * and the decision of *when* to reach for the network.
 *
 * **On demand.** Opening a track or the heatmap computes the tiles its own
 * viewport needs; anything missing is fetched in the background while the track
 * draws on empty ground, and the map appears when it arrives. Over any network,
 * because a route opened away from home is exactly when you want to know where
 * you are. A failed fetch is silent and the view is simply mapless, which is
 * what every view in this app looked like before this feature existed.
 *
 * **Bounded.** A viewport fetch asks for at most [MAX_ON_DEMAND_CHUNKS]
 * requests' worth of ground. Opening one route in a city you have never
 * downloaded should cost a few seconds, not a quarter of an hour and eighty
 * megabytes; the rest of that region is what „Karte vorladen" is for.
 */
class BasemapRepository(
    private val context: Context,
    private val client: OverpassClient = UrlOverpassClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {

    val store = TileStore(File(context.filesDir, TileStore.DIR_NAME))

    /** One cache for every view, which is why [BasemapQuery] returns lat/lon:
     *  the route drawing, the heatmap card and the full-screen view have three
     *  different projections between them and share these tiles. */
    private val cache = TileCache()

    private val _stats = MutableStateFlow(TileStore.Stats(0, 0L, null, 0))
    val stats: StateFlow<TileStore.Stats> = _stats.asStateFlow()

    /** Bumped whenever tiles land, so a view that drew on empty ground knows to
     *  ask again. A counter rather than the scene itself: what each view wants
     *  differs by its own viewport. */
    private val _generation = MutableStateFlow(0)
    val generation: StateFlow<Int> = _generation.asStateFlow()

    private val fetching = Mutex()
    private val asked = HashSet<Pair<Level, TileId>>()

    init {
        scope.launch { refreshStats() }
    }

    /**
     * The scene for one viewport, off the main thread.
     *
     * Returns whatever is on disk *now*. Nothing here waits for a fetch: the
     * honest picture while a download runs is the one this app has always
     * drawn — a track on empty ground.
     */
    suspend fun scene(bounds: LatLonBounds, dpPerM: Double): MapScene = withContext(Dispatchers.Default) {
        runCatching { BasemapQuery.query(store, bounds, dpPerM, cache) }
            .getOrElse {
                Log.w(TAG, "Kartenausschnitt konnte nicht gelesen werden", it)
                MapScene.EMPTY
            }
    }

    /**
     * Ask for the ground under a viewport, if it is not already there.
     *
     * Fire and forget. Every tile that arrives is written before the next one is
     * asked for, so an interrupted fetch leaves whole tiles behind rather than a
     * corrupt region, and asking again costs only what is still missing.
     */
    fun request(bounds: LatLonBounds, dpPerM: Double) {
        val level = BasemapQuery.levelAt(dpPerM)
        scope.launch { fetch(level, TileGrid.tilesIn(bounds)) }
    }

    private suspend fun fetch(level: Level, wanted: List<TileId>) {
        // One request at a time across the whole app, which is the manners the
        // client promises and the thing a repository can actually enforce.
        fetching.withLock {
            val missing = store.missing(level, wanted).filter { (level to it) !in asked }
            if (missing.isEmpty()) return
            asked.addAll(missing.map { level to it })
            val chunks = FetchPlanner.plan(missing).take(MAX_ON_DEMAND_CHUNKS)
            for (chunk in chunks) {
                try {
                    val body = client.fetch(OverpassQuery.build(chunk.bounds, level))
                    val result = BasemapBuilder.build(OsmJson.parse(body), level, chunk.tiles)
                    for (tile in result.tiles) store.write(tile)
                    cache.clear()
                    _generation.value += 1
                } catch (e: Exception) {
                    // Silent by design: a mapless view is exactly today's
                    // picture, and a snackbar about scenery on every train
                    // journey would be worse than no map.
                    Log.i(TAG, "Karte nicht geladen (${chunk.tileCount} Kacheln): ${e.message}")
                    asked.removeAll(chunk.tiles.map { level to it }.toSet())
                    return
                }
            }
            store.refreshIndex()
            refreshStats()
        }
    }

    /**
     * Take a store built on a laptop, for the case where the whole thing should
     * be filled over ethernet before a trip.
     *
     * Merging, and validating: [TileStore.unpack] decodes every entry before it
     * keeps it, so a file that is not a map — or is one from a future version of
     * the format — leaves what is installed untouched.
     */
    suspend fun import(uri: Uri): Int = withContext(Dispatchers.IO) {
        val taken = CacheFile.use(context, uri, "hecate-karte") { file ->
            file.inputStream().use { store.unpack(it) }
        }
        cache.clear()
        asked.clear()
        _generation.value += 1
        refreshStats()
        taken
    }

    /**
     * Remove everything.
     *
     * This is the feature's off switch. There is no toggle: a toggle would be a
     * fifth state to test for something that was asked for unconditionally, and
     * deleting the data says the same thing without one.
     */
    suspend fun delete() = withContext(Dispatchers.IO) {
        store.clear()
        cache.clear()
        asked.clear()
        _generation.value += 1
        refreshStats()
    }

    /**
     * Everything the prefetch worker needs, so it holds no store of its own.
     *
     * The fetch lock is taken per block, not for the run. A whole prefetch can
     * take the better part of an hour, and held for all of it, it made every
     * map opened meanwhile wait for the end before it could fetch its own
     * ground. And each block is announced as it lands ([generation], [stats])
     * rather than once at the end, so the maps on screen fill in while the
     * download runs instead of after it.
     */
    internal suspend fun fill(level: Level, tiles: List<TileId>, onProgress: (Int, Int) -> Unit): Int {
        var written = 0
        val chunks = FetchPlanner.plan(store.missing(level, tiles))
        for ((i, chunk) in chunks.withIndex()) {
            onProgress(i, chunks.size)
            fetching.withLock {
                // A map on screen may have fetched some of it in the meantime.
                if (store.missing(level, chunk.tiles).isEmpty()) return@withLock
                try {
                    val body = client.fetch(OverpassQuery.build(chunk.bounds, level))
                    val result = BasemapBuilder.build(OsmJson.parse(body), level, chunk.tiles)
                    for (tile in result.tiles) store.write(tile)
                    written += result.tiles.size
                    cache.clear()
                    _generation.value += 1
                } catch (e: Exception) {
                    // Every completed tile is already on disk, so a run that
                    // dies half way is resumed rather than restarted.
                    Log.w(TAG, "Vorladen: Block ${i + 1}/${chunks.size} fehlgeschlagen: ${e.message}")
                }
            }
            refreshStats()
        }
        onProgress(chunks.size, chunks.size)
        if (written > 0) {
            fetching.withLock {
                asked.clear()
                store.refreshIndex()
            }
        }
        refreshStats()
        return written
    }

    private fun refreshStats() {
        _stats.value = runCatching { store.stats() }.getOrDefault(TileStore.Stats(0, 0L, null, 0))
    }

    private companion object {
        const val TAG = "BasemapRepository"

        /** At most this many Overpass requests for one viewport. Two chunks is
         *  up to a thousand square kilometres, which covers any route drawing
         *  and most of a pinched heatmap. */
        const val MAX_ON_DEMAND_CHUNKS = 2
    }
}
