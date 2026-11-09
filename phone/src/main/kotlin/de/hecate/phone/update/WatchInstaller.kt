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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

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
 *
 * ~30 MB over Bluetooth takes minutes (the first real transfer: about four
 * and a half), so every step reports its progress, and a send that moves no
 * byte for [STALL_S] is given up rather than left spinning.
 */
class WatchInstaller(
    private val context: Context,
    private val download: (String, File, (Long, Long) -> Unit) -> Unit = ::download,
) {
    enum class Outcome { SENT, NO_WATCH, WATCH_TOO_OLD, REJECTED, FAILED }

    enum class Step { DOWNLOADING, SENDING, CHECKING }

    /** [percent] is null while the size is unknown or the step has no measure. */
    fun interface Progress {
        fun on(step: Step, percent: Int?)
    }

    fun send(apkUrl: String, progress: Progress = Progress { _, _ -> }): Outcome {
        val apk = File(context.cacheDir, "watch-update.apk")
        try {
            val nodes = receivers()
            if (nodes.isEmpty()) {
                val connected = Tasks.await(Wearable.getNodeClient(context).connectedNodes, CALL_TIMEOUT_S, TimeUnit.SECONDS)
                return if (connected.isEmpty()) Outcome.NO_WATCH else Outcome.WATCH_TOO_OLD
            }
            progress.on(Step.DOWNLOADING, null)
            download(apkUrl, apk) { done, total -> progress.on(Step.DOWNLOADING, percent(done, total)) }
            Log.i(TAG, "APK geladen (${apk.length()} B), an ${nodes.size} Uhr(en)")
            val outcomes = nodes.map { sendTo(it, apk, progress) }
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
            CALL_TIMEOUT_S,
            TimeUnit.SECONDS,
        ).nodes

    private fun sendTo(node: Node, apk: File, progress: Progress): Outcome {
        val channels = Wearable.getChannelClient(context)
        val channel = Tasks.await(channels.openChannel(node.id, Wire.PATH_APK), CALL_TIMEOUT_S, TimeUnit.SECONDS)
        val closed = CountDownLatch(1)
        var answer: Int? = null
        val callback = object : ChannelClient.ChannelCallback() {
            override fun onChannelClosed(c: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
                if (c.nodeId != channel.nodeId || c.path != channel.path) return
                if (closeReason == CLOSE_REASON_REMOTE_CLOSE) answer = appSpecificErrorCode
                closed.countDown()
            }
        }
        Tasks.await(channels.registerChannelCallback(channel, callback), CALL_TIMEOUT_S, TimeUnit.SECONDS)
        // A write blocks while the watch does not read; closing the channel
        // from here is the only way to free it. Checked every few seconds.
        val lastMove = AtomicLong(System.nanoTime())
        val stalled = AtomicBoolean(false)
        val watchdog = Executors.newSingleThreadScheduledExecutor()
        watchdog.scheduleWithFixedDelay({
            if (System.nanoTime() - lastMove.get() > TimeUnit.SECONDS.toNanos(STALL_S)) {
                stalled.set(true)
                runCatching { channels.close(channel) }
            }
        }, STALL_S, 5, TimeUnit.SECONDS)
        try {
            val started = System.nanoTime()
            val total = apk.length()
            progress.on(Step.SENDING, 0)
            Tasks.await(channels.getOutputStream(channel), CALL_TIMEOUT_S, TimeUnit.SECONDS).use { out ->
                apk.inputStream().use { input ->
                    val buffer = ByteArray(CHUNK)
                    var sent = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        sent += n
                        lastMove.set(System.nanoTime())
                        val p = percent(sent, total)
                        if (p != null && p != shown) {
                            shown = p
                            progress.on(Step.SENDING, p)
                        }
                    }
                }
            }
            watchdog.shutdownNow()
            Log.i(TAG, "An ${node.displayName} gesendet in ${TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started)} s")
            progress.on(Step.CHECKING, null)
            // The watch verifies a ~30 MB archive before it answers.
            if (!closed.await(ANSWER_TIMEOUT_S, TimeUnit.SECONDS)) {
                Log.w(TAG, "Keine Antwort von ${node.displayName}")
                return Outcome.FAILED
            }
            Log.i(TAG, "Antwort von ${node.displayName}: $answer")
            return when (answer) {
                Wire.APK_ACCEPTED -> Outcome.SENT
                Wire.APK_REJECTED -> Outcome.REJECTED
                else -> Outcome.FAILED
            }
        } catch (e: java.io.IOException) {
            if (stalled.get()) Log.w(TAG, "Keine Bewegung seit $STALL_S s, abgebrochen")
            throw e
        } finally {
            watchdog.shutdownNow()
            runCatching { channels.unregisterChannelCallback(channel, callback) }
            if (closed.count > 0) runCatching { channels.close(channel) }
        }
    }

    companion object {
        private const val TAG = "WatchInstaller"
        private const val ANSWER_TIMEOUT_S = 120L
        private const val CALL_TIMEOUT_S = 30L
        private const val STALL_S = 60L
        private const val CHUNK = 64 * 1024

        /** 0–100, or null when the size is unknown. */
        fun percent(done: Long, total: Long): Int? =
            if (total <= 0) null else (done * 100 / total).toInt().coerceIn(0, 100)

        /** GitHub answers a release asset with a redirect to its storage host;
         *  HttpURLConnection follows it, both being https. */
        fun download(url: String, to: File, progress: (Long, Long) -> Unit = { _, _ -> }) {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 20_000
                c.readTimeout = 60_000
                c.instanceFollowRedirects = true
                c.setRequestProperty("User-Agent", UrlReleaseClient.USER_AGENT)
                if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                val total = c.contentLengthLong
                c.inputStream.use { input ->
                    to.outputStream().use { out ->
                        val buffer = ByteArray(CHUNK)
                        var done = 0L
                        var shown = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            out.write(buffer, 0, n)
                            done += n
                            val p = percent(done, total)
                            if (p != null && p != shown) {
                                shown = p
                                progress(done, total)
                            }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
        }
    }
}
