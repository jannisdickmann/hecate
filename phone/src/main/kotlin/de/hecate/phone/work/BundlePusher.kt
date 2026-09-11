package de.hecate.phone.work

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import de.hecate.core.io.BundleCodec
import de.hecate.core.model.RouteBundle
import de.hecate.phone.Wire
import java.io.ByteArrayOutputStream

/**
 * Pushes the route bundle to the watch (§5.1: one-way, the phone is the sole
 * source of truth for routes).
 *
 * A data item rather than a message, so the watch picks it up whenever it next
 * comes into range without anybody retrying by hand.
 */
class BundlePusher(private val context: Context) {

    fun push(bundle: RouteBundle) {
        val bytes = ByteArrayOutputStream().also { BundleCodec.writeGzip(bundle, it) }.toByteArray()
        val request = PutDataMapRequest.create(Wire.PATH_BUNDLE).apply {
            dataMap.putAsset(Wire.KEY_BUNDLE_ASSET, Asset.createFromBytes(bytes))
            dataMap.putLong(Wire.KEY_BUNDLE_VERSION, bundle.version)
        }.asPutDataRequest().setUrgent()

        runCatching { Tasks.await(Wearable.getDataClient(context).putDataItem(request)) }
            .onSuccess { Log.i(TAG, "Bundle v${bundle.version} gesendet (${bytes.size} B, ${bundle.routes.size} Routen)") }
            .onFailure { Log.w(TAG, "Bundle konnte nicht gesendet werden", it) }
    }

    private companion object {
        const val TAG = "BundlePusher"
    }
}
