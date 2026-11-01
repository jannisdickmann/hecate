package de.hecate.phone.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.hecate.phone.AppGraph

/**
 * The update check (A.34): once a day, on any network, and once on every app
 * start. A failed check is not retried early — tomorrow's is soon enough for a
 * release, and GitHub's unauthenticated API allows sixty requests an hour.
 */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        AppGraph.updates.check()
        return Result.success()
    }
}
