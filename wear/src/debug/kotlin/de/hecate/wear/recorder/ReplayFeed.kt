package de.hecate.wear.recorder

import android.content.Context
import android.util.Log
import de.hecate.core.io.SessionCodec
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.Sample
import de.hecate.core.model.SessionHeader
import kotlinx.coroutines.delay
import java.io.File

/**
 * A stored session, played back into the recorder at a chosen speed.
 *
 * Debug builds only; see [SampleFeed] for why the seam exists and where the
 * release build's half of it is.
 *
 * **Speed changes nothing but the wait.** [de.hecate.core.matcher.RouteMatcher]
 * never reads a clock and its hold windows are counted in samples, not seconds
 * (`COMMIT_HOLD_SAMPLES`), so a replay at ×10 commits on the same sample as a
 * replay at ×1 and as the watch did on the day. What the speed buys is not
 * having to stand in front of an emulator for forty-five minutes to watch a
 * route be found after four.
 *
 * The one thing that is *not* faithful is the wall-clock arrival time on the
 * Live page: `ETA` is "now plus the seconds remaining", and at ×10 the remaining
 * seconds are real ones. The route name, the ahead/behind figure and the
 * deviation alert are all exactly what the wrist would have shown.
 */
class ReplayFeed private constructor(
    private val session: RecordedSession,
    private val speed: Double,
    private val source: File,
) : SampleFeed {

    override val header: SessionHeader get() = session.header

    override val label: String = "Wiedergabe ×${Speed.text(speed)}"

    @Volatile
    private var paused = false

    override fun setPaused(paused: Boolean) {
        this.paused = paused
    }

    override suspend fun drive(onSample: (Sample) -> Unit) {
        Log.i(
            TAG,
            "Wiedergabe von ${source.name}: ${session.samples.size} Messpunkte, " +
                "${header.exerciseType.label}, ×${Speed.text(speed)}",
        )
        // Paced off tOffsetMs, which is the monotonic offset the watch recorded
        // (NFR-6). A gap in the recording — a GPS dropout, a long pause — is
        // therefore replayed as a gap, divided by the speed. That is deliberate:
        // a dropout the matcher has to survive is one of the things worth
        // watching it survive, and smoothing it away here would hide it.
        var previousMs = session.samples.firstOrNull()?.tOffsetMs ?: 0L
        for (sample in session.samples) {
            val waitMs = ((sample.tOffsetMs - previousMs) / speed).toLong()
            if (waitMs > 0) delay(waitMs)
            // Held here rather than by not advancing the clock, so that resuming
            // carries on from this sample instead of racing through the ones the
            // pause was spent on.
            while (paused) delay(PAUSE_POLL_MS)
            previousMs = sample.tOffsetMs
            onSample(sample)
        }
        Log.i(TAG, "Wiedergabe von ${source.name} zu Ende")
    }

    private object Speed {
        /** "10" rather than "10.0", but "0.5" when someone asks for half speed. */
        fun text(v: Double): String =
            if (v == v.toLong().toDouble()) v.toLong().toString() else "%.1f".format(v)
    }

    companion object {
        private const val TAG = "ReplayFeed"

        /** How often a paused replay asks whether it may carry on. Far below the
         *  time it takes to press the button again. */
        private const val PAUSE_POLL_MS = 100L

        /** Fast enough that a commute commits while you are still looking at it,
         *  slow enough to read the numbers changing. */
        const val DEFAULT_SPEED = 10.0

        /** Below 0.1 a replay is indistinguishable from a hang; above 600 the
         *  delays round to zero and the whole session arrives in one burst, which
         *  is what `selfcheck` is for and not what this is for. */
        private val SPEED_RANGE = 0.1..600.0

        /** Where `tools/emu replay` puts the session, inside the app's own
         *  storage: a broadcast names a file in here and cannot name a path. */
        fun dir(context: Context): File = File(context.filesDir, "replay")

        /**
         * Reads a session file, or explains into the log why it will not be
         * replayed. Null is always a bad file, never a bad session — a recording
         * that never matched anything is a perfectly good thing to replay.
         */
        fun of(file: File, speed: Double): ReplayFeed? {
            if (!file.isFile) {
                Log.e(TAG, "Keine solche Datei: $file — tools/emu replay legt sie dort ab")
                return null
            }
            val session = runCatching {
                file.bufferedReader().useLines { SessionCodec.decodeSession(it) }
            }.onFailure { Log.e(TAG, "Sitzungsdatei unlesbar: $file", it) }.getOrNull()
            if (session == null) {
                Log.e(TAG, "Keine Sitzungsdatei (kein Header): $file")
                return null
            }
            if (session.samples.size < 2) {
                Log.e(TAG, "Sitzung ohne Messpunkte: $file")
                return null
            }
            return ReplayFeed(session, speed.coerceIn(SPEED_RANGE), file)
        }
    }
}
