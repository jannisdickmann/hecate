package de.hecate.core.archive

import de.hecate.core.model.ExerciseType
import de.hecate.core.model.SessionSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The on-disk backup format (§1.3 of the open points).
 *
 * A plain ZIP the user owns and can put wherever they like — including Google
 * Drive by hand. Nothing here talks to a network; NFR-7 is untouched.
 *
 * ```
 * hecate-sicherung-20260911-1432.zip
 * ├── manifest.json          format version, export time, counts
 * ├── database.json          everything except samples: sessions, routes,
 * │                          members, geometry, profiles, meta
 * └── sessions/<id>.jsonl    one file per session in the *watch's own* format
 * ```
 *
 * Two deliberate choices about that layout.
 *
 * Samples live in the per-session JSONL rather than in `database.json` because
 * they are 99% of the bytes, and ZIP deflates each entry: measured over 300
 * sessions of 45 minutes at 1 Hz, the whole archive is about 15 MB where a copy
 * of the SQLite file would be 145 MB.
 *
 * And that JSONL is exactly [de.hecate.core.io.SessionCodec]'s format, the one
 * the watch writes and the replay harness reads. Unzip `sessions/` into
 * `fixtures/` and `./gradlew :core:run --args="replay …"` works on it directly,
 * with no converter in between.
 *
 * The DTOs below are deliberately *not* the Room entities. A backup format that
 * changes silently whenever a column is renamed is not a backup format; these
 * are a separate, versioned thing that the repository maps to and from by hand.
 *
 * The two enum fields are the exception to that separation, and the exception is
 * deliberate. They serialize as their own names, so the JSON is the same text it
 * has always been, and an archive written by a build that knows a type this one
 * does not now fails to parse instead of being quietly read as something else.
 * That is the louder failure of the two available, and the better one: this is
 * the file the user restores from after losing the phone, and a workout silently
 * relabelled as a run cannot be told from one that always was.
 */
object Archive {

    /** Bumped only for an incompatible change. A reader refuses anything newer. */
    const val FORMAT_VERSION = 1

    const val MANIFEST = "manifest.json"
    const val DATABASE = "database.json"
    const val SESSIONS_DIR = "sessions/"

    const val MIME = "application/zip"

    val json: Json = Json {
        // A reader of an older version must survive fields it has never heard of.
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    fun sessionEntry(sessionId: String): String = "$SESSIONS_DIR$sessionId.jsonl"

    /** "hecate-sicherung-20260911-1432.zip", in the phone's own time zone. */
    fun suggestedFileName(nowMs: Long): String {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(nowMs))
        return "hecate-sicherung-$stamp.zip"
    }

    @Serializable
    data class Manifest(
        val formatVersion: Int,
        val exportedAtUtcMs: Long,
        val appVersion: String,
        val sessionCount: Int,
        val routeCount: Int,
    )

    @Serializable
    data class Database(
        val sessions: List<Session> = emptyList(),
        val routes: List<Route> = emptyList(),
        val routeMembers: List<RouteMember> = emptyList(),
        val routePoints: List<RoutePoint> = emptyList(),
        val routeProfile: List<ProfileBin> = emptyList(),
        val meta: List<Meta> = emptyList(),
    )

    /**
     * A session row. Samples and the match log are not here — they are in the
     * session's own file, keyed by [id].
     *
     * `hcWriteState` is carried so a restore does not re-write sessions that
     * Health Connect already has. `matchedRouteId`, `committedAtM` and `avgHr`
     * are carried rather than recomputed because `committedAtM` in particular is
     * a fact about what the watch decided on the day, which nothing can derive
     * again later.
     */
    @Serializable
    data class Session(
        val id: String,
        val startedAtUtc: Long,
        val endedAtUtc: Long? = null,
        val tzOffsetMinutes: Int,
        val exerciseType: ExerciseType,
        val activeDurationMs: Long,
        val totalDistanceM: Double,
        val source: SessionSource,
        val hcWriteState: Int,
        val hcError: String? = null,
        val matchedRouteId: Long? = null,
        val matchCoverage: Double? = null,
        val committedAtM: Double? = null,
        val avgHr: Double? = null,
        val ingestedAt: Long = 0,
    )

    /** A route row. [name] and [priority] are the reason this file exists: they
     *  are the only things in the database a human typed, and they cannot be
     *  recomputed from the sessions by any amount of re-clustering. */
    @Serializable
    data class Route(
        val id: Long,
        val name: String,
        val nameIsCustom: Boolean,
        val exerciseType: ExerciseType,
        val lengthM: Double,
        val memberCount: Int,
        val medoidSessionId: String,
        val createdAt: Long,
        val active: Boolean,
        val priority: Int,
    )

    @Serializable
    data class RouteMember(val routeId: Long, val sessionId: String)

    @Serializable
    data class RoutePoint(
        val routeId: Long,
        val seq: Int,
        val lat: Double,
        val lon: Double,
        val cumDistM: Double,
    )

    @Serializable
    data class ProfileBin(
        val routeId: Long,
        val bin: Int,
        val medianCumTimeS: Double,
        val p25S: Double? = null,
        val p75S: Double? = null,
        val medianHr: Double? = null,
    )

    @Serializable
    data class Meta(val key: String, val value: String)

    data class ExportReport(val sessions: Int, val routes: Int, val bytes: Long)

    data class RestoreReport(
        val sessionsRestored: Int = 0,
        val sessionsSkipped: Int = 0,
        val routesRestored: Int = 0,
        val routesSkipped: Int = 0,
        val failed: Int = 0,
        val messages: List<String> = emptyList(),
    )

    /** Anything that makes an archive unusable: not a ZIP, no manifest, a format
     *  version from the future. Per-session problems are counted in the report
     *  instead, so one damaged entry cannot cost the user the other 299. */
    class ArchiveException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
