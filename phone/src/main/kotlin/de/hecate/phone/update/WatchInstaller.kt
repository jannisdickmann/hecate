package de.hecate.phone.update

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import de.hecate.core.update.UrlReleaseClient
import de.hecate.phone.Wire
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Brings a watch release to the watch (A.35): the phone downloads the APK —
 * the watch has no network — and streams it over a Data Layer channel, where
 * the watch checks it and offers to install it.
 */
class WatchInstaller(
    private val context: Context,
    private val download: (String, File) -> Unit = ::download,
) {
    enum class Outcome { SENT, NO_WATCH, FAILED }

    fun send(apkUrl: String): Outcome {
        val apk = File(context.cacheDir, "watch-update.apk")
        try {
            download(apkUrl, apk)
            val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
            if (nodes.isEmpty()) return Outcome.NO_WATCH
            val channels = Wearable.getChannelClient(context)
            for (node in nodes) {
                val channel = Tasks.await(channels.openChannel(node.id, Wire.PATH_APK))
                try {
                    Tasks.await(channels.getOutputStream(channel)).use { out ->
                        apk.inputStream().use { it.copyTo(out) }
                    }
                } finally {
                    runCatching { channels.close(channel) }
                }
            }
            return Outcome.SENT
        } catch (e: Exception) {
            Log.w(TAG, "Update nicht an die Uhr gesendet", e)
            return Outcome.FAILED
        } finally {
            apk.delete()
        }
    }

    companion object {
        private const val TAG = "WatchInstaller"

        /** GitHub answers a release asset with a redirect to its storage host;
         *  HttpURLConnection follows it, both being https. */
        fun download(url: String, to: File) {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", UrlReleaseClient.USER_AGENT)
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                c.inputStream.use { input -> to.outputStream().use { input.copyTo(it) } }
            } finally {
                c.disconnect()
            }
        }
    }
}
