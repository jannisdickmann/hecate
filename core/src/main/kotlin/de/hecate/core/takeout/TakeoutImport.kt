package de.hecate.core.takeout

import de.hecate.core.archive.Archive
import de.hecate.core.geo.Track
import de.hecate.core.model.ActiveClock
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.Sample
import de.hecate.core.model.SessionEndReason
import de.hecate.core.model.SessionFooter
import de.hecate.core.model.SessionHeader
import de.hecate.core.model.SessionSource
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Turns a Google Health takeout into a Hecate backup archive (US-9, §7).
 *
 * The cold-start problem this solves is the same one the TCX import solves, at a
 * different scale: a route needs three recordings before it exists, so a fresh
 * install knows nothing for weeks. A takeout holds every workout the watch ever
 * recorded, and the archive this produces restores into the app in one go.
 *
 * What it does *not* do is invent routes. The archive carries sessions and
 * nothing else; the phone's own clusterer runs at the end of the restore and
 * derives the routes from the imported history, by the same code path and the
 * same thresholds as a route learned live. An importer that shipped its own
 * clustering would be a second implementation to keep in step with the first.
 *
 * Everything is held in memory: 63 workouts came to roughly 25 MB of samples
 * here. A history ten times longer would still fit, and the alternative — two
 * passes over the position files, or entries written out of order — costs more
 * than it saves at any size this app will ever see.
 */
object TakeoutImport {

    /** Heart rate is sampled at its own cadence; this is how far a position may
     *  look for one before giving up and recording no heart rate at all. */
    private const val HR_TOLERANCE_MS = 15_000L

    /** A workout needs at least this many positions to be worth importing. */
    private const val MIN_SAMPLES = 2

    private val UTC_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

    private val RELEVANT_PREFIXES = listOf(
        GoogleHealth.EXERCISES_PREFIX,
        GoogleHealth.GPS_PREFIX,
        GoogleHealth.HR_PREFIX,
        GoogleHealth.PROFILE,
    )

    data class Options(
        /**
         * Whether to import auto-detected activities that carry positions and do
         * not overlap a real workout. Three such rides are in this takeout; every
         * other auto-detected row is either empty or a duplicate of a workout
         * that was already recorded properly.
         */
        val includeAutoDetected: Boolean = true,
        /** Activity names to force onto a type, for what
         *  [de.hecate.core.model.ActivityNames] cannot map — a sport this app
         *  does not record, which the user nevertheless wants the route of. */
        val typeOverrides: Map<String, ExerciseType> = emptyMap(),
        /** Sessions already in the app, from a backup exported by the phone.
         *  Anything here that looks like the same workout is left out. */
        val existing: List<Archive.Session> = emptyList(),
        /** Overrides the zone from the takeout's own profile. */
        val zone: ZoneId? = null,
    )

    /**
     * One workout that did not make it into the archive, and why.
     *
     * [notable] separates the two very different populations in here. Most of a
     * takeout's exercise table is the watch guessing after the fact that twenty
     * minutes of walking happened, with no positions attached — hundreds of rows
     * that are *supposed* to be dropped, and listing them individually buries the
     * handful that a human should actually look at.
     */
    data class Skipped(
        val startUtcMs: Long,
        val activityName: String,
        val reason: String,
        val notable: Boolean,
    )

    data class Result(
        val database: Archive.Database,
        val sessions: List<RecordedSession>,
        val skipped: List<Skipped>,
        /** Zone used where an exercise did not name its own offset. */
        val fallbackZone: ZoneId,
        val duplicateEntries: List<String>,
    )

    // ------------------------------------------------------------------ import

