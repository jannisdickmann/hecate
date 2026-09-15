package de.hecate.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The outdoor, GPS-on exercise types the app records (US-1).
 *
 * Every one of them exists on both sides of the round trip — Health Services can
 * start a session of that type on the watch, and Health Connect has a matching
 * `EXERCISE_TYPE_*` for the phone to write it back as. That is the rule for
 * adding another: Health Services offers finer grain than Health Connect can
 * express (`MOUNTAIN_BIKING`, `ALPINE_SKIING`, `CROSS_COUNTRY_SKIING`), and a
 * type that collapses into another one on write-back would leave the watch and
 * Google Health disagreeing about what the workout was.
 *
 * The three German words are three different jobs, which is why they are not one
 * string: [label] is what the user taps and reads, [sessionTitle] is the noun
 * Health Connect shows in its own list, and [routeWord] goes inside an
 * auto-generated route name. They live here rather than in each module's
 * formatter so that the watch and the phone cannot drift apart.
 */
@Serializable
enum class ExerciseType(
    /** Picker, lists, detail screens — everywhere the user reads the type. */
    val label: String,
    /** The title Health Connect shows for a written session. A noun. */
    val sessionTitle: String,
    /** The word [de.hecate.core.cluster.RouteNamer] builds route names from. */
    val routeWord: String,
) {
    RUNNING("Laufen", "Lauf", "Lauf"),
    WALKING("Spaziergang", "Spaziergang", "Gehen"),
    HIKING("Wandern", "Wanderung", "Wandern"),
    BIKING("Fahrradfahren", "Radfahrt", "Rad"),
    SKIING("Skifahren", "Skitour", "Ski"),
    ;

    companion object {
        /**
         * A stored name back to a value, or null if this build has never heard
         * of it.
         *
         * Deliberately not [valueOf] with a fallback. The one way an unknown name
         * can reach a database or an archive is a build that knew a type this one
         * does not, and quietly calling that a run would file the workout under
         * the wrong sport for good — see Appendix A.18.
         */
        fun parse(name: String?): ExerciseType? = entries.firstOrNull { it.name == name }
    }
}

/** Where a session came from. Imported sessions are never written back to Health
 *  Connect — they came from there in the first place (US-9). */
@Serializable
enum class SessionSource {
    WATCH,

    /** One TCX file, picked by hand in the app. */
    IMPORT_TCX,

    /** A Google Health takeout, decoded by `:core:run --args="takeout …"`. The
     *  distinction from [IMPORT_TCX] buys nothing algorithmically — both behave
     *  identically everywhere [isImport] is asked — and exists so that a session
     *  can say where it came from years later. */
    IMPORT_TAKEOUT,
    ;

    /** True for anything that was read out of an export rather than recorded.
     *  Such a session is never written back to Health Connect, and is the only
     *  kind a restore is allowed to reject as a duplicate of something already
     *  in the database. */
    val isImport: Boolean get() = this != WATCH

    companion object {
        /** As [ExerciseType.parse], and for the same reason: a name this build
         *  does not know is an error, not a session recorded on the watch. */
        fun parse(name: String?): SessionSource? = entries.firstOrNull { it.name == name }
    }
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

    /**
     * The same samples on a clock that only ever advances — see [ActiveClock]
     * for why [Sample.activeMs] as recorded cannot be read as a series.
     *
     * Anything that asks *when* rather than *how long in total* should read
     * this. Computed once and kept, because a track is built from it and a
     * profile is built from the track, and those must be on the same clock as
     * the heart rates looked up beside them.
     */
    val timedSamples: List<Sample> by lazy(LazyThreadSafetyMode.NONE) { ActiveClock.repair(samples) }

    /** Active duration from the footer when present, otherwise from the samples.
     *  An interrupted recording still has a usable duration — and it is read off
     *  the repaired clock, since the raw one can end lower than it started. */
    val activeDurationMs: Long
        get() = footer?.activeDurationMs ?: timedSamples.lastOrNull()?.activeMs ?: 0L
}
