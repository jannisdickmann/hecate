package de.hecate.phone.weather

import android.util.Log
import de.hecate.core.weather.WeatherClient
import de.hecate.core.weather.WeatherFetch
import de.hecate.core.weather.WeatherRateLimited
import de.hecate.core.weather.WeatherSummary
import de.hecate.core.weather.WeatherTrack
import de.hecate.phone.db.HecateDatabase
import de.hecate.phone.db.SessionWeatherEntity
import de.hecate.phone.db.Settings
import de.hecate.phone.db.toSample
import java.io.IOException

/**
 * Fills in the weather of every recording that has none (A.33).
 *
 * One request per recording, newest first, one at a time with a pause between
 * them: the free tier allows thousands a day, and a history backfill is a few
 * hundred.
 */
class WeatherRepository(
    private val db: HecateDatabase,
    private val client: WeatherClient,
    private val pauseMs: Long = PAUSE_MS,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = { if (it > 0) Thread.sleep(it) },
) {

    /** How a backfill went: [fetched] recordings now have weather, [failed]
     *  could not get it this time, and [rateLimited] means the server asked us
     *  to stop for now. */
    data class Outcome(val fetched: Int, val failed: Int, val rateLimited: Boolean)

    suspend fun enabled(): Boolean = db.meta().get(Settings.WEATHER_ENABLED) != "0"

    suspend fun backfill(): Outcome {
        if (!enabled()) return Outcome(0, 0, false)
        val missing = db.weather().missing()
        var fetched = 0
        var failed = 0
        for ((i, session) in missing.withIndex()) {
            // The switch can be turned off while a long backfill runs; it
            // means "stop asking", so it is honoured between requests.
            if (!enabled()) break
            if (i > 0) sleep(pauseMs)
            val samples = db.sessions().samples(session.id).map { it.toSample() }
            try {
                val track = WeatherFetch.track(client, session.startedAtUtc, samples, now())
                store(session.id, track)
                fetched++
            } catch (e: WeatherRateLimited) {
                Log.i(TAG, "Open-Meteo-Kontingent erschöpft nach $fetched Abrufen")
                return Outcome(fetched, failed + 1, rateLimited = true)
            } catch (e: IOException) {
                Log.w(TAG, "Wetter für ${session.id}: ${e.message}")
                failed++
            } catch (e: RuntimeException) {
                // A malformed answer for one recording must not stop the rest.
                Log.w(TAG, "Wetter für ${session.id} unlesbar: ${e.message}")
                failed++
            }
        }
        Log.i(TAG, "Wetter: $fetched abgerufen, $failed fehlgeschlagen, ${missing.size} offen")
        return Outcome(fetched, failed, rateLimited = false)
    }

    private suspend fun store(sessionId: String, track: WeatherTrack) {
        val summary = WeatherSummary.of(track.readings)
        db.weather().put(
            SessionWeatherEntity(
                sessionId = sessionId,
                payload = WeatherTrack.encode(track),
                code = summary?.code,
                tempMinC = summary?.minTempC,
                tempMaxC = summary?.maxTempC,
                precipMm = summary?.precipMm,
                isDay = summary?.isDay,
                fetchedAtUtc = track.fetchedAtUtcMs,
            ),
        )
    }

    companion object {
        private const val TAG = "WeatherRepository"
        const val PAUSE_MS: Long = 500
    }
}
