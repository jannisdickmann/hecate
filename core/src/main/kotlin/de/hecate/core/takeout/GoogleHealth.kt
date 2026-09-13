package de.hecate.core.takeout

import de.hecate.core.model.ActivityNames
import de.hecate.core.model.ExerciseType
import de.hecate.core.tcx.TcxParser
import java.time.ZoneId

/**
 * The three things in a Google Health takeout that a route matcher cares about,
 * and how to get them out of it.
 *
 * The export is large and almost entirely irrelevant here — sleep stages, SpO2,
 * skin temperature, stress scores. What matters is:
 *
 * - `UserExercises_<date>.csv`, one row per workout, with UTC start and end and
 *   an explicit `utc_offset`. This is the authoritative list. The prettier
 *   `exercise-0.json` next to it is *not*: it is capped at a few hundred entries,
 *   its `hasGps` flag disagrees with the position files on fifteen days here, and
 *   its `startTime` is a bare local timestamp in no stated zone.
 * - `gps_location_<date>.csv`, every position the watch recorded that UTC day, at
 *   1 Hz, in UTC. Not segmented by workout — segmenting it is [TakeoutImport]'s job.
 * - `heart_rate_<date>.csv`, the same for heart rate, roughly every two seconds.
 *
 * Both time series repeat themselves: each point appears once per `data source`,
 * typically as `Pixel Watch 4` and again as `Google Health App`, with identical
 * coordinates. They are deduplicated on the timestamp here rather than left for
 * the caller, because a track with every point doubled still resamples to
 * something plausible and would never be noticed.
 */
object GoogleHealth {

    const val EXERCISES_PREFIX = "UserExercises_"
    const val GPS_PREFIX = "gps_location_"
    const val HR_PREFIX = "heart_rate_"
    const val PROFILE = "Profile.csv"

    /** The fallback when a takeout has no profile and an exercise no offset. */
    val FALLBACK_ZONE: ZoneId = ZoneId.of("Europe/Berlin")

    /**
     * How a workout was logged. Only [TRACKER] means "the user pressed start on
     * the watch", and only those have positions worth keeping: an
     * [AUTO_DETECTED] row is the watch noticing after the fact that the last
     * twenty minutes looked like a walk, which on this export carries no GPS and
     * frequently overlaps a real workout.
     */
    enum class LogType { TRACKER, AUTO_DETECTED, OTHER }

    /** One row of `UserExercises`, reduced to what is used. */
    data class Exercise(
        val id: String,
        val startUtcMs: Long,
        val endUtcMs: Long,
        val tzOffsetMinutes: Int,
        val activityName: String,
        val logType: LogType,
        /** Google's own total, which knows about the ground the GPS missed. */
        val distanceM: Double?,
    ) {
        val durationMs: Long get() = endUtcMs - startUtcMs
        fun overlaps(other: Exercise): Boolean = startUtcMs < other.endUtcMs && other.startUtcMs < endUtcMs
    }

    /** One position fix. */
    data class Fix(val tUtcMs: Long, val lat: Double, val lon: Double, val altM: Double?)

