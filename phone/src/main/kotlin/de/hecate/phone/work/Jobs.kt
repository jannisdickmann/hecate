package de.hecate.phone.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import de.hecate.phone.map.BasemapPrefetchWorker
import java.util.concurrent.TimeUnit

/**
 * §4: WorkManager, phone only, one-shot after ingest. Three jobs now, all
 * replaceable — a burst of five sessions arriving at once should cluster once,
 * not five times, which `REPLACE` gives for free.
 */
object Jobs {

    /** Public because the UI observes this job to show that a re-clustering is
     *  under way; the name is the handle WorkManager knows it by. */
    const val CLUSTER = "hecate.cluster"
    private const val HEALTH_CONNECT = "hecate.healthconnect"

    /** Public for the same reason [CLUSTER] is: the settings card watches this
     *  job to say that a prefetch is under way. */
    const val MAP_PREFETCH = "hecate.map.prefetch"

    fun enqueueClustering(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            CLUSTER,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ClusterWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build(),
        )
    }

    /**
     * „Karte vorladen": the OpenStreetMap ground around every recording.
     *
     * The constraints are the whole point of making this a job. It is a few
     * hundred megabytes over about forty polite requests, which is not something
     * to start on a train — so it waits for an unmetered network and a battery
     * that is not low, and it is resumable, because every completed tile is
     * already on disk.
     */
    fun enqueueMapPrefetch(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            MAP_PREFETCH,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BasemapPrefetchWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
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
