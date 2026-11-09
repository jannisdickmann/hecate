package de.hecate.phone.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import de.hecate.phone.update.SelfUpdate
import de.hecate.phone.update.UpdateNotifier
import de.hecate.phone.update.WatchInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Downloads a phone release and hands it to the system installer (A.36).
 * Not retried: the user asked for it now, and the notification says how it
 * went.
 *
 * Installing replaces this process, possibly before WorkManager records this
 * worker as done; it may then run once more in the new build, find the APK
 * no newer than itself, and must end quietly.
 */
class SelfUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val context = applicationContext
        if (!SelfUpdate.canInstall(context)) {
            SelfUpdate.notify(context, SelfUpdate.State.NOT_ALLOWED, SelfUpdate.allowIntent(context))
            return@withContext Result.failure()
        }
        val notifier = UpdateNotifier(context)
        var progressId = FOREGROUND_ID
        runCatching { setForeground(getForegroundInfo()) }.onFailure {
            Log.w(TAG, "Kein Vordergrund für den Download", it)
            progressId = SelfUpdate.NOTIFICATION_ID
        }
        val apk = File(context.cacheDir, "self-update.apk")
        try {
            var lastPost = 0L
            WatchInstaller.download(url, apk) { done, total ->
                val now = System.currentTimeMillis()
                val percent = WatchInstaller.percent(done, total)
                if (percent != 100 && now - lastPost < PROGRESS_INTERVAL_MS) return@download
                lastPost = now
                UpdateNotifier.post(
                    context,
                    progressId,
                    notifier.transferNotification(UpdateNotifier.Transfer.DOWNLOADING, percent),
                )
            }
            val (verdict, info) = SelfUpdate.verify(context, apk)
            Log.i(TAG, "APK geladen (${apk.length()} B): $verdict ${info?.versionName}")
            when (verdict) {
                SelfUpdate.Verdict.NOT_NEWER -> {
                    SelfUpdate.dismiss(context)
                    Result.success()
                }
                SelfUpdate.Verdict.INVALID -> {
                    SelfUpdate.notify(context, SelfUpdate.State.FAILED)
                    Result.failure()
                }
                SelfUpdate.Verdict.OK -> {
                    SelfUpdate.install(context, apk)
                    Result.success()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Update nicht installiert", e)
            SelfUpdate.notify(context, SelfUpdate.State.FAILED)
            Result.failure()
        } finally {
            // Without the foreground, the progress sat on the result's id: a
            // result posted above replaced it, and a quiet end dismissed it.
            apk.delete()
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        FOREGROUND_ID,
        UpdateNotifier(applicationContext).transferNotification(UpdateNotifier.Transfer.DOWNLOADING),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    companion object {
        const val KEY_URL = "url"
        private const val TAG = "SelfUpdateWorker"
        private const val FOREGROUND_ID = SelfUpdate.NOTIFICATION_ID + 3
        private const val PROGRESS_INTERVAL_MS = 1_000L
    }
}
