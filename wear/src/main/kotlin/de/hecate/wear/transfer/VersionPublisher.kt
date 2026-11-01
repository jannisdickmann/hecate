package de.hecate.wear.transfer

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import de.hecate.wear.AppVersion
import de.hecate.wear.Wire
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tells the phone which build this watch runs (A.34). The watch has no network
 * (NFR-7), so it cannot ask GitHub for a newer release itself; the phone asks
 * for both and needs this to know whether the watch is behind.
 *
 * A data item rather than a message: it must reach a phone that is out of range
 * when the watch app starts, and the phone reads it whenever it checks, not only
 * when it changes. Putting identical bytes again is a no-op for the Data Layer,
 * so publishing on every process start costs nothing after the first.
 */
object VersionPublisher {
    fun publish(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val request = PutDataMapRequest.create(Wire.PATH_WATCH_VERSION).apply {
                    dataMap.putLong(Wire.KEY_VERSION_CODE, AppVersion.code(app))
                    dataMap.putString(Wire.KEY_VERSION_NAME, AppVersion.name(app))
                }.asPutDataRequest()
                Tasks.await(Wearable.getDataClient(app).putDataItem(request))
            }.onFailure { Log.w(TAG, "Version konnte nicht gemeldet werden", it) }
        }
    }

    private const val TAG = "VersionPublisher"
}
