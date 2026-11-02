package de.hecate.phone.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import de.hecate.phone.update.UpdateNotifier
import de.hecate.phone.update.WatchInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sends a watch release to the watch (A.35). Not retried: the user asked
 *  for it now, and the notification says whether it worked.
 *
 *  Runs in the foreground: ~30 MB over Bluetooth can outlast the ten minutes
 *  WorkManager gives a background worker. If the system refuses the
 *  foreground start, the transfer still runs and may simply be cut short. */
class WatchInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val notifier = UpdateNotifier(applicationContext)
        runCatching { setForeground(getForegroundInfo()) }.onFailure {
            Log.w(TAG, "Kein Vordergrund für die Übertragung", it)
            notifier.watchTransfer(UpdateNotifier.Transfer.SENDING)
        }
        val outcome = WatchInstaller(applicationContext).send(url)
        notifier.watchTransfer(
            when (outcome) {
                WatchInstaller.Outcome.SENT -> UpdateNotifier.Transfer.SENT
                WatchInstaller.Outcome.NO_WATCH -> UpdateNotifier.Transfer.NO_WATCH
                WatchInstaller.Outcome.WATCH_TOO_OLD -> UpdateNotifier.Transfer.WATCH_TOO_OLD
                WatchInstaller.Outcome.REJECTED -> UpdateNotifier.Transfer.REJECTED
                WatchInstaller.Outcome.FAILED -> UpdateNotifier.Transfer.FAILED
            },
        )
        if (outcome == WatchInstaller.Outcome.SENT) Result.success() else Result.failure()
    }

    /** Its own id: the foreground notification goes when the worker ends,
     *  and the result on [UpdateNotifier.TRANSFER_ID] must stay. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = UpdateNotifier(applicationContext)
            .transferNotification(UpdateNotifier.Transfer.SENDING)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(FOREGROUND_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(FOREGROUND_ID, notification)
        }
    }

    companion object {
        const val KEY_URL = "url"
        private const val TAG = "WatchInstallWorker"
        private const val FOREGROUND_ID = UpdateNotifier.TRANSFER_ID + 1
    }
}
