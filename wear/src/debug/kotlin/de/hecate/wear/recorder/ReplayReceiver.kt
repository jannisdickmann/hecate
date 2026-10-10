package de.hecate.wear.recorder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.hecate.wear.AppGraph
import java.io.File

/**
 * The adb entry point for a replay. Debug builds only — this class and the
 * manifest entry that declares it both live in `src/debug`, so a release APK
 * has no way to feed the recorder a sample it did not measure.
 *
 * ```
 * tools/emu replay fixtures/commute.jsonl 10
 * tools/emu replay --archive backup.zip a3f91b2c
 * tools/emu replay --stop
 * ```
 *
 * which is this, with the file copied into place first:
 *
 * ```
 * adb shell am broadcast -n de.hecate/de.hecate.wear.recorder.ReplayReceiver \
 *     -a de.hecate.wear.REPLAY --es file current.jsonl --ef speed 10
 * ```
 *
 * An explicit component rather than a bare action: an implicit broadcast does
 * not reach a manifest receiver at all on this platform version, and naming the
 * component also says out loud which app is being driven.
 */
class ReplayReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_REPLAY -> begin(context, intent)
            ACTION_REPLAY_STOP -> RecorderService.command(context, RecorderService.ACTION_STOP)
            // The phone's acknowledgement, for emulators that have no paired
            // phone to send one: confirms the session the end-of-recording
            // screen is waiting for, through the same path a real ack takes.
            ACTION_FAKE_ACK -> AppGraph.recorder.state.value.handoff?.let {
                AppGraph.uploader.onAck(it.sessionId)
            }
            else -> Log.w(TAG, "Unbekannte Aktion: ${intent.action}")
        }
    }

    private fun begin(context: Context, intent: Intent) {
        // A file name, never a path: whatever the broadcast says is reduced to its
        // last segment and looked up inside the app's own storage, so this cannot
        // be pointed at another app's data or at /sdcard.
        val name = File(intent.getStringExtra(EXTRA_FILE) ?: DEFAULT_FILE).name
        val speed = intent.getFloatExtra(EXTRA_SPEED, ReplayFeed.DEFAULT_SPEED.toFloat()).toDouble()

        // Reading the session is a whole file of JSON, one line per sample. For a
        // commute that is a few hundred milliseconds on the main thread; for a
        // four-hour ride it is not, and onReceive runs on the main thread. So it
        // happens on another one, with goAsync holding the receiver open until it
        // is done.
        val pending = goAsync()
        Thread {
            try {
                val feed = ReplayFeed.of(File(ReplayFeed.dir(context), name), speed)
                if (feed != null) {
                    PendingFeed.arm(feed)
                    // The exercise type comes from the recording, not from a
                    // picker, and the recorder reads it off the feed's header
                    // again for the matcher. It is passed here so the ongoing
                    // notification says the right sport from the first frame
                    // rather than correcting itself a moment later.
                    try {
                        RecorderService.start(context, feed.header.exerciseType)
                    } catch (t: Throwable) {
                        // A background app may not start a foreground service,
                        // which is why tools/emu brings this one to the front
                        // first. The feed stays armed either way, so starting any
                        // exercise on the watch runs the replay instead of
                        // recording.
                        Log.w(TAG, "Dienst nicht startbar; Wiedergabe bleibt scharf — auf der Uhr starten", t)
                    }
                }
            } finally {
                pending.finish()
            }
        }.start()
    }

    private companion object {
        const val TAG = "ReplayReceiver"

        const val ACTION_REPLAY = "de.hecate.wear.REPLAY"
        const val ACTION_REPLAY_STOP = "de.hecate.wear.REPLAY_STOP"
        const val ACTION_FAKE_ACK = "de.hecate.wear.FAKE_ACK"
        const val EXTRA_FILE = "file"
        const val EXTRA_SPEED = "speed"

        /** What `tools/emu replay` calls whatever it last pushed. */
        const val DEFAULT_FILE = "current.jsonl"
    }
}
