package de.hecate.phone.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The phone's schema, §5.5 verbatim where the spec named columns.
 *
 * `sample` is the only large table: a year of daily hour-long workouts is about
 * 1.3 M rows, which SQLite does not notice.
 */

/** How far a session has got towards Health Connect (US-2). */
object HcWriteState {
    const val PENDING = 0
    const val WRITTEN = 1
    const val FAILED = 2
    /** Imported sessions came *from* Health Connect and are never written back. */
    const val SKIPPED = 3
}

@Entity(tableName = "session")
data class SessionEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "started_at_utc") val startedAtUtc: Long,
    /** Wall-clock stop time from the session footer. Null when the recording was
     *  interrupted and never wrote one (NFR-1); such a session is still ingested. */
    @ColumnInfo(name = "ended_at_utc") val endedAtUtc: Long? = null,
    @ColumnInfo(name = "tz_offset") val tzOffsetMinutes: Int,
    @ColumnInfo(name = "exercise_type") val exerciseType: String,
    @ColumnInfo(name = "active_duration_ms") val activeDurationMs: Long,
    @ColumnInfo(name = "total_distance_m") val totalDistanceM: Double,
    @ColumnInfo(name = "source") val source: String,
    @ColumnInfo(name = "hc_write_state") val hcWriteState: Int = HcWriteState.PENDING,
    @ColumnInfo(name = "hc_error") val hcError: String? = null,
    @ColumnInfo(name = "matched_route_id") val matchedRouteId: Long? = null,
    @ColumnInfo(name = "match_coverage") val matchCoverage: Double? = null,
    @ColumnInfo(name = "committed_at_m") val committedAtM: Double? = null,
    @ColumnInfo(name = "avg_hr") val avgHr: Double? = null,
    @ColumnInfo(name = "ingested_at") val ingestedAt: Long = 0,
)

@Entity(
    tableName = "sample",
    primaryKeys = ["session_id", "t_offset_ms"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id")],
)
data class SampleEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "t_offset_ms") val tOffsetMs: Long,
    @ColumnInfo(name = "active_ms") val activeMs: Long,
    @ColumnInfo(name = "lat") val lat: Double,
    @ColumnInfo(name = "lon") val lon: Double,
    @ColumnInfo(name = "alt_m") val altM: Double?,
    @ColumnInfo(name = "hr_bpm") val hrBpm: Int?,
    @ColumnInfo(name = "accuracy_m") val accuracyM: Double?,
    @ColumnInfo(name = "paused") val paused: Boolean,
)

@Entity(tableName = "route")
data class RouteEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
    @ColumnInfo(name = "name") val name: String,
    /** True once the user has renamed it: auto-naming must not overwrite that. */
    @ColumnInfo(name = "name_is_custom") val nameIsCustom: Boolean = false,
    @ColumnInfo(name = "exercise_type") val exerciseType: String,
    @ColumnInfo(name = "length_m") val lengthM: Double,
    @ColumnInfo(name = "member_count") val memberCount: Int,
    @ColumnInfo(name = "medoid_session_id") val medoidSessionId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "active") val active: Boolean = true,
    /** The user's manual ranking, 1..10. Breaks ties the evidence cannot break:
     *  see RouteEntry.priority in :core. Survives re-clustering, like the name. */
    @ColumnInfo(name = "priority", defaultValue = "5")
    val priority: Int = de.hecate.core.Tuning.DEFAULT_ROUTE_PRIORITY,
)

@Entity(
    tableName = "route_point",
    primaryKeys = ["route_id", "seq"],
    foreignKeys = [
        ForeignKey(
            entity = RouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["route_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RoutePointEntity(
    @ColumnInfo(name = "route_id") val routeId: Long,
    @ColumnInfo(name = "seq") val seq: Int,
    @ColumnInfo(name = "lat") val lat: Double,
    @ColumnInfo(name = "lon") val lon: Double,
    @ColumnInfo(name = "cum_dist_m") val cumDistM: Double,
)

@Entity(
    tableName = "route_profile",
    primaryKeys = ["route_id", "bin"],
    foreignKeys = [
        ForeignKey(
            entity = RouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["route_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class RouteProfileEntity(
    @ColumnInfo(name = "route_id") val routeId: Long,
    @ColumnInfo(name = "bin") val bin: Int,
    @ColumnInfo(name = "median_cum_time_s") val medianCumTimeS: Double,
    @ColumnInfo(name = "p25_s") val p25S: Double?,
    @ColumnInfo(name = "p75_s") val p75S: Double?,
    @ColumnInfo(name = "median_hr") val medianHr: Double?,
)

/** Which sessions a route was built from. Needed to keep route identity — and
 *  therefore the user's own names — stable across re-clustering. */
@Entity(
    tableName = "route_member",
    primaryKeys = ["route_id", "session_id"],
    foreignKeys = [
        ForeignKey(
            entity = RouteEntity::class,
            parentColumns = ["id"],
            childColumns = ["route_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("session_id")],
)
data class RouteMemberEntity(
    @ColumnInfo(name = "route_id") val routeId: Long,
    @ColumnInfo(name = "session_id") val sessionId: String,
)

/** US-10: the match log, one blob per session. */
@Entity(
    tableName = "match_log",
    primaryKeys = ["session_id"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class MatchLogEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is MatchLogEntity && other.sessionId == sessionId && other.payload.contentEquals(payload)

    override fun hashCode(): Int = 31 * sessionId.hashCode() + payload.contentHashCode()
}

/**
 * The session's lifecycle events, one blob per session.
 *
 * Separate from [MatchLogEntity] because they answer different questions and
 * arrive at different sizes: the match log is one entry per sample and says what
 * the matcher decided, while this is a handful of entries per session and says
 * what happened to the recording — paused, GPS lost, service restarted. The
 * watch writes both into the session file; without this table the second kind
 * was parsed at ingest and then dropped, and went with the file when the watch
 * deleted it.
 */
@Entity(
    tableName = "session_events",
    primaryKeys = ["session_id"],
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class SessionEventsEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is SessionEventsEntity && other.sessionId == sessionId && other.payload.contentEquals(payload)

    override fun hashCode(): Int = 31 * sessionId.hashCode() + payload.contentHashCode()
}

/**
 * The precomputed heatmap raster, one row for the whole database.
 *
 * A blob rather than a table of cells: it is written whole, read whole, and
 * never queried — 20,736 counts as rows would be a table nothing ever selects
 * from. Recomputed by `ClusterWorker` on the same trigger as the routes, because
 * it is derived from exactly the same two things and would otherwise drift.
 */
@Entity(tableName = "heatmap")
data class HeatmapEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = SINGLETON,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
    @ColumnInfo(name = "computed_at_utc") val computedAtUtc: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is HeatmapEntity && other.id == id &&
            other.computedAtUtc == computedAtUtc && other.payload.contentEquals(payload)

    override fun hashCode(): Int = 31 * id + payload.contentHashCode()

    companion object {
        /** There is only ever one heatmap, and this is its id. */
        const val SINGLETON = 0
    }
}

/** Small key/value table for the bundle version counter and similar. */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)
