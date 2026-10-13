package de.hecate.phone.ingest

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import de.hecate.core.geo.Track
import de.hecate.core.model.MatchPhase
import de.hecate.core.model.RecordedSession
import de.hecate.core.recorder.AutoStop
import de.hecate.phone.db.HcWriteState
import de.hecate.phone.db.HecateDatabase
import de.hecate.phone.db.SessionEntity
import de.hecate.phone.db.duplicateOf
import de.hecate.phone.db.insertBlobs
import de.hecate.phone.db.mergedSamples
import de.hecate.phone.db.toEntity
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

        /**
         * Not stored. [permanent] separates "this payload can never be stored" —
         * a recording with no positions in it — from "something went wrong here",
         * because the two deserve opposite treatment on the wire: the first
         * should be acknowledged so the watch stops offering it, and the second
         * must not be, so that a bug here cannot throw a real recording away.
         */
        data class Rejected(val reason: String, val permanent: Boolean = false) : IngestResult
    }

    /**
     * Store one session. Safe to call repeatedly with the same payload.
     *
     * @param triggerFollowUp whether to queue clustering and the Health Connect
     *        write. A bulk import sets it false and queues once at the end.
     */
    suspend fun ingest(incoming: RecordedSession, triggerFollowUp: Boolean = true): IngestResult {
        // The one place the auto-stop's cut is applied (A.30). The watch cannot
        // apply it itself — its file is append-only, which is what makes a
        // recording survive a process death — so it records where the activity
        // ended and the phone drops the rest here, before anything else in the
        // system has seen it. Everything downstream of ingest therefore reads a
        // session that contains the workout and not the drive home: the track,
        // the profile, the clusterer that learns routes from it, the heatmap,
        // Health Connect and every backup.
        val discarded = AutoStop.discardedSamples(incoming)
        val session = AutoStop.trim(incoming)
        if (discarded > 0) {
            Log.i(
                TAG,
                "Sitzung ${session.id}: $discarded Messpunkte nach dem automatischen Stopp verworfen",
            )
        }

        if (session.samples.size < 2) {
            return IngestResult.Rejected("zu wenige Messpunkte", permanent = true)
        }

        val dao = db.sessions()
        if (dao.countById(session.id) > 0) {
            return IngestResult.Duplicate(session.id, "bereits vorhanden")
        }

        // §7: the watch's own activity detection can produce a second session for
        // the same workout. Reject by start-time proximity rather than trusting
        // the user to have turned it off.
        val clash = dao.duplicateOf(session.header.startedAtUtcMs, session.activeDurationMs)
        if (clash != null) {
            Log.i(TAG, "Sitzung ${session.id} ist ein Duplikat von ${clash.id}")
            return IngestResult.Duplicate(session.id, "Duplikat von ${clash.id}")
        }

        // The two sensors are recorded separately and joined here, once, on the
        // way in.
        val samples = session.mergedSamples()

        val hrValues = samples.mapNotNull { it.hrBpm }
        val avgHr = if (hrValues.isEmpty()) null else hrValues.average()

        val committedAt = session.matchLog.firstOrNull { it.phase == MatchPhase.COMMITTED }?.travelledM
        val matchedRoute = session.matchLog.lastOrNull { it.committedRouteId != null }?.committedRouteId

        val entity = SessionEntity(
            id = session.id,
            startedAtUtc = session.header.startedAtUtcMs,
            endedAtUtc = session.footer?.endedAtUtcMs,
            tzOffsetMinutes = session.header.tzOffsetMinutes,
            exerciseType = session.header.exerciseType,
            activeDurationMs = session.activeDurationMs,
            totalDistanceM = session.footer?.totalDistanceM ?: trackDistance(session),
            source = session.header.source,
            // US-9: imported sessions are not re-written to Health Connect.
            hcWriteState = if (session.header.source.isImport) {
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
            dao.insertSamples(samples.map { it.toEntity(session.id) })
            dao.insertBlobs(session.id, session)
        }

        Log.i(
            TAG,
            "Sitzung ${session.id} gespeichert (${samples.size} Messpunkte, " +
                "${session.heartRates.size} Pulswerte, ${hrValues.size} davon zugeordnet, " +
                "${session.altitudes.size} Höhenwerte, ${session.speeds.size} Geschwindigkeiten)",
        )
        if (triggerFollowUp) queueFollowUp()
        return IngestResult.Stored(session.id)
    }

    /** §5.4: clustering, the Health Connect write and the weather (A.30) are
     *  deferred, each retried independently so that a failure in one never
     *  blocks local history or the others. */
    fun queueFollowUp() {
        Jobs.enqueueHealthConnect(context)
        Jobs.enqueueClustering(context)
        Jobs.enqueueWeather(context)
    }

    private fun trackDistance(session: RecordedSession): Double =
        Track.of(session)?.totalDistanceM ?: 0.0

    private companion object {
        const val TAG = "SessionRepository"
    }
}
