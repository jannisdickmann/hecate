package de.hecate.wear.recorder

import de.hecate.core.model.Sample
import de.hecate.core.model.SessionHeader

/**
 * A source of samples that is not a sensor (§6.5's emulator layer).
 *
 * The Wear emulator's synthetic provider emits one default route with identical
 * values every run, so the one thing it can never show is the matcher doing its
 * job. A feed replaces it: a stored session's samples arrive through the same
 * [RecorderService.onSample] path a real workout's do, and the matcher, the
 * metrics, the haptics and the notification all behave exactly as they would on
 * a wrist — because they are not told which of the two they are looking at.
 *
 * **Only the debug build has an implementation.** The receiver that arms one
 * lives in `src/debug`, so in a release APK nothing can call [PendingFeed.arm],
 * [PendingFeed.take] always returns null, and the recorder has no path to a
 * sample it did not measure.
 */
interface SampleFeed {

    /**
     * The header of the recording being replayed.
     *
     * The recorder takes the exercise type, the wall clock and the time zone from
     * here rather than from the picker: the matcher filters candidate routes by
     * type, and its start-of-session prior is conditioned on the day and hour
     * (§5.6). Replaying Tuesday's commute as a Sunday afternoon ride would ask
     * the matcher a different question than the one being tested.
     */
    val header: SessionHeader

    /** Short enough for a watch face. Shown while the replay runs. */
    val label: String

    /**
     * Emits every sample in order, pacing itself, and returns when the recording
     * is exhausted. Cancelling the calling coroutine ends the replay.
     */
    suspend fun drive(onSample: (Sample) -> Unit)

    /**
     * Stops and restarts the flow of samples, for the watch's own pause button.
     * A paused feed emits nothing and resumes where it left off.
     */
    fun setPaused(paused: Boolean)
}

/**
 * The one seam between the debug build's replay and the recorder.
 *
 * A field rather than an intent extra because a [SampleFeed] is an object with a
 * parsed session behind it, and because the recorder must be able to tell "this
 * start is a replay" before it opens a file or asks for a GNSS fix.
 */
object PendingFeed {

    @Volatile
    private var pending: SampleFeed? = null

    /** Called only from `src/debug`. */
    fun arm(feed: SampleFeed) {
        pending = feed
    }

    /** Hands over the armed feed, if any, and disarms. A second recording is a
     *  recording, not a second replay. */
    fun take(): SampleFeed? {
        val f = pending
        pending = null
        return f
    }
}
