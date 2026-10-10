package de.hecate.phone.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import de.hecate.core.Tuning
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.SessionSource

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
    @ColumnInfo(name = "exercise_type") val exerciseType: ExerciseType,
    @ColumnInfo(name = "active_duration_ms") val activeDurationMs: Long,
    @ColumnInfo(name = "total_distance_m") val totalDistanceM: Double,
    @ColumnInfo(name = "source") val source: SessionSource,
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
    @ColumnInfo(name = "exercise_type") val exerciseType: ExerciseType,
    @ColumnInfo(name = "length_m") val lengthM: Double,
    @ColumnInfo(name = "member_count") val memberCount: Int,
    @ColumnInfo(name = "medoid_session_id") val medoidSessionId: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "active") val active: Boolean = true,
    /** The user's manual ranking, −2..2, zero meaning "no opinion". Breaks ties
     *  the evidence cannot break: see RouteEntry.priority in :core. Survives
     *  re-clustering, like the name. */
    @ColumnInfo(name = "priority", defaultValue = "0")
    val priority: Int = Tuning.DEFAULT_ROUTE_PRIORITY,
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
    /** Median altitude [m] over the members at this bin — the route's elevation
     *  profile. Null for a bin no member recorded an altitude for, and for every
     *  bin of a route last clustered before schema v6. */
    @ColumnInfo(name = "median_alt_m") val medianAltM: Double? = null,
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
 * The series a session's sensors produced on their own schedules.
 *
 * One row per session holding all three, in the same shape as [SessionEventsEntity]
 * and for the same reason: the watch writes them into the session file, and
 * anything not kept here dies with that file when the watch deletes it.
 *
 * Until v8 none of this was kept. Heart rate was merged into the sample rows at
 * ingest and the series discarded, which is why the merge tolerance can never be
 * re-derived for a session already stored — the readings it would need are gone.
 * Altitude would have been worse: the barometer reports about once every
 * half-minute against a position a second, so a merge keeps roughly one sample in
 * seven and throws the rest away. Keeping the series means the join stays a
 * decision made on the way out, and changing it costs a redraw rather than a
 * migration.
 *
 * Null where a session has no such series: every import carries its values per
 * position instead, and so does every recording made before the watch wrote one.
 */
@Entity(
    tableName = "session_series",
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
data class SessionSeriesEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "heart_rates", typeAffinity = ColumnInfo.BLOB) val heartRates: ByteArray?,
    @ColumnInfo(name = "altitudes", typeAffinity = ColumnInfo.BLOB) val altitudes: ByteArray?,
    @ColumnInfo(name = "speeds", typeAffinity = ColumnInfo.BLOB) val speeds: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is SessionSeriesEntity &&
            other.sessionId == sessionId &&
            other.heartRates.contentEquals(heartRates) &&
            other.altitudes.contentEquals(altitudes) &&
            other.speeds.contentEquals(speeds)

    override fun hashCode(): Int {
        var h = sessionId.hashCode()
        h = 31 * h + heartRates.contentHashCode()
        h = 31 * h + altitudes.contentHashCode()
        h = 31 * h + speeds.contentHashCode()
        return h
    }
}


/**
 * A precomputed heatmap raster: one row per picture, and there are two of them
 * ([CARD] and [FULL]).
 *
 * A blob rather than a table of cells: it is written whole, read whole, and
 * never queried — millions of counts as rows would be a table nothing ever
 * selects from. Recomputed by `ClusterWorker` on the same trigger as the routes,
 * because it is derived from exactly the same two things and would otherwise
 * drift.
 */
@Entity(tableName = "heatmap")
data class HeatmapEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: Int = CARD,
    @ColumnInfo(name = "payload", typeAffinity = ColumnInfo.BLOB) val payload: ByteArray,
    @ColumnInfo(name = "computed_at_utc") val computedAtUtc: Long,
) {
    override fun equals(other: Any?): Boolean =
        other is HeatmapEntity && other.id == id &&
            other.computedAtUtc == computedAtUtc && other.payload.contentEquals(payload)

    override fun hashCode(): Int = 31 * id + payload.contentHashCode()

    companion object {
        /**
         * The card on the routes list: the routes' own extent, 16:9.
         *
         * Two rows rather than one, because there are two pictures of two
         * different pieces of ground (`Heatmap.Extent`), and the routes screen
         * must not pay for inflating the big one to show the small one. Two rows
         * and not two tables or two columns, because the schema already has an
         * id here — so this costs no migration at all.
         */
        const val CARD = 0

        /** The full-screen view: everything inside the radius, in its own shape.
         *  Read only when that view is opened. */
        const val FULL = 1
    }
}

/** Small key/value table for the bundle version counter and similar. */
@Entity(tableName = "meta")
data class MetaEntity(
    @PrimaryKey @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
)
