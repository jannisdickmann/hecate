package de.hecate.phone.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.SessionSource
import kotlinx.coroutines.flow.Flow

/** A row of the sessions list, without dragging every sample along. */
data class SessionSummary(
    val id: String,
    val startedAtUtc: Long,
    val tzOffsetMinutes: Int,
    val exerciseType: ExerciseType,
    val activeDurationMs: Long,
    val totalDistanceM: Double,
    val source: SessionSource,
    val hcWriteState: Int,
    val matchedRouteId: Long?,
    val routeName: String?,
    val avgHr: Double?,
)

/** A row of the routes list (US-8). */
data class RouteSummary(
    val id: Long,
    val name: String,
    val exerciseType: ExerciseType,
    val lengthM: Double,
    val memberCount: Int,
    val medianDurationMs: Long?,
    val bestDurationMs: Long?,
    val priority: Int,
)

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSamples(samples: List<SampleEntity>)

    @Upsert
    suspend fun upsertMatchLog(log: MatchLogEntity)

    @Upsert
    suspend fun upsertEvents(events: SessionEventsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeries(series: SessionSeriesEntity)

    @Query("SELECT COUNT(*) FROM session WHERE id = :id")
    suspend fun countById(id: String): Int

    /** §7: dedupe by time window, which catches both TCX re-imports and the
     *  watch's own auto-detected duplicates. */
    @Query(
        """
        SELECT * FROM session
        WHERE ABS(started_at_utc - :startedAtUtc) <= :windowMs
        """,
    )
    suspend fun near(startedAtUtc: Long, windowMs: Long): List<SessionEntity>

    @Query("SELECT * FROM session WHERE id = :id")
    suspend fun byId(id: String): SessionEntity?

    @Query("SELECT * FROM session ORDER BY started_at_utc DESC")
    suspend fun all(): List<SessionEntity>

    @Query(
        """
        SELECT s.id, s.started_at_utc AS startedAtUtc, s.tz_offset AS tzOffsetMinutes,
               s.exercise_type AS exerciseType, s.active_duration_ms AS activeDurationMs,
               s.total_distance_m AS totalDistanceM, s.source, s.hc_write_state AS hcWriteState,
               s.matched_route_id AS matchedRouteId, r.name AS routeName, s.avg_hr AS avgHr
        FROM session s LEFT JOIN route r ON r.id = s.matched_route_id
        ORDER BY s.started_at_utc DESC
        """,
    )
    fun summaries(): Flow<List<SessionSummary>>

    @Query("SELECT * FROM sample WHERE session_id = :id ORDER BY t_offset_ms")
    suspend fun samples(id: String): List<SampleEntity>

    @Query("SELECT * FROM match_log WHERE session_id = :id")
    suspend fun matchLog(id: String): MatchLogEntity?

    @Query("SELECT * FROM session_events WHERE session_id = :id")
    suspend fun events(id: String): SessionEventsEntity?

    @Query("SELECT * FROM session_series WHERE session_id = :id")
    suspend fun series(id: String): SessionSeriesEntity?

    @Query("SELECT * FROM session WHERE hc_write_state = :state ORDER BY started_at_utc")
    suspend fun byHcState(state: Int): List<SessionEntity>

    @Query("UPDATE session SET hc_write_state = :state, hc_error = :error WHERE id = :id")
    suspend fun setHcState(id: String, state: Int, error: String?)

    /** Deliberately does not touch `committed_at_m`: that is a fact about what the
     *  watch decided during the workout and re-clustering has no business
     *  rewriting it. */
    @Query("UPDATE session SET matched_route_id = :routeId, match_coverage = :coverage WHERE id = :id")
    suspend fun setMatch(id: String, routeId: Long?, coverage: Double?)

    @Query("UPDATE session SET matched_route_id = NULL, match_coverage = NULL WHERE matched_route_id = :routeId")
    suspend fun clearMatchesFor(routeId: Long)

    /**
     * Correct the sport a recording was filed under.
     *
     * The only writer of `exercise_type` other than ingest, and it exists because
     * the watch cannot know it guessed wrong: a hike started with the Spaziergang
     * button is a walk for ever, and it drags a route's identity with it — type
     * is a hard partition in clustering ([de.hecate.core.cluster.CoverageMetric]
     * scores a cross-type pair at 1.0), so one mistyped session sits in the wrong
     * cluster forming the wrong route.
     *
     * Targeted rather than an `@Update` of the row: everything else on a session
     * is a fact about the recording, and an entity write would let a stale copy
     * held by a screen quietly put the rest of them back.
     */
    @Query("UPDATE session SET exercise_type = :type WHERE id = :id")
    suspend fun setExerciseType(id: String, type: ExerciseType)

    @Query("DELETE FROM session WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM session")
    suspend fun count(): Int
}

@Dao
interface RouteDao {

    @Insert
    suspend fun insert(route: RouteEntity): Long

    @Upsert
    suspend fun upsert(route: RouteEntity)

    @Query("SELECT * FROM route ORDER BY id")
    suspend fun all(): List<RouteEntity>

    @Query("SELECT * FROM route WHERE id = :id")
    suspend fun byId(id: Long): RouteEntity?

    @Query("UPDATE route SET name = :name, name_is_custom = 1 WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE route SET priority = :priority WHERE id = :id")
    suspend fun setPriority(id: Long, priority: Int)

    @Query("DELETE FROM route WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM route_member")
    suspend fun allMembers(): List<RouteMemberEntity>

    @Query("SELECT * FROM route_member WHERE route_id = :routeId")
    suspend fun members(routeId: Long): List<RouteMemberEntity>

