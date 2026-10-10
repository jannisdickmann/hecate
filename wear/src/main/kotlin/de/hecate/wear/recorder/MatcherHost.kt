package de.hecate.wear.recorder

import android.util.Log
import de.hecate.core.Tuning
import de.hecate.core.matcher.RouteMatcher
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.MatchState
import de.hecate.core.model.RouteBundle
import de.hecate.core.model.Sample

/**
 * Wraps the pure matcher in the belt and braces recording requires.
 *
 * US-1 is explicit: recording continues if the matcher throws, and matcher
 * failure is never fatal. Every call is guarded, and after
 * [Tuning.MATCHER_FAILURE_LIMIT] consecutive failures the matcher is switched off
 * for the rest of the session and the recorder carries on as if there were no
 * route bundle at all.
 */
class MatcherHost(
    bundle: RouteBundle,
    exerciseType: ExerciseType,
    startedAtUtcMs: Long,
    tzOffsetMinutes: Int,
    /** Called for each new match log entry so the recorder can persist it (US-10). */
    private val onLog: (MatchLogEntry) -> Unit,
) {
    // The log is streamed straight to the session file rather than accumulated:
    // an hour of 1 Hz entries in memory would be pointless when the file is right
    // there, and a crash would take the log with it.
    private var matcher: RouteMatcher? = runCatching {
        RouteMatcher(bundle, exerciseType, startedAtUtcMs, tzOffsetMinutes) { entry ->
            runCatching { onLog(entry) }
        }
    }.onFailure { Log.e(TAG, "Matcher konnte nicht erstellt werden", it) }.getOrNull()

    private var failures = 0

    /** True once the matcher is gone: never built, or switched off after
     *  too many failures. Both leave [matcher] null, so that is the test. */
    val disabled: Boolean get() = matcher == null

    /** Returns the new belief, or null when the matcher is unavailable. */
    fun update(sample: Sample): MatchState? {
        val m = matcher ?: return null
        return try {
            m.update(sample).also { failures = 0 }
        } catch (t: Throwable) {
            failures++
            Log.e(TAG, "Matcher-Fehler #$failures", t)
            if (failures >= Tuning.MATCHER_FAILURE_LIMIT) {
                Log.e(TAG, "Matcher wird für diese Aufzeichnung deaktiviert")
                matcher = null
            }
            null
        }
    }

    private companion object {
        const val TAG = "MatcherHost"
    }
}
