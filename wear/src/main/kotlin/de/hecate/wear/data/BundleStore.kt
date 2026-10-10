package de.hecate.wear.data

import android.content.Context
import android.util.Log
import de.hecate.core.io.BundleCodec
import de.hecate.core.model.RouteBundle
import java.io.File

/**
 * Caches the latest route bundle received from the phone.
 *
 * Read-only from the watch's point of view (§5.1). If there is no bundle — first
 * install, or the phone has never had enough history — the watch simply says
 * "Noch keine Routen", which is the honest answer and not an error state.
 */
class BundleStore(context: Context) {

    private val file = File(context.filesDir, "route-bundle.json.gz")

    @Volatile
    private var cached: RouteBundle? = null

    fun load(): RouteBundle {
        cached?.let { return it }
        val loaded = if (!file.exists()) {
            RouteBundle.EMPTY
        } else {
            runCatching { file.inputStream().use { BundleCodec.readGzip(it) } }
                .onFailure { Log.w(TAG, "Routen-Bundle unlesbar, wird ignoriert", it) }
                .getOrDefault(RouteBundle.EMPTY)
        }
        cached = loaded
        return loaded
    }

    /** Replace the cached bundle. Written to a temporary file and renamed so a
     *  power loss mid-write cannot leave a half-bundle behind. */
    fun store(bytes: ByteArray) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
        cached = null
        load()
        Log.i(TAG, "Neues Routen-Bundle gespeichert: Version ${cached?.version}")
    }

    private companion object {
        const val TAG = "BundleStore"
    }
}
