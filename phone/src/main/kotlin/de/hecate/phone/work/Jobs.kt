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
    fun enqueueMapPrefetch(context: Context, restart: Boolean = false) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            MAP_PREFETCH,
            // KEEP by default, so a second tap leaves a waiting job alone.
            // REPLACE when the user asks for it now: a job that was interrupted
            // — by an app update, say — sits out a 15-minute backoff before it
            // runs again, with WLAN right there. Replacing it costs nothing,
            // since a prefetch only ever fetches what is still missing.
            if (restart) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
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

    /** Public so the settings card can say a backfill is under way. */
    const val WEATHER = "hecate.weather"

    /**
     * The weather backfill (A.33). Any network: one recording is a few
     * kilobytes. Appended rather than replaced, so a recording arriving
     * mid-backfill waits for the running pass instead of restarting it — the
     * next pass only asks for what is still missing.
     */
    fun enqueueWeather(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WEATHER,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<WeatherWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
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
