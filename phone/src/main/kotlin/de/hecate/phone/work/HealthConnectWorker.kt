package de.hecate.phone.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.Sample
import de.hecate.phone.AppGraph
import de.hecate.phone.db.HcWriteState
import de.hecate.phone.health.HealthConnectWriter

/**
 * US-2: writes pending sessions to Health Connect, retried by WorkManager.
 *
 * Runs independently of ingest and of clustering, because "failed Health Connect
 * writes are retried and never block local history" — a missing permission or a
 * Health Connect update in progress must not cost the user their history.
 */
class HealthConnectWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        val writer = AppGraph.healthConnect
        if (!writer.isAvailable) {
            Log.i(TAG, "Health Connect nicht verfügbar — später erneut")
            return Result.retry()
        }
        if (!writer.hasPermissions()) {
            // Not a failure of ours: the user has to grant these by hand (§6.4).
            Log.i(TAG, "Health-Connect-Berechtigungen fehlen — warte auf den Nutzer")
            return Result.success()
        }

        val dao = AppGraph.db.sessions()
        val pending = dao.byHcState(HcWriteState.PENDING) + dao.byHcState(HcWriteState.FAILED)
        if (pending.isEmpty()) return Result.success()

        var retryNeeded = false
        for (session in pending) {
            val samples = dao.samples(session.id).map {
                Sample(it.tOffsetMs, it.activeMs, it.lat, it.lon, it.altM, it.hrBpm, it.accuracyM, paused = it.paused)
            }
            val result = writer.write(
                sessionId = session.id,
                startedAtUtcMs = session.startedAtUtc,
                tzOffsetMinutes = session.tzOffsetMinutes,
                exerciseType = ExerciseType.valueOf(session.exerciseType),
                activeDurationMs = session.activeDurationMs,
                totalDistanceM = session.totalDistanceM,
                samples = samples,
            )
            when (result) {
                is HealthConnectWriter.WriteResult.Written ->
                    dao.setHcState(session.id, HcWriteState.WRITTEN, null)
                is HealthConnectWriter.WriteResult.Failed -> {
                    dao.setHcState(session.id, HcWriteState.FAILED, result.reason)
                    retryNeeded = retryNeeded || result.retryable
                }
                is HealthConnectWriter.WriteResult.NotPossible ->
                    dao.setHcState(session.id, HcWriteState.PENDING, result.reason)
            }
        }
        return if (retryNeeded) Result.retry() else Result.success()
    }

    private companion object {
        const val TAG = "HealthConnectWorker"
    }
}