    /**
     * Every exercise in the export, newest row winning where two parts of a split
     * takeout hold the same exercise id.
     *
     * Rows without a usable start and end are dropped: an exercise that cannot say
     * when it happened cannot be matched to positions either.
     */
    fun exercises(source: TakeoutSource, fallbackZone: ZoneId): List<Exercise> {
        val byId = LinkedHashMap<String, Exercise>()
        for (name in source.dated(EXERCISES_PREFIX)) {
            for (row in source.read(name) { Csv.read(it) }) {
                val id = row["exercise_id"] ?: continue
                val start = row["exercise_start"]?.let { TcxParser.parseIso8601Millis(it) } ?: continue
                val end = row["exercise_end"]?.let { TcxParser.parseIso8601Millis(it) } ?: continue
                if (end <= start) continue
                byId[id] = Exercise(
                    id = id,
                    startUtcMs = start,
                    endUtcMs = end,
                    // The takeout does name the zone, per exercise and as an
                    // offset rather than a zone id, which is the thing actually
                    // needed and is already DST-resolved. The profile zone is
                    // only reached for a row that left the column empty.
                    tzOffsetMinutes = row["utc_offset"]?.let { offsetMinutes(it) }
                        ?: TcxParser.offsetMinutesFor(start, fallbackZone),
                    activityName = row["activity_name"] ?: "",
                    logType = when (row["log_type"]) {
                        "TRACKER" -> LogType.TRACKER
                        "AUTO_DETECTED" -> LogType.AUTO_DETECTED
                        else -> LogType.OTHER
                    },
                    distanceM = row["tracker_total_distance_mm"]?.toDoubleOrNull()
                        ?.takeIf { it > 0 }?.div(1000.0),
                )
            }
        }
        return byId.values.sortedBy { it.startUtcMs }
    }

    /** The zone in the user's profile, or null when the export has no profile. */
    fun profileZone(source: TakeoutSource): ZoneId? =
        source.readIfPresent(PROFILE) { Csv.read(it) }
            ?.firstOrNull()?.get("timezone")
            ?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    /** Positions for one UTC date, sorted and deduplicated. Empty when absent. */
    fun positions(source: TakeoutSource, date: String): List<Fix> {
        val rows = source.readIfPresent("$GPS_PREFIX$date.csv") { Csv.read(it) } ?: return emptyList()
        val byTime = LinkedHashMap<Long, Fix>(rows.size * 2)
        for (row in rows) {
            val t = row["timestamp"]?.let { TcxParser.parseIso8601Millis(it) } ?: continue
            val lat = row["latitude"]?.toDoubleOrNull() ?: continue
            val lon = row["longitude"]?.toDoubleOrNull() ?: continue
            byTime.putIfAbsent(t, Fix(t, lat, lon, row["altitude"]?.toDoubleOrNull()))
        }
        return byTime.values.sortedBy { it.tUtcMs }
    }

    /** Heart rate for one UTC date as parallel arrays, sorted by time. */
    fun heartRate(source: TakeoutSource, date: String): Pair<LongArray, IntArray> {
        val rows = source.readIfPresent("$HR_PREFIX$date.csv") { Csv.read(it) } ?: return LongArray(0) to IntArray(0)
        val byTime = LinkedHashMap<Long, Int>(rows.size * 2)
        for (row in rows) {
            val t = row["timestamp"]?.let { TcxParser.parseIso8601Millis(it) } ?: continue
            val bpm = row["beats per minute"]?.toDoubleOrNull()?.toInt() ?: continue
            if (bpm > 0) byTime.putIfAbsent(t, bpm)
        }
        val times = byTime.keys.sorted()
        return times.toLongArray() to IntArray(times.size) { byTime.getValue(times[it]) }
    }

    /** `"+02:00"`, `"-05:30"`, `"Z"` → minutes east of UTC. Null when unparseable. */
    internal fun offsetMinutes(raw: String): Int? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s == "Z" || s == "z") return 0
        val sign = when (s[0]) {
            '+' -> 1
            '-' -> -1
            else -> return null
        }
        val body = s.substring(1).split(':')
        val h = body.getOrNull(0)?.toIntOrNull() ?: return null
        val m = if (body.size > 1) body[1].toIntOrNull() ?: return null else 0
        return sign * (h * 60 + m)
    }

    /**
     * Google's activity names to the types this app records.
     *
     * The table itself is [ActivityNames], shared with the TCX reader so that a
     * name one importer understands cannot be a name the other silently gets
     * wrong. Anything unmatched is null and left for the caller to report.
     */
    fun exerciseTypeOf(activityName: String): ExerciseType? = ActivityNames.typeOf(activityName)
}