    @Query("DELETE FROM route_member WHERE route_id = :routeId")
    suspend fun clearMembers(routeId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<RouteMemberEntity>)

    @Query("DELETE FROM route_point WHERE route_id = :routeId")
    suspend fun clearPoints(routeId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPoints(points: List<RoutePointEntity>)

    @Query("SELECT * FROM route_point WHERE route_id = :routeId ORDER BY seq")
    suspend fun points(routeId: Long): List<RoutePointEntity>

    @Query("DELETE FROM route_profile WHERE route_id = :routeId")
    suspend fun clearProfile(routeId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(bins: List<RouteProfileEntity>)

    @Query("SELECT * FROM route_profile WHERE route_id = :routeId ORDER BY bin")
    suspend fun profile(routeId: Long): List<RouteProfileEntity>

    /** US-8: the route list shows name, length, count, median time and best time.
     *  Taken in SQL so the list is one query rather than one query per row.
     *
     *  A correlated `OFFSET (SELECT COUNT(*) ... WHERE mm.route_id = r.id)` is what
     *  this wants to say and is not legal SQLite — a LIMIT/OFFSET expression cannot
     *  reach the outer query, so it fails with "no such column: r.id". Ranking the
     *  members once in a window function says the same thing and is a single pass.
     *
     *  The median is the mean of the two middle values for an even member count,
     *  matching `HecateViewModel.median()` so that the list row and the detail
     *  screen show the same number for the same route. */
    @Query(
        """
        WITH member_duration AS (
            SELECT m.route_id AS route_id,
                   s.active_duration_ms AS duration_ms,
                   ROW_NUMBER() OVER (PARTITION BY m.route_id ORDER BY s.active_duration_ms) AS rn,
                   COUNT(*) OVER (PARTITION BY m.route_id) AS n
              FROM route_member m JOIN session s ON s.id = m.session_id
        )
        SELECT r.id, r.name, r.exercise_type AS exerciseType, r.length_m AS lengthM,
               r.member_count AS memberCount, r.priority,
               (SELECT CAST(AVG(d.duration_ms) AS INTEGER)
                  FROM member_duration d
                 WHERE d.route_id = r.id AND d.rn IN ((d.n + 1) / 2, (d.n + 2) / 2)
               ) AS medianDurationMs,
               (SELECT MIN(d.duration_ms)
                  FROM member_duration d
                 WHERE d.route_id = r.id
               ) AS bestDurationMs
        FROM route r WHERE r.active = 1 ORDER BY r.member_count DESC, r.id
        """,
    )
    fun summaries(): Flow<List<RouteSummary>>

    /** Durations of a route's members, for the median/best columns of US-8. */
    @Query(
        """
        SELECT s.active_duration_ms FROM route_member m
        JOIN session s ON s.id = m.session_id
        WHERE m.route_id = :routeId ORDER BY s.active_duration_ms
        """,
    )
    suspend fun memberDurations(routeId: Long): List<Long>

    @Transaction
    @Query(
        """
        SELECT s.id, s.started_at_utc AS startedAtUtc, s.tz_offset AS tzOffsetMinutes,
               s.exercise_type AS exerciseType, s.active_duration_ms AS activeDurationMs,
               s.total_distance_m AS totalDistanceM, s.source, s.hc_write_state AS hcWriteState,
               s.matched_route_id AS matchedRouteId, r.name AS routeName, s.avg_hr AS avgHr
        FROM route_member m
        JOIN session s ON s.id = m.session_id
        LEFT JOIN route r ON r.id = m.route_id
        WHERE m.route_id = :routeId
        ORDER BY s.started_at_utc DESC
        """,
    )
    suspend fun memberSessions(routeId: Long): List<SessionSummary>
}

@Dao
interface HeatmapDao {
    @Upsert
    suspend fun put(heatmap: HeatmapEntity)

    @Query("SELECT * FROM heatmap WHERE id = :id")
    suspend fun get(id: Int = HeatmapEntity.CARD): HeatmapEntity?

    /** Observed by the routes screen so the picture refreshes itself when a
     *  re-clustering finishes, rather than on the next visit to the panel. */
    @Query("SELECT * FROM heatmap WHERE id = :id")
    fun watch(id: Int = HeatmapEntity.CARD): Flow<HeatmapEntity?>
}

/**
 * The meta keys that hold a user preference rather than bookkeeping.
 *
 * In `meta` rather than in a `DataStore` or a `SharedPreferences` file because
 * it is one number and the app already has a key/value table that the backup
 * archive carries: a setting stored anywhere else would be a setting that does
 * not survive a restore, and finding that out costs the user the setting.
 */
object Settings {
    /** The heart rate the intensity zones are measured against [bpm]. Absent
     *  until the user changes it; [de.hecate.core.Tuning.DEFAULT_PEAK_HR_BPM]
     *  stands in until then. */
    const val PEAK_HR_BPM = "peak_hr_bpm"
}

@Dao
interface MetaDao {
    @Upsert
    suspend fun put(meta: MetaEntity)

    @Query("SELECT value FROM meta WHERE key = :key")
    suspend fun get(key: String): String?

    /** As [get], watched, so a setting changed on one screen recolours another
     *  without either knowing about the other. A list because the row may not
     *  exist, and a query for a missing single value is an empty result rather
     *  than a null one. */
    @Query("SELECT value FROM meta WHERE key = :key")
    fun watch(key: String): Flow<List<String>>

    /** Every row, for the backup archive. */
    @Query("SELECT * FROM meta")
    suspend fun all(): List<MetaEntity>
}