    fun read(source: TakeoutSource, options: Options = Options()): Result {
        val fallbackZone = options.zone
            ?: GoogleHealth.profileZone(source)
            ?: GoogleHealth.FALLBACK_ZONE

        val all = GoogleHealth.exercises(source, fallbackZone)
        val skipped = ArrayList<Skipped>()
        val candidates = select(all, options, skipped)

        // Positions and heart rate are held one UTC day at a time. Grouping the
        // workouts by day first is what keeps that true: a day's file is read
        // once however many workouts fall in it, and dropped before the next.
        val sessions = ArrayList<RecordedSession>(candidates.size)
        // Google's own name for each workout, kept only so that a session left
        // out later can be reported under the name the export gave it — "Outdoor
        // Bike" rather than "Fahrradfahren", which is all the type could say.
        val activityNames = HashMap<String, String>(candidates.size * 2)
        for ((_, sameDay) in candidates.groupBy { UTC_DATE.format(Instant.ofEpochMilli(it.startUtcMs)) }) {
            // A workout that runs over UTC midnight has its tail in the next day's
            // file, and one that starts just after midnight local time has its
            // head in the previous day's. Both neighbours are cheap to include.
            val dates = sameDay.flatMap { datesTouched(it) }.toSortedSet()
            val fixes = dates.flatMap { GoogleHealth.positions(source, it) }.sortedBy { it.tUtcMs }
            val hr = dates.map { GoogleHealth.heartRate(source, it) }
                .fold(LongArray(0) to IntArray(0), ::mergeHeartRate)

            for (exercise in sameDay) {
                val session = build(exercise, fixes, hr, options)
                if (session == null) {
                    skipped.add(
                        Skipped(
                            exercise.startUtcMs,
                            exercise.activityName,
                            "keine Positionen im Takeout",
                            notable = exercise.logType == GoogleHealth.LogType.TRACKER,
                        ),
                    )
                } else {
                    sessions.add(session)
                    activityNames[session.id] = exercise.activityName
                }
            }
        }

        val kept = dropDuplicatesOf(sessions, activityNames, options.existing, skipped)
        return Result(
            database = Archive.Database(sessions = kept.map { it.toArchiveRow() }),
            sessions = kept,
            skipped = skipped.sortedBy { it.startUtcMs },
            fallbackZone = fallbackZone,
            // Only for the files this import actually reads. A takeout has a
            // README.txt in twenty folders, and none of them is a problem.
            duplicateEntries = source.duplicateNames.filter { name ->
                RELEVANT_PREFIXES.any { name.startsWith(it) }
            },
        )
    }

    // --------------------------------------------------------------- selection

    /**
     * Which exercises are worth trying to build a session from.
     *
     * Tracker-logged workouts always are. An auto-detected one only when it
     * overlaps nothing already selected: the watch routinely notices a walk that
     * is the first ten minutes of a ride it was already recording, and importing
     * both would give the clusterer two sessions for one journey.
     */
    private fun select(
        all: List<GoogleHealth.Exercise>,
        options: Options,
        skipped: MutableList<Skipped>,
    ): List<GoogleHealth.Exercise> {
        val out = ArrayList<GoogleHealth.Exercise>()
        val tracker = all.filter { it.logType == GoogleHealth.LogType.TRACKER }

        for (exercise in all) {
            val reason = when {
                exercise.logType == GoogleHealth.LogType.TRACKER -> null
                !options.includeAutoDetected -> "nicht selbst aufgezeichnet"
                exercise.logType != GoogleHealth.LogType.AUTO_DETECTED -> "unbekannte Aufzeichnungsart"
                tracker.any { it.overlaps(exercise) } -> "überschneidet eine aufgezeichnete Einheit"
                out.any { it.overlaps(exercise) } -> "überschneidet eine bereits übernommene Einheit"
                else -> null
            }
            if (reason != null) {
                skipped.add(Skipped(exercise.startUtcMs, exercise.activityName, reason, notable = false))
                continue
            }
            if (typeOf(exercise, options) == null) {
                skipped.add(
                    Skipped(
                        exercise.startUtcMs,
                        exercise.activityName,
                        "Sportart wird nicht unterstützt — mit --map \"${exercise.activityName}=WALKING\" erzwingen",
                        // Worth saying out loud when the user pressed start on it.
                        notable = exercise.logType == GoogleHealth.LogType.TRACKER,
                    ),
                )
                continue
            }
            out.add(exercise)
        }
        return out
    }

