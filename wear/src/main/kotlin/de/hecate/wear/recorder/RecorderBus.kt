package de.hecate.wear.recorder

import de.hecate.core.display.RouteBelief
import de.hecate.core.display.RouteBeliefKind
import de.hecate.core.model.ExerciseType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The recorder state machine of §5.3.
 *
 * `PREPARING` exists so that `startExercise()` is only called once there is a
 * GNSS fix: the first few hundred metres are exactly the segment the matcher
 * needs, and starting without a fix throws them away.
 */
enum class RecorderPhase {
    IDLE,
    PREPARING,
    ACTIVE,
    PAUSED,
    ENDING,
}

/**
 * Everything the watch pages render, in two clearly separated halves.
 *
 * **What the matcher believes** ([belief] and the route-relative numbers under
 * it) and **whether the recording is working** (the fix, write and permission
 * fields) are kept apart on purpose: they used to share a status line and a
 * palette, so a healthy matcher weighing two routes looked exactly like a watch
 * that could not get a fix. See
 * [de.hecate.core.display.RouteBelief] and
 * [de.hecate.core.display.recordingHealth], which turn each half into one line.
 */
data class RecorderUiState(
    val phase: RecorderPhase = RecorderPhase.IDLE,
    val exerciseType: ExerciseType = ExerciseType.RUNNING,
    val sessionId: String? = null,
    /** Wall clock at which this session started, and the fallback the health line
     *  measures a missing fix against: a workout that never got one has been
     *  without it for its whole length, which is the honest figure to show. */
    val startedAtUtcMs: Long = 0,

    // Metrics
    val activeDurationMs: Long = 0,
    val distanceM: Double = 0.0,
    val heartRateBpm: Int? = null,
    /** Current speed as Health Services reports it. Null until the first
     *  reading; the display then falls back to the session average and says so
     *  rather than passing one off as the other. */
    val speedKmh: Double? = null,

    // What the matcher believes
    val belief: RouteBelief = RouteBelief(RouteBeliefKind.COMPARING),
    val remainingM: Double? = null,
    /**
     * Arrival as an absolute wall-clock instant, not "seconds from now".
     *
     * The seconds version was turned into a clock time at every recomposition,
     * so an arrival worked out once and then frozen — after the matcher was
     * disabled, say — drifted a minute later for every minute the user kept
     * looking at it. An instant cannot do that.
     */
    val arrivalAtUtcMs: Long? = null,
    val deltaVsMedianS: Double? = null,
    /** Full-scale deflection of the ahead/behind bar [s], from the route's own
     *  quartiles. Null when no route is being followed. */
    val deltaScaleS: Double? = null,
    /** How far along the route, 0..1, for the progress bar. */
    val progressFraction: Double? = null,
    /** The projected position has not been refreshed recently, so everything
     *  route-relative above is the last honest value rather than a current one. */
    val positionStale: Boolean = false,
    val offRoute: Boolean = false,
    val matcherDisabled: Boolean = false,

    // Health
    val gpsReady: Boolean = false,
    /** Health Services has explicitly reported the fix gone (ACQUIRING or
     *  NO_GNSS) since it was last acquired. Unlike `!gpsReady`, never set merely
     *  because nothing has been reported, e.g. while auto-paused. */
    val gpsSignalLost: Boolean = false,
    val heartRateAvailable: Boolean = false,
    val locationPermission: Boolean = true,
    /** Wall clock of the last sample that was good enough to use, and of the
     *  last write to the session file. Zero means "not yet in this session".
     *  Both are wall-clock rather than availability callbacks: a fix that
     *  quietly degrades to 200 m of error never produces a callback. */
    val lastFixAtMs: Long = 0,
    val lastWriteAtMs: Long = 0,
    val lastAccuracyM: Double? = null,
    val autoPaused: Boolean = false,

    // Housekeeping
    val pendingUploads: Int = 0,
    val bundleVersion: Long = 0,
    val message: String? = null,
    /** Set while a stored session is being replayed instead of recorded (§6.5).
     *  Always null in a release build, and shown on the Live page so that a
     *  screenshot of a replay can never be mistaken for one of a workout. */
    val replayLabel: String? = null,
    /** When [message] was set, so the live screen can retire it once it has been
     *  on the wrist long enough to have been read. */
    val messageAtMs: Long = 0,
) {
    val isRecording: Boolean get() = phase == RecorderPhase.ACTIVE || phase == RecorderPhase.PAUSED
}

/**
 * The single place the service publishes to and the UI reads from.
 *
 * A plain StateFlow in the app graph rather than a bound service: the UI never
 * needs to talk back (it sends intents), and an unbound flow cannot keep the
 * service alive by accident or die with the activity.
 */
class RecorderBus {
    private val _state = MutableStateFlow(RecorderUiState())
    val state: StateFlow<RecorderUiState> = _state.asStateFlow()

    fun update(block: (RecorderUiState) -> RecorderUiState) {
        _state.value = block(_state.value)
    }

    /**
     * Back to idle, keeping what outlives the session.
     *
     * [RecorderUiState.message] is kept, and that is the whole point of it: the
     * end of a session is exactly when the recorder has something to say ("Nichts
     * aufgezeichnet — verworfen"), and this used to run immediately *after*
     * setting it, so the one message written for the start screen was wiped
     * before the start screen ever drew.
     */
    fun reset() {
        val was = _state.value
        _state.value = RecorderUiState(
            pendingUploads = was.pendingUploads,
            bundleVersion = was.bundleVersion,
            message = was.message,
            messageAtMs = was.messageAtMs,
            locationPermission = was.locationPermission,
        )
    }
}
