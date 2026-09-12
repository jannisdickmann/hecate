package de.hecate.phone.ingest

import android.util.Log
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import de.hecate.phone.AppGraph
import de.hecate.phone.Wire
import kotlinx.coroutines.runBlocking

/**
 * §5.4: receives session assets from the watch, parses, inserts, acknowledges.
 *
 * The acknowledgement is the contract of US-2 — the watch deletes its copy only
 * once this service has said the session is in the database. A duplicate is
 * acknowledged too: the watch's copy is genuinely redundant either way.
 *
 * The work itself is [DataLayerIngest]'s, because this is not the only way in.
 * An `onDataChanged` that never arrives used to mean an item nothing on this
 * phone would ever look at again; the same class sweeps what is already there
 * when the app starts.
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
            runCatching { runBlocking { AppGraph.dataLayer.ingest(event.dataItem) } }
                .onFailure { Log.e(TAG, "Ingest von $path fehlgeschlagen", it) }
        }
    }

    private companion object {
        const val TAG = "IngestService"
    }
}
