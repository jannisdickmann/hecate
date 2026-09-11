package de.hecate.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The three outdoor, GPS-on exercise types v1 records (US-1). */
@Serializable
enum class ExerciseType {
    RUNNING,
    WALKING,
    BIKING,
}

/** Where a session came from. Imported sessions are never written back to Health
 *  Connect — they came from there in the first place (US-9). */
@Serializable
enum class SessionSource {
    WATCH,
    IMPORT_TCX,
}

/**
 * One GNSS/HR observation.
 *
 * [tOffsetMs] is measured from the session start with a monotonic clock
 * (`SystemClock.elapsedRealtimeNanos` on the watch, NFR-6). Wall clock appears
 * exactly once per session, in [SessionHeader.startedAtUtcMs].
 *
 * [activeMs] is the elapsed *active* duration at this sample, i.e. wall-ish time
 * minus everything spent paused. Splits, ahead/behind and the profile all use it;
 * [tOffsetMs] only ever orders samples.
 */
@Serializable
data class Sample(
    @SerialName("t") val tOffsetMs: Long,
    @SerialName("a") val activeMs: Long,
    @SerialName("lat") val lat: Double,
    @SerialName("lon") val lon: Double,
    @SerialName("alt") val altM: Double? = null,
    @SerialName("hr") val hrBpm: Int? = null,
    @SerialName("acc") val accuracyM: Double? = null,
    @SerialName("d") val cumDistanceM: Double? = null,
    @SerialName("p") val paused: Boolean = false,
) {
    /** True when this sample is good enough to feed the matcher and the clusterer. */
    val usable: Boolean
        get() = !paused &&
            lat.isFinite() && lon.isFinite() &&
            (accuracyM == null || accuracyM <= de.hecate.core.Tuning.MAX_USABLE_ACCURACY_M)
}

/** Things that happen to a recording but are not observations. */
@Serializable
enum class SessionEventType {
    PAUSE_MANUAL,
    RESUME_MANUAL,
    PAUSE_AUTO,
    RESUME_AUTO,
    GPS_LOST,
    GPS_ACQUIRED,
    SERVICE_RESTARTED,
    MATCHER_DISABLED,
}

@Serializable
data class SessionEvent(
    @SerialName("t") val tOffsetMs: Long,
    @SerialName("e") val type: SessionEventType,
    @SerialName("n") val note: String? = null,
)

/** Written as the first line of a session file, before any sample exists. */
@Serializable
data class SessionHeader(
    val sessionId: String,
    val startedAtUtcMs: Long,
    val tzOffsetMinutes: Int,
    val exerciseType: ExerciseType,
    val source: SessionSource = SessionSource.WATCH,
    /** Version of the route bundle the watch held while recording. Null when the
     *  watch had no bundle, which makes an "Unbekannt" match unremarkable. */
    val bundleVersion: Long? = null,
    val appVersion: String = "",
)

@Serializable
enum class SessionEndReason {
    USER_STOPPED,
    AUTO_ENDED,
    SERVICE_DIED,
    IMPORTED,
}

/** Written as the last line of a session file. Its absence means the recording was
 *  interrupted; such a file is still uploaded and ingested (NFR-1). */
@Serializable
data class SessionFooter(
    val endedAtUtcMs: Long,
    val activeDurationMs: Long,
    val totalDistanceM: Double,
    val reason: SessionEndReason,
)

/**
 * A session as it exists once fully read back: header, everything observed, and
 * — if the recording ended cleanly — a footer.
 */
data class RecordedSession(
    val header: SessionHeader,
    val samples: List<Sample>,
    val events: List<SessionEvent> = emptyList(),
    val footer: SessionFooter? = null,
    val matchLog: List<MatchLogEntry> = emptyList(),
) {
    val id: String get() = header.sessionId

    /** Active duration from the footer when present, otherwise from the samples.
     *  An interrupted recording still has a usable duration. */
    val activeDurationMs: Long
        get() = footer?.activeDurationMs ?: samples.lastOrNull()?.activeMs ?: 0L
}
