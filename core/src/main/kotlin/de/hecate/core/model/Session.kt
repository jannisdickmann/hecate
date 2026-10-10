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
    /** Taken while the workout was paused. Recordings made before the watch
     *  decided this per position carry their delivery's state on every position
     *  in it, right or wrong; read them through [ActiveClock.unpause], as
     *  [ActiveClock.repair] and the matcher do. */
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

    /** The recorder ended the session itself because the activity was over and
     *  nobody had said so — see
     *  [de.hecate.core.recorder.AutoStopWatch]. The note says which of the three
     *  rules decided it. Written *after* the cut it describes, which is why the
     *  events survive a trim when the samples around them do not. */
    AUTO_STOP,
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

    /** Health Services ended the exercise on its own — a permission lost, a
     *  session taken over by another app, a replay that ran out of recording. */
    AUTO_ENDED,

    /** The recorder decided the activity was over: standing still, no fix for a
     *  quarter of an hour, or a speed the sport cannot reach
     *  ([de.hecate.core.recorder.AutoStopWatch]). Distinct from [AUTO_ENDED]
     *  because it is this app's own judgement rather than the platform's, and
     *  because it is the only ending that discards part of what it recorded. */
    AUTO_STOPPED,

    SERVICE_DIED,
    IMPORTED,
}

/**
 * One heart-rate reading, with the time it was taken.
 *
 * Its own record because the sensor has its own clock. A [Sample] is written per
 * *position*, and a rate that arrives between two of them — or in a batch that
 * carried no position at all — has nowhere to go: on one real 47-minute ride the
 * recorder kept one reading per delivery and wrote 161 of them, which is a
 * reading every seventeen seconds for a sensor that measures every second. What
 * was thrown away is not in the file and cannot be got back from it, which is
 * the whole argument for writing the series as the sensor gives it and joining
 * it to the positions later (see `HeartRates.merge`).
 */
@Serializable
data class HeartRate(
    @SerialName("t") val tOffsetMs: Long,
    @SerialName("hr") val bpm: Int,
)

/**
 * One reading from a sensor that measures on a schedule of its own.
 *
 * The generic successor to [HeartRate], which keeps its own shape only because
 * every session file and every backup already written uses it. Everything added
 * since — barometric altitude, fused speed — is this.
 *
 * The argument is the one [HeartRate] already paid for. A reading that arrives
 * between two positions, or in a delivery that carried none, has nowhere to go
 * in a per-position row and is lost. That is not hypothetical here: measured on
 * a Pixel Watch 4, ABSOLUTE_ELEVATION arrives once every 20 to 60 seconds and
 * irregularly, against a position a second, so joining it at write time kept 16
 * samples in 101 and would have kept none at all through a GNSS dropout — where
 * the barometer is in fact the one sensor still working.
 *
 * [value] is in the unit of whatever wrote it: metres for altitude, metres per
 * second for speed. The line kind says which, and nothing here needs to know.
 */
@Serializable
data class Reading(
    @SerialName("t") val tOffsetMs: Long,
    @SerialName("v") val value: Double,
)

/** Written as the last line of a session file. Its absence means the recording was
 *  interrupted; such a file is still uploaded and ingested (NFR-1). */
@Serializable
data class SessionFooter(
    val endedAtUtcMs: Long,
    val activeDurationMs: Long,
    val totalDistanceM: Double,
    val reason: SessionEndReason,
    /**
     * The last sample offset that is part of the activity [ms], or null when
     * nothing was cut — which is every session stopped by hand and every one
     * recorded before auto-stop existed.
     *
     * Written by an auto-stop, together with [activeDurationMs] and
     * [totalDistanceM] as they stood at that sample rather than at the end. The
     * file itself keeps every position it recorded, because it is append-only
     * and rewriting it is the one thing a recorder may not do (NFR-1); the phone
     * applies the cut once, on the way in
     * ([de.hecate.core.recorder.AutoStop.trim]), so the drive home reaches
     * neither the database nor Health Connect nor the clusterer.
     */
    val trimmedAfterTOffsetMs: Long? = null,
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
    /** Every reading the sensor gave, at its own times. Empty for a session
     *  recorded before the watch wrote them, and for every import — a takeout
     *  or a TCX carries one rate per position and nothing between. */
    val heartRates: List<HeartRate> = emptyList(),
    /** Barometric altitude in metres, at the barometer's own times. Empty for
     *  every import — which carries an altitude per position instead — and for
     *  anything recorded before the watch asked for the data type at all. */
    val altitudes: List<Reading> = emptyList(),
    /** Speed in metres per second as Health Services fuses it, at its own times.
     *  Empty for imports and for recordings made before the watch kept it. */
    val speeds: List<Reading> = emptyList(),
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
