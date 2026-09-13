package de.hecate.wear.recorder

import de.hecate.core.model.ExerciseType
import de.hecate.core.model.MatchPhase
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

/** Everything the three watch pages render. */
data class RecorderUiState(
    val phase: RecorderPhase = RecorderPhase.IDLE,
    val exerciseType: ExerciseType = ExerciseType.RUNNING,
    val sessionId: String? = null,

    // Live page
    val activeDurationMs: Long = 0,
    val distanceM: Double = 0.0,
    val heartRateBpm: Int? = null,
    val speedKmh: Double? = null,

    // Route page
    val matchPhase: MatchPhase = MatchPhase.SEARCHING,
    val routeName: String? = null,
    /** The route was assumed from the user's own ranking rather than worked out
     *  from the track. Shown in a different colour so a glance tells you which. */
    val routeByPriority: Boolean = false,
    val remainingM: Double? = null,
    val etaSecondsFromNow: Double? = null,
    val deltaVsMedianS: Double? = null,
    val offRoute: Boolean = false,
    val matcherDisabled: Boolean = false,

    // Housekeeping
    val gpsReady: Boolean = false,
    val locationAvailable: Boolean = false,
    val heartRateAvailable: Boolean = false,
    val autoPaused: Boolean = false,
    val pendingUploads: Int = 0,
    val bundleVersion: Long = 0,
    val message: String? = null,
    /** Set while a stored session is being replayed instead of recorded (§6.5).
     *  Always null in a release build, and shown on the Live page so that a
     *  screenshot of a replay can never be mistaken for one of a workout. */
    val replayLabel: String? = null,
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

    fun reset() {
        _state.value = RecorderUiState(pendingUploads = _state.value.pendingUploads, bundleVersion = _state.value.bundleVersion)
    }
}