    private fun typeOf(exercise: GoogleHealth.Exercise, options: Options): ExerciseType? =
        options.typeOverrides[exercise.activityName.lowercase()]
            ?: GoogleHealth.exerciseTypeOf(exercise.activityName)

    // ---------------------------------------------------------------- building

    /**
     * One workout as a session in the watch's own shape.
     *
     * The session id is derived from Google's exercise id, which makes the whole
     * import idempotent: running this over the same takeout twice, or over next
     * year's takeout that still contains this year's workouts, produces the same
     * ids, and the restore in the app skips what it already has.
     */
    private fun build(
        exercise: GoogleHealth.Exercise,
        fixes: List<GoogleHealth.Fix>,
        hr: Pair<LongArray, IntArray>,
        options: Options,
    ): RecordedSession? {
        val type = typeOf(exercise, options) ?: return null
        val window = fixes.filter { it.tUtcMs >= exercise.startUtcMs && it.tUtcMs <= exercise.endUtcMs }
        if (window.size < MIN_SAMPLES) return null

        val start = exercise.startUtcMs
        // As in the TCX import: a takeout records no pauses, so wall-clock time is
        // active time except across a hole (see ActiveClock.importedElapsed).
        var active = 0L
        var prev = window.first().tUtcMs
        val samples = ArrayList<Sample>(window.size)
        for (fix in window) {
            active += ActiveClock.importedElapsed(prev, fix.tUtcMs)
            prev = fix.tUtcMs
            samples.add(
                Sample(
                    tOffsetMs = fix.tUtcMs - start,
                    activeMs = active,
                    lat = fix.lat,
                    lon = fix.lon,
                    altM = fix.altM,
                    hrBpm = heartRateAt(hr, fix.tUtcMs),
                    // The takeout reports no per-fix accuracy. Null rather than a
                    // guess: Sample.usable treats null as good, and inventing a
                    // number here would quietly filter real points.
                    accuracyM = null,
                ),
            )
        }

        return RecordedSession(
            header = SessionHeader(
                sessionId = sessionId(exercise),
                startedAtUtcMs = start,
                tzOffsetMinutes = exercise.tzOffsetMinutes,
                exerciseType = type,
                source = SessionSource.IMPORT_TAKEOUT,
            ),
            samples = samples,
            footer = SessionFooter(
                endedAtUtcMs = maxOf(exercise.endUtcMs, window.last().tUtcMs),
                activeDurationMs = active,
                // Google's own total covers ground the positions missed during a
                // dropout. Zero when it has none, which Track reads as "measure
                // the geometry instead".
                totalDistanceM = exercise.distanceM ?: 0.0,
                reason = SessionEndReason.IMPORTED,
            ),
        )
    }

    /** Stable across takeouts: Google's exercise id is the workout's identity. */
    fun sessionId(exercise: GoogleHealth.Exercise): String = "gh-${exercise.id}"

    /** The nearest heart rate within [HR_TOLERANCE_MS], or null. */
    private fun heartRateAt(hr: Pair<LongArray, IntArray>, tUtcMs: Long): Int? {
        val (times, bpm) = hr
        if (times.isEmpty()) return null
        var lo = times.binarySearch(tUtcMs)
        if (lo >= 0) return bpm[lo]
        lo = -lo - 1
        val before = if (lo > 0) lo - 1 else -1
        val after = if (lo < times.size) lo else -1
        val best = when {
            before < 0 -> after
            after < 0 -> before
            abs(times[before] - tUtcMs) <= abs(times[after] - tUtcMs) -> before
            else -> after
        }
        if (best < 0 || abs(times[best] - tUtcMs) > HR_TOLERANCE_MS) return null
        return bpm[best]
    }

