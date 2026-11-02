package de.hecate.phone.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.hecate.phone.update.UpdateNotifier
import de.hecate.phone.update.WatchInstaller
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Sends a watch release to the watch (A.35). Not retried: the user asked
 *  for it now, and the notification says whether it worked. */
class WatchInstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url = inputData.getString(KEY_URL) ?: return@withContext Result.failure()
        val notifier = UpdateNotifier(applicationContext)
        notifier.watchTransfer(UpdateNotifier.Transfer.SENDING)
        val outcome = WatchInstaller(applicationContext).send(url)
        notifier.watchTransfer(
            when (outcome) {
                WatchInstaller.Outcome.SENT -> UpdateNotifier.Transfer.SENT
                WatchInstaller.Outcome.NO_WATCH -> UpdateNotifier.Transfer.NO_WATCH
                WatchInstaller.Outcome.FAILED -> UpdateNotifier.Transfer.FAILED
            },
        )
        if (outcome == WatchInstaller.Outcome.SENT) Result.success() else Result.failure()
    }

    companion object {
        const val KEY_URL = "url"
    }
}
