package de.hecate.phone.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import de.hecate.phone.map.BasemapPrefetchWorker
import java.util.concurrent.TimeUnit

/**
 * §4: WorkManager, phone only, one-shot after ingest — and, since A.34, one
 * daily job, the update check. The one-shot jobs are replaceable — a burst of five sessions arriving at once should cluster once,
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

    /** Sends a watch release to the watch (A.35). REPLACE: a second tap
     *  restarts rather than queues a second 30 MB transfer. */
    fun enqueueWatchInstall(context: Context, apkUrl: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WATCH_INSTALL,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WatchInstallWorker>()
                .setInputData(workDataOf(WatchInstallWorker.KEY_URL to apkUrl))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    const val WATCH_INSTALL = "hecate.update.watch"

    /** Downloads and installs a phone release (A.36). REPLACE, as for the
     *  watch: a second tap restarts the download. */
    fun enqueueSelfUpdate(context: Context, apkUrl: String) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            SELF_UPDATE,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SelfUpdateWorker>()
                .setInputData(workDataOf(SelfUpdateWorker.KEY_URL to apkUrl))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    const val SELF_UPDATE = "hecate.update.phone"

    private const val UPDATE_DAILY = "hecate.update.daily"

    /** Public so the settings card can say a check is under way. */
    const val UPDATE_NOW = "hecate.update.now"

    /**
     * The update check (A.34): daily, plus once now — on every app start and
     * whenever the watch reports a different build. KEEP for the daily one so
     * starting the app does not keep pushing it back a day; REPLACE for the
     * immediate one, which is idempotent.
     */
    fun enqueueUpdateCheck(context: Context) {
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val work = WorkManager.getInstance(context)
        work.enqueueUniquePeriodicWork(
            UPDATE_DAILY,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS).setConstraints(online).build(),
        )
        work.enqueueUniqueWork(
            UPDATE_NOW,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(online).build(),
        )
    }
}
