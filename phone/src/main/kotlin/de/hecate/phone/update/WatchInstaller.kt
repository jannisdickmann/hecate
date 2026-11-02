package de.hecate.phone.update

import android.content.Context
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import de.hecate.core.update.UrlReleaseClient
import de.hecate.phone.Wire
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Brings a watch release to the watch (A.35): the phone downloads the APK —
 * the watch has no network — and streams it over a Data Layer channel, where
 * the watch checks it and offers to install it.
 *
 * Only watches advertising [Wire.CAPABILITY_APK] are sent to. A watch build
 * from before A.35 has no reader for the channel: the write fills the buffer
 * and blocks until the Data Layer gives up, minutes later — the "Senden
 * fehlgeschlagen" of the first attempts. Such a watch is reported as
 * [Outcome.WATCH_TOO_OLD] at once, without downloading anything.
 *
 * The transfer counts as done only when the watch closes the channel with
 * [Wire.APK_ACCEPTED]. Closing it from this side right after the last write
 * could drop bytes still in flight, so this side only closes its output and
 * waits.
 */
class WatchInstaller(
    private val context: Context,
    private val download: (String, File) -> Unit = ::download,
) {
    enum class Outcome { SENT, NO_WATCH, WATCH_TOO_OLD, REJECTED, FAILED }

    fun send(apkUrl: String): Outcome {
        val apk = File(context.cacheDir, "watch-update.apk")
        try {
            val nodes = receivers()
            if (nodes.isEmpty()) {
                val connected = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                return if (connected.isEmpty()) Outcome.NO_WATCH else Outcome.WATCH_TOO_OLD
            }
            download(apkUrl, apk)
            val outcomes = nodes.map { sendTo(it, apk) }
            return when {
                outcomes.all { it == Outcome.SENT } -> Outcome.SENT
                Outcome.REJECTED in outcomes -> Outcome.REJECTED
                else -> Outcome.FAILED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update nicht an die Uhr gesendet", e)
            return Outcome.FAILED
        } finally {
            apk.delete()
        }
    }

    private fun receivers(): Set<Node> =
        Tasks.await(
            Wearable.getCapabilityClient(context)
                .getCapability(Wire.CAPABILITY_APK, CapabilityClient.FILTER_REACHABLE),
        ).nodes

    private fun sendTo(node: Node, apk: File): Outcome {
        val channels = Wearable.getChannelClient(context)
        val channel = Tasks.await(channels.openChannel(node.id, Wire.PATH_APK))
        val closed = CountDownLatch(1)
        var answer: Int? = null
        val callback = object : ChannelClient.ChannelCallback() {
            override fun onChannelClosed(c: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
                if (c.nodeId != channel.nodeId || c.path != channel.path) return
                if (closeReason == CLOSE_REASON_REMOTE_CLOSE) answer = appSpecificErrorCode
                closed.countDown()
            }
        }
        Tasks.await(channels.registerChannelCallback(channel, callback))
        try {
            Tasks.await(channels.getOutputStream(channel)).use { out ->
                apk.inputStream().use { it.copyTo(out) }
            }
            // The watch verifies a ~30 MB archive before it answers.
            if (!closed.await(ANSWER_TIMEOUT_S, TimeUnit.SECONDS)) {
                Log.w(TAG, "Keine Antwort von ${node.displayName}")
                return Outcome.FAILED
            }
            return when (answer) {
                Wire.APK_ACCEPTED -> Outcome.SENT
                Wire.APK_REJECTED -> Outcome.REJECTED
                else -> Outcome.FAILED
            }
        } finally {
            runCatching { channels.unregisterChannelCallback(channel, callback) }
            if (closed.count > 0) runCatching { channels.close(channel) }
        }
    }

    companion object {
        private const val TAG = "WatchInstaller"
        private const val ANSWER_TIMEOUT_S = 120L

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
