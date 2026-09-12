package de.hecate.phone.ingest

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import de.hecate.core.io.SessionCodec
import de.hecate.core.model.RecordedSession
import de.hecate.phone.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.zip.GZIPInputStream

/**
 * Everything that turns a Data Layer item into a row, in one place so that both
 * ways into it behave identically.
 *
 * There are two ways in, and until now there was only one. [IngestService] gets
 * `onDataChanged` when an item arrives, which works right up until it does not:
 * a delivery that never happens leaves the item sitting in the Data Layer with
 * nothing on the phone ever looking at it again. The watch cannot fix that
 * either — it re-offers on its own triggers, and a re-offer only helps if the
 * event is delivered this time.
 *
 * So [sweep] reads what is already there. It costs one query at app start and it
 * is the only thing in the system that can recover a missed event.
 */
class DataLayerIngest(
    private val context: Context,
    private val repository: SessionRepository,
) {

    /**
     * Ingest every session item currently in the Data Layer.
     *
     * Ingest is idempotent on the session id, so an item that `onDataChanged`
     * already handled costs a lookup and nothing else. An item that was already
     * acknowledged is normally gone — the watch deletes it — so in the ordinary
     * case this finds nothing at all.
     */
    suspend fun sweep(): Int = withContext(Dispatchers.IO) {
        val items = runCatching {
            Tasks.await(
                // FILTER_PREFIX, not the default: the single-argument overload
                // matches the path literally, so a query for the prefix matches
                // the one item whose path *is* the prefix — which is none of them.
                Wearable.getDataClient(context).getDataItems(
                    ("wear://*" + Wire.PATH_SESSION_PREFIX).toUri(),
                    DataClient.FILTER_PREFIX,
                ),
            )
        }.getOrElse {
            Log.w(TAG, "Data Layer konnte nicht gelesen werden", it)
            return@withContext 0
        }

        var handled = 0
        items.use { buffer ->
            for (item in buffer) {
                if (item.uri.path?.startsWith(Wire.PATH_SESSION_PREFIX) != true) continue
                if (runCatching { ingest(item) }.getOrDefault(false)) handled++
            }
        }
        if (handled > 0) Log.i(TAG, "$handled liegengebliebene Sitzung(en) nachgeholt")
        handled
    }

    /**
     * One item. Returns whether it was dealt with — stored, recognised as a
     * duplicate, or refused for good.
     *
     * A refusal that is *not* permanent leaves the item and the watch's copy
     * exactly where they are. That is the point: a bug on this side must cost a
     * retry, never a recording.
     */
    suspend fun ingest(item: DataItem): Boolean {
        val map = DataMapItem.fromDataItem(item).dataMap
        val sessionId = map.getString(Wire.KEY_SESSION_ID) ?: run {
            Log.w(TAG, "Datenobjekt ohne Sitzungs-ID: ${item.uri.path}")
            return false
        }
        val asset = map.getAsset(Wire.KEY_SESSION_ASSET) ?: run {
            Log.w(TAG, "Sitzung $sessionId ohne Nutzdaten")
            return false
        }

        val bytes = Tasks.await(Wearable.getDataClient(context).getFdForAsset(asset))
            .inputStream.use { raw -> GZIPInputStream(raw).use { it.readBytes() } }

        val session: RecordedSession? = bytes.toString(Charsets.UTF_8)
            .lineSequence()
            .let { SessionCodec.decodeSession(it) }

        if (session == null) {
            Log.e(TAG, "Sitzung $sessionId ist unlesbar — wird nicht bestätigt")
            return false
        }

        return when (val result = repository.ingest(session)) {
            is SessionRepository.IngestResult.Stored,
            is SessionRepository.IngestResult.Duplicate,
            -> { ack(sessionId); true }

            is SessionRepository.IngestResult.Rejected -> {
                Log.e(TAG, "Sitzung $sessionId abgelehnt: ${result.reason}")
                // Permanent means the payload can never become storable — a
                // recording with no positions in it. Acknowledging it is what
                // stops the watch keeping it and offering it again after every
                // reboot, for ever, which is what happened before this existed.
                if (result.permanent) ack(sessionId)
                result.permanent
            }
        }
    }

    private fun ack(sessionId: String) {
        runCatching {
            val nodes: List<Node> = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
            for (node in nodes) {
                Tasks.await(
                    Wearable.getMessageClient(context)
                        .sendMessage(node.id, Wire.PATH_ACK, sessionId.toByteArray(Charsets.UTF_8)),
                )
            }
            Log.i(TAG, "Sitzung $sessionId bestätigt")
        }.onFailure { Log.w(TAG, "Bestätigung für $sessionId fehlgeschlagen", it) }
    }

    private companion object {
        const val TAG = "DataLayerIngest"
    }
}
