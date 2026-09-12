package de.hecate.phone.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * §4: WorkManager, phone only, one-shot after ingest. Two jobs, both replaceable
 * — a burst of five sessions arriving at once should cluster once, not five
 * times, which `REPLACE` gives for free.
 */
object Jobs {

    /** Public because the UI observes this job to show that a re-clustering is
     *  under way; the name is the handle WorkManager knows it by. */
    const val CLUSTER = "hecate.cluster"
    private const val HEALTH_CONNECT = "hecate.healthconnect"

    fun enqueueClustering(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CLUSTER,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ClusterWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    fun enqueueHealthConnect(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            HEALTH_CONNECT,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<HealthConnectWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
                .build(),
        )
    }
}
