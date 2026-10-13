package de.hecate.phone.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.hecate.core.weather.UrlWeatherClient
import de.hecate.phone.AppGraph
import de.hecate.phone.weather.WeatherRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The weather backfill (A.33): every recording without weather gets it, on any
 * network. A run that could not finish — no answer, or the free tier spent for
 * the day — is retried with WorkManager's backoff, and picks up only what is
 * still missing.
 */
class WeatherWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        AppGraph.init(applicationContext)
        val outcome = WeatherRepository(AppGraph.db, UrlWeatherClient()).backfill()
        if (outcome.failed > 0 || outcome.rateLimited) Result.retry() else Result.success()
    }
}
