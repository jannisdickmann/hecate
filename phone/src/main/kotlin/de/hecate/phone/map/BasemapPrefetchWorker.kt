package de.hecate.phone.map

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import de.hecate.core.Tuning
import de.hecate.core.geo.LatLon
import de.hecate.core.map.Coverage
import de.hecate.core.map.LatLonBounds
import de.hecate.core.map.Level
import de.hecate.core.map.MapRegion
import de.hecate.core.map.TileGrid
import de.hecate.core.map.TileId
import de.hecate.phone.AppGraph

/**
 * „Karte vorladen": the ground around every recording, over WLAN, in the
 * background.
 *
 * The whole point of doing this as a job rather than on a screen is that it is
 * long — a full prefetch is about forty requests and can take the better part of
 * an hour if Overpass is having a bad afternoon. It is enqueued with
 * `NetworkType.UNMETERED` and `setRequiresBatteryNotLow`, so it waits for the
 * conditions under which nobody minds, and it is **resumable by construction**:
 * every completed tile is already on disk, so a run that is cut short and
 * restarted fetches only what is still missing.
 *
 * It walks the coverage clusters derived from the database — the same
 * [Coverage] the laptop CLI uses — fetching [Level.GROB] for all of them and
 * [Level.FEIN] only within [Tuning.MAP_DETAIL_RADIUS_M] of a position actually
 * recorded. That is the difference between a few thousand square kilometres and
 * a few hundred.
 */
class BasemapPrefetchWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        return try {
            prefetch()
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Karte vorladen fehlgeschlagen", e)
            // Retry rather than fail: the usual cause is an instance under load,
            // and the next attempt costs only what is still missing.
            Result.retry()
        }
    }

    private suspend fun prefetch() {
        val repository = AppGraph.basemap
        val positions = positions()
        if (positions.isEmpty()) {
            Log.i(TAG, "keine Positionen, nichts vorzuladen")
            return
        }
        val regions = Coverage.regions(positions)
        if (regions.isEmpty()) return

        val coarse = LinkedHashSet<TileId>()
        for (r in regions) coarse.addAll(r.tiles)
        val fine = detailTiles(positions, coarse)

        Log.i(TAG, "${regions.size} Regionen, ${coarse.size} grobe und ${fine.size} feine Kacheln")
        repository.fill(Level.GROB, coarse.toList()) { i, n -> report(0, i, n) }
        repository.fill(Level.FEIN, fine.toList()) { i, n -> report(1, i, n) }

        repository.store.refreshIndex(regions.map { MapRegion.of(it.name, it.bounds) })
    }

    /**
     * Every position the database holds.
     *
     * Read straight off the samples rather than through `Track`, because what is
     * wanted here is *where the user has been*, not a resampled path — and a
     * recording too short or too broken to become a track was still somewhere.
     */
    private suspend fun positions(): List<LatLon> {
        val dao = AppGraph.db.sessions()
        val out = ArrayList<LatLon>()
        for (session in dao.all()) {
            for (s in dao.samples(session.id)) {
                if (s.accuracyM != null && s.accuracyM > Tuning.MAX_USABLE_ACCURACY_M) continue
                out.add(LatLon(s.lat, s.lon))
            }
        }
        return out
    }

    private fun detailTiles(positions: List<LatLon>, within: Set<TileId>): Set<TileId> {
        val out = LinkedHashSet<TileId>()
        for (p in positions) {
            val box = LatLonBounds(p.lat, p.lon, p.lat, p.lon).expandedBy(Tuning.MAP_DETAIL_RADIUS_M)
            for (id in TileGrid.tilesIn(box)) if (id in within) out.add(id)
        }
        return out
    }

    /** Logged, and published for the settings card, which used to say "sobald
     *  WLAN da ist" for an hour of downloading because it could not tell a job
     *  that was waiting from one that was running. */
    private fun report(phase: Int, chunk: Int, chunks: Int) {
        val pass = if (phase == 0) "grob" else "fein"
        Log.i(TAG, if (chunk < chunks) "Vorladen $pass: Block ${chunk + 1}/$chunks" else "Vorladen $pass: fertig")
        setProgressAsync(
            Data.Builder()
                .putInt(PROGRESS_STEP, phase)
                .putInt(PROGRESS_CHUNK, chunk)
                .putInt(PROGRESS_CHUNKS, chunks)
                .build(),
        )
    }

    companion object {
        private const val TAG = "BasemapPrefetch"
        const val PROGRESS_STEP = "step"
        const val PROGRESS_CHUNK = "chunk"
        const val PROGRESS_CHUNKS = "chunks"
    }
}
