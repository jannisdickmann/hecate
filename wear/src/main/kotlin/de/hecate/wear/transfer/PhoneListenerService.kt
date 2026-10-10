package de.hecate.wear.transfer

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import de.hecate.wear.AppGraph
import de.hecate.wear.Wire
import kotlinx.coroutines.runBlocking

/**
 * Receives everything the phone sends: route bundles (data items, because they
 * must arrive eventually) and ingest acknowledgements (messages, because a lost
 * one is repaired by the next upload attempt).
 */
class PhoneListenerService : WearableListenerService() {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        for (event in events) {
            if (event.type != DataEvent.TYPE_CHANGED) continue
            val path = event.dataItem.uri.path ?: continue
            if (path != Wire.PATH_BUNDLE) continue
            runCatching { storeBundle(event) }
                .onFailure { Log.e(TAG, "Routen-Bundle konnte nicht gespeichert werden", it) }
        }
    }

    private fun storeBundle(event: DataEvent) {
        val map = DataMapItem.fromDataItem(event.dataItem).dataMap
        val asset = map.getAsset(Wire.KEY_BUNDLE_ASSET) ?: return
        val version = map.getLong(Wire.KEY_BUNDLE_VERSION)
        val client = Wearable.getDataClient(this)
        val bytes = Tasks.await(client.getFdForAsset(asset))
            .inputStream.use { it.readBytes() }
        AppGraph.bundles.store(bytes)
        AppGraph.recorder.update { it.copy(bundleVersion = version) }
        Log.i(TAG, "Routen-Bundle v$version empfangen (${bytes.size} B)")
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            Wire.PATH_ACK -> {
                val id = String(event.data, Charsets.UTF_8)
                AppGraph.uploader.onAck(id)
            }
            Wire.PATH_RESEND -> runBlocking { AppGraph.uploader.uploadPending() }
            else -> Unit
        }
    }

    private companion object {
        const val TAG = "PhoneListener"
    }
}
