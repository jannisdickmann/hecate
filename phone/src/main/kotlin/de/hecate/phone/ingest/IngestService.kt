package de.hecate.phone.ingest

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import de.hecate.core.io.SessionCodec
import de.hecate.core.model.RecordedSession
import de.hecate.phone.AppGraph
import de.hecate.phone.Wire
import kotlinx.coroutines.runBlocking
import java.util.zip.GZIPInputStream

/**
 * §5.4: receives session assets from the watch, parses, inserts, acknowledges.
 *
 * The acknowledgement is the contract of US-2 — the watch deletes its copy only
 * once this service has said the session is in the database. A duplicate is
 * acknowledged too: the watch's copy is genuinely redundant either way.
 */
class IngestService : WearableListenerService() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            if (!path.startsWith(Wire.PATH_SESSION_PREFIX)) continue
            runCatching { handle(event) }
                .onFailure { Log.e(TAG, "Ingest von $path fehlgeschlagen", it) }
        }
    }

    private fun handle(event: DataEvent) {
        val map = DataMapItem.fromDataItem(event.dataItem).dataMap
        val sessionId = map.getString(Wire.KEY_SESSION_ID) ?: return
        val asset = map.getAsset(Wire.KEY_SESSION_ASSET) ?: return

        val bytes = Tasks.await(Wearable.getDataClient(this).getFdForAsset(asset))
            .inputStream.use { raw -> GZIPInputStream(raw).use { it.readBytes() } }

        val session: RecordedSession? = bytes.toString(Charsets.UTF_8)
            .lineSequence()
            .let { SessionCodec.decodeSession(it) }

        if (session == null) {
            Log.e(TAG, "Sitzung $sessionId ist unlesbar — wird nicht bestätigt")
            return
        }

        val result = runBlocking { AppGraph.repository.ingest(session) }
        when (result) {
            is SessionRepository.IngestResult.Stored,
            is SessionRepository.IngestResult.Duplicate,
            -> ack(sessionId)
            is SessionRepository.IngestResult.Rejected ->
                // Not acknowledged: the watch keeps its copy so the data is not
                // silently thrown away because of a bug here.
                Log.e(TAG, "Sitzung $sessionId abgelehnt: ${result.reason}")
        }
    }

    private fun ack(sessionId: String) {
        runCatching {
            val nodes: List<Node> = Tasks.await(Wearable.getNodeClient(this).connectedNodes)
            for (node in nodes) {
                Tasks.await(
                    Wearable.getMessageClient(this)
                        .sendMessage(node.id, Wire.PATH_ACK, sessionId.toByteArray(Charsets.UTF_8)),
                )
            }
            Log.i(TAG, "Sitzung $sessionId bestätigt")
        }.onFailure { Log.w(TAG, "Bestätigung für $sessionId fehlgeschlagen", it) }
    }

    private companion object {
        const val TAG = "IngestService"
    }
}