    // -------------------------------------------------------------- duplicates

    /**
     * Drop anything that is already in the app.
     *
     * Three ways the same workout can show up twice, and this is the one that
     * needs work. Re-running the import is handled by the ids alone. A workout
     * this takeout and a previous takeout both contain likewise. But a workout
     * Hecate recorded on the watch — which the watch also logged to Google Health
     * on its own, and which therefore comes back in the takeout — carries an
     * unrelated id at each end, and only the clock can tell they are the same
     * journey. [Track.likelyDuplicate] is the test the ingest path already
     * applies for exactly this reason (§7); the same one is applied again inside
     * the app on restore, so an import built without `--existing` is still safe.
     */
    private fun dropDuplicatesOf(
        sessions: List<RecordedSession>,
        activityNames: Map<String, String>,
        existing: List<Archive.Session>,
        skipped: MutableList<Skipped>,
    ): List<RecordedSession> {
        if (existing.isEmpty()) return sessions
        val byId = existing.associateBy { it.id }
        return sessions.filter { session ->
            val clash = byId[session.id] ?: existing.firstOrNull {
                Track.likelyDuplicate(
                    it.startedAtUtc, it.activeDurationMs,
                    session.header.startedAtUtcMs, session.activeDurationMs,
                )
            }
            if (clash != null) {
                skipped.add(
                    Skipped(
                        session.header.startedAtUtcMs,
                        activityNames[session.id] ?: session.header.exerciseType.label,
                        "schon in der App vorhanden (${clash.id})",
                        notable = true,
                    ),
                )
            }
            clash == null
        }
    }

    // ---------------------------------------------------------------- plumbing

    /** The UTC dates a workout's positions could be filed under. */
    private fun datesTouched(exercise: GoogleHealth.Exercise): List<String> {
        val day = 86_400_000L
        return listOf(exercise.startUtcMs - day, exercise.startUtcMs, exercise.endUtcMs, exercise.endUtcMs + day)
            .map { UTC_DATE.format(Instant.ofEpochMilli(it)) }
            .distinct()
    }

    private fun mergeHeartRate(
        a: Pair<LongArray, IntArray>,
        b: Pair<LongArray, IntArray>,
    ): Pair<LongArray, IntArray> {
        if (a.first.isEmpty()) return b
        if (b.first.isEmpty()) return a
        val times = (a.first + b.first)
        val bpm = (a.second + b.second)
        val order = times.indices.sortedBy { times[it] }
        return LongArray(order.size) { times[order[it]] } to IntArray(order.size) { bpm[order[it]] }
    }

    /**
     * The session's row in `database.json`.
     *
     * `hcWriteState` is [de.hecate.core.archive.Archive] business rather than
     * this object's, but it matters here: 3 is SKIPPED, and it has to be, because
     * these workouts came *out* of Health Connect. Writing them back would give
     * the user two copies of every walk they have ever taken, in the one place
     * this app has no way to clean up afterwards.
     */
    private fun RecordedSession.toArchiveRow(): Archive.Session {
        val hr = samples.mapNotNull { it.hrBpm }
        return Archive.Session(
            id = id,
            startedAtUtc = header.startedAtUtcMs,
            endedAtUtc = footer?.endedAtUtcMs,
            tzOffsetMinutes = header.tzOffsetMinutes,
            exerciseType = header.exerciseType,
            activeDurationMs = activeDurationMs,
            totalDistanceM = footer?.totalDistanceM?.takeIf { it > 0 }
                ?: Track.of(this)?.totalDistanceM ?: 0.0,
            source = header.source,
            hcWriteState = HC_WRITE_STATE_SKIPPED,
            avgHr = if (hr.isEmpty()) null else hr.average(),
            ingestedAt = System.currentTimeMillis(),
        )
    }

    /** `de.hecate.phone.db.HcWriteState.SKIPPED`, which `:core` cannot see. */
    private const val HC_WRITE_STATE_SKIPPED = 3
}
