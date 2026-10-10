package de.hecate.wear.transfer

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import de.hecate.wear.AppGraph
import de.hecate.wear.Wire
import de.hecate.wear.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Ships session files to the phone (§5.3, US-2).
 *
 * The Data Layer is doing the hard part: a `DataClient` item is retried by the
 * platform and delivered whenever the phone comes back into range, so "a session
 * recorded with the phone switched off transfers when the phone returns" needs
 * no queue, no retry policy, and no network code of our own.
 *
 * Transfer is at-least-once. Ingest is idempotent on the session UUID, so a
 * redelivery costs nothing. The file is deleted only once the phone acks.
 */
class Uploader(
    private val context: Context,
    private val sessions: SessionStore,
) {
    private val mutex = Mutex()

    /** Offer every unacknowledged session to the phone. */
    suspend fun uploadPending() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val pending = sessions.unacked()
            AppGraph.recorder.update { it.copy(pendingUploads = pending.size) }
            for (file in pending) {
                runCatching { upload(file) }
                    .onFailure { Log.w(TAG, "Upload von ${file.name} fehlgeschlagen", it) }
            }
        }
    }

    private suspend fun upload(file: File) {
        if (!file.exists() || file.length() == 0L) return
        val id = sessions.sessionIdOf(file)

        val gz = ByteArrayOutputStream().also { out ->
            GZIPOutputStream(out).use { zip -> file.inputStream().use { it.copyTo(zip) } }
        }.toByteArray()

        val request = PutDataMapRequest.create(Wire.PATH_SESSION_PREFIX + id).apply {
            dataMap.putAsset(Wire.KEY_SESSION_ASSET, Asset.createFromBytes(gz))
            dataMap.putString(Wire.KEY_SESSION_ID, id)
            dataMap.putLong(Wire.KEY_STARTED_AT, file.lastModified())
            // The Data Layer suppresses an item whose bytes have not changed;
            // a growing attempt counter makes every retry a genuine change.
            dataMap.putLong(Wire.KEY_ATTEMPT, System.currentTimeMillis())
        }.asPutDataRequest().setUrgent()

        // Tasks.await rather than kotlinx-coroutines-play-services: one more
        // third-party dependency for one blocking call on an IO dispatcher is a
        // poor trade (NFR-8).
        Tasks.await(Wearable.getDataClient(context).putDataItem(request))
        Log.i(TAG, "Sitzung $id angeboten (${gz.size} B gzip)")
    }

    /** Called when the phone confirms the session is in its database (US-2). */
    fun onAck(sessionId: String) {
        sessions.markAcked(sessionId)
        AppGraph.recorder.update {
            it.copy(
                pendingUploads = sessions.unacked().size,
                // The confirmation the end-of-recording screen is waiting for.
                handoff = it.handoff
                    ?.takeIf { h -> h.sessionId == sessionId && !h.confirmed }
                    ?.copy(confirmed = true)
                    ?: it.handoff,
            )
        }
        // Drop the now-redundant data item so the Data Layer does not keep it.
        runCatching {
            Wearable.getDataClient(context).deleteDataItems(
                ("wear://*" + Wire.PATH_SESSION_PREFIX + sessionId).toUri(),
            )
        }
        Log.i(TAG, "Sitzung $sessionId bestätigt und gelöscht")
    }

    private companion object {
        const val TAG = "Uploader"
    }
}
