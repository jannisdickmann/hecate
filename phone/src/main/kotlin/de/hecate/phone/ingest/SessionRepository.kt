package de.hecate.phone.ingest

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import de.hecate.core.Tuning
import de.hecate.core.geo.Track
import de.hecate.core.io.BundleCodec
import de.hecate.core.model.MatchPhase
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.SessionSource
import de.hecate.phone.db.HcWriteState
import de.hecate.phone.db.HecateDatabase
import de.hecate.phone.db.MatchLogEntity
import de.hecate.phone.db.SampleEntity
import de.hecate.phone.db.SessionEntity
import de.hecate.phone.work.Jobs

/**
 * Everything that happens to a session once it reaches the phone.
 *
 * Ingest is idempotent on the session UUID and runs inside one transaction
 * (§5.4), which is what lets the watch retry blindly: a redelivered session is
 * recognised and acknowledged again rather than duplicated.
 */
class SessionRepository(
    private val context: Context,
    private val db: HecateDatabase,
) {

    sealed interface IngestResult {
        /** Newly stored. */
        data class Stored(val sessionId: String) : IngestResult

        /** Already present, or a duplicate by start time. Still acknowledge it. */
        data class Duplicate(val sessionId: String, val reason: String) : IngestResult

        data class Rejected(val reason: String) : IngestResult
    }

    /**
     * Store one session. Safe to call repeatedly with the same payload.
     *
     * @param triggerFollowUp whether to queue clustering and the Health Connect
     *        write. A bulk import sets it false and queues once at the end.
     */
    suspend fun ingest(session: RecordedSession, triggerFollowUp: Boolean = true): IngestResult {
        if (session.samples.size < 2) return IngestResult.Rejected("zu wenige Messpunkte")

        val dao = db.sessions()
        if (dao.countById(session.id) > 0) {
            return IngestResult.Duplicate(session.id, "bereits vorhanden")
        }

        // §7: the watch's own activity detection can produce a second session for
        // the same workout. Reject by start-time proximity rather than trusting
        // the user to have turned it off.
        val near = dao.near(session.header.startedAtUtcMs, Tuning.DUPLICATE_START_WINDOW_MS)
        val clash = near.firstOrNull {
            Track.likelyDuplicate(
                it.startedAtUtc, it.activeDurationMs,
                session.header.startedAtUtcMs, session.activeDurationMs,
            )
        }
        if (clash != null) {
            Log.i(TAG, "Sitzung ${session.id} ist ein Duplikat von ${clash.id}")
            return IngestResult.Duplicate(session.id, "Duplikat von ${clash.id}")
        }

        val hrValues = session.samples.mapNotNull { it.hrBpm }
        val avgHr = if (hrValues.isEmpty()) null else hrValues.average()

        val committedAt = session.matchLog.firstOrNull { it.phase == MatchPhase.COMMITTED }?.travelledM
        val matchedRoute = session.matchLog.lastOrNull { it.committedRouteId != null }?.committedRouteId

        val entity = SessionEntity(
            id = session.id,
            startedAtUtc = session.header.startedAtUtcMs,
            tzOffsetMinutes = session.header.tzOffsetMinutes,
            exerciseType = session.header.exerciseType.name,
            activeDurationMs = session.activeDurationMs,
            totalDistanceM = session.footer?.totalDistanceM ?: trackDistance(session),
            source = session.header.source.name,
            // US-9: imported sessions are not re-written to Health Connect.
            hcWriteState = if (session.header.source == SessionSource.IMPORT_TCX) {
                HcWriteState.SKIPPED
            } else {
                HcWriteState.PENDING
            },
            matchedRouteId = matchedRoute,
            committedAtM = committedAt,
            avgHr = avgHr,
            ingestedAt = System.currentTimeMillis(),
        )

        db.withTransaction {
            dao.insert(entity)
            dao.insertSamples(
                session.samples.map {
                    SampleEntity(
                        sessionId = session.id,
                        tOffsetMs = it.tOffsetMs,
                        activeMs = it.activeMs,
                        lat = it.lat,
                        lon = it.lon,
                        altM = it.altM,
                        hrBpm = it.hrBpm,
                        accuracyM = it.accuracyM,
                        paused = it.paused,
                    )
                },
            )
            if (session.matchLog.isNotEmpty()) {
                dao.upsertMatchLog(
                    MatchLogEntity(
                        sessionId = session.id,
                        payload = BundleCodec.encodeMatchLog(session.matchLog).toByteArray(Charsets.UTF_8),
                    ),
                )
            }
        }

        Log.i(TAG, "Sitzung ${session.id} gespeichert (${session.samples.size} Messpunkte)")
        if (triggerFollowUp) queueFollowUp()
        return IngestResult.Stored(session.id)
    }

    /** §5.4: clustering is the only deferred job, and the Health Connect write is
     *  retried independently so a failure there never blocks local history. */
    fun queueFollowUp() {
        Jobs.enqueueHealthConnect(context)
        Jobs.enqueueClustering(context)
    }

    private fun trackDistance(session: RecordedSession): Double =
        Track.of(session)?.totalDistanceM ?: 0.0

    suspend fun sessionCount(): Int = db.sessions().count()

    private companion object {
        const val TAG = "SessionRepository"
    }
}
