package de.hecate.core.display

import de.hecate.core.Tuning

/**
 * Whether the recording is working, decided separately from what it knows.
 *
 * This is the other half of the split described in [RouteBelief]: the route line
 * may never look alarming, and this line is the only thing that may. It also
 * closes the gaps that made the old watch face quietly optimistic — every one of
 * these was invisible on the live screen before:
 *
 *  - GPS lost mid-workout. The fix state was rendered on the *control* page,
 *    which you have to swipe to.
 *  - A fix present but far too inaccurate to use. Samples were silently rejected,
 *    the matcher's position froze, and the only visible symptom was the
 *    ahead/behind number drifting worse — the app blamed the runner for the
 *    receiver.
 *  - Recording without location permission, a failed warm-up, a failed start.
 *    All of these set a message that only the *start* screen rendered, so the
 *    user learned about them after stopping.
 *
 * And the positive half, which matters just as much for trusting the thing:
 * [RECORDING] is a state that gets drawn, not an empty line. "Nothing is wrong"
 * and "the status line is broken" used to look identical.
 */
enum class HealthKind {
    /** Everything is landing on disk. Drawn as a quiet green marker. */
    RECORDING,

    /** Warming the sensors up before the exercise starts, so that the opening
     *  few hundred metres are not spent acquiring GNSS. */
    PREPARING,

    PAUSED,
    AUTO_PAUSED,

    /** US-7: off the route the deviation detector is watching. */
    OFF_ROUTE,

    /** No location permission: this recording is a stopwatch with a heart rate
     *  monitor, and says so for its whole duration. */
    NO_LOCATION_PERMISSION,

    /** Fixes are arriving but are too poor to use, or none has arrived for
     *  [Tuning.FIX_STALE_MS]. */
    POOR_FIX,

    /** Nothing usable for [Tuning.FIX_LOST_MS]. */
    NO_FIX,

    /** Nothing has reached the session file for [Tuning.NO_WRITE_MS] while the
     *  exercise is supposedly running. */
    NOT_WRITING,

    /** The matcher was switched off after repeated failures; recording continues
     *  (NFR-1 outranks the feature). */
    MATCHER_OFF,

    /** Something the recorder wants to say in words. */
    MESSAGE,
}

enum class HealthSeverity { OK, DEGRADED, FAILED }

/** Everything the decision needs, and nothing Android-shaped. */
data class RecordingHealthInput(
    val preparing: Boolean = false,
    val paused: Boolean = false,
    val autoPaused: Boolean = false,
    val hasLocationPermission: Boolean = true,
    /**
     * Wall-clock age of the last sample that passed `Sample.usable`.
     *
     * A session that has never had one supplies its own age instead, so that
     * "no fix for the whole workout" is reported as what it is rather than as a
     * missing value — a recording that started without GPS because the warm-up
     * timed out is exactly the case the user needs told. Null means no
     * information at all, which is never a reason to invent a complaint.
     */
    val fixAgeMs: Long? = null,
    /**
     * The receiver itself has said the fix is gone (searching, or no GNSS),
     * as opposed to samples merely not arriving.
     *
     * Only consulted around pauses: Health Services stops delivering locations
     * while an exercise is paused, so [fixAgeMs] grows there with a perfectly
     * good fix, and on its own is no evidence of anything.
     */
    val gpsSignalLost: Boolean = false,
    /**
     * Wall-clock time since the exercise last resumed from a pause, or null if
     * it has not been paused. Caps [fixAgeMs] (unless [gpsSignalLost]): the
     * silence during the pause was Health Services', not the receiver's, so
     * the first fix after resuming gets the same grace as any other.
     */
    val sinceResumeMs: Long? = null,
    /** Wall-clock time since anything was last written to the session file. */
    val sinceLastWriteMs: Long? = null,
    /** Reported accuracy of the most recent position, usable or not. */
    val accuracyM: Double? = null,
    val offRoute: Boolean = false,
    val matcherDisabled: Boolean = false,
    val message: String? = null,
)

data class RecordingHealth(
    val kind: HealthKind,
    val severity: HealthSeverity,
    /** Carried through so the display can say *how long* there has been no fix.
     *  "Kein GPS seit 1:20" is actionable in a way "Kein GPS" is not. */
    val fixAgeMs: Long = 0,
    val message: String? = null,
)

/**
 * Rank what is wrong by how much it changes what the user should do next, and
 * report only the top one — a wrist is not a log viewer.
 *
 * The order is deliberate: a recording that is not recording outranks everything
 * (it is the only failure that destroys data); a dead fix outranks a wrong turn
 * (the turn cannot be detected without the fix); a pause outranks fix quality
 * (standing still explains both); and the matcher going down ranks last, because
 * every metric except the route survives it.
 */
fun recordingHealth(input: RecordingHealthInput): RecordingHealth {
    val fixAgeMs = input.fixAgeMs?.let { age ->
        val sinceResume = input.sinceResumeMs
        if (sinceResume != null && !input.gpsSignalLost) minOf(age, sinceResume) else age
    }
    val fixAge = fixAgeMs ?: 0L

    // Nothing has reached the file while the exercise is supposedly running.
    // Never raised while paused or preparing, when there is legitimately nothing
    // to write.
    if (!input.paused && !input.preparing &&
        (input.sinceLastWriteMs ?: 0L) > Tuning.NO_WRITE_MS
    ) {
        return RecordingHealth(HealthKind.NOT_WRITING, HealthSeverity.FAILED)
    }

    if (input.message != null) {
        return RecordingHealth(HealthKind.MESSAGE, HealthSeverity.DEGRADED, fixAge, input.message)
    }

    if (!input.hasLocationPermission) {
        // Suppresses every fix-quality complaint below: there is nothing wrong
        // with the receiver, the app was simply never allowed to ask it.
        return RecordingHealth(HealthKind.NO_LOCATION_PERMISSION, HealthSeverity.DEGRADED)
    }

    // While paused (auto or manual), silence is expected — Health Services
    // withholds the locations it is still receiving — so fix age alone never
    // raises a complaint there; the receiver has to have said the fix is gone.
    val pausedSilence = input.paused && !input.gpsSignalLost
    if (!input.preparing && !pausedSilence && fixAgeMs != null && fixAge > Tuning.FIX_LOST_MS) {
        return RecordingHealth(HealthKind.NO_FIX, HealthSeverity.FAILED, fixAge)
    }

    if (input.offRoute) return RecordingHealth(HealthKind.OFF_ROUTE, HealthSeverity.FAILED, fixAge)

    if (input.paused) {
        return RecordingHealth(
            if (input.autoPaused) HealthKind.AUTO_PAUSED else HealthKind.PAUSED,
            HealthSeverity.DEGRADED,
            fixAge,
        )
    }

    if (input.preparing) return RecordingHealth(HealthKind.PREPARING, HealthSeverity.DEGRADED, fixAge)

    val poorAccuracy = (input.accuracyM ?: 0.0) > Tuning.MAX_USABLE_ACCURACY_M
    if (poorAccuracy || (fixAgeMs != null && fixAge > Tuning.FIX_STALE_MS)) {
        return RecordingHealth(HealthKind.POOR_FIX, HealthSeverity.DEGRADED, fixAge)
    }

    if (input.matcherDisabled) return RecordingHealth(HealthKind.MATCHER_OFF, HealthSeverity.DEGRADED, fixAge)

    return RecordingHealth(HealthKind.RECORDING, HealthSeverity.OK, fixAge)
}
