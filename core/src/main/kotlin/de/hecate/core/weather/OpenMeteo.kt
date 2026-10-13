package de.hecate.core.weather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.zip.GZIPInputStream

/**
 * Historical weather from Open-Meteo (open-meteo.com): open source, no key, and
 * CC BY 4.0 data — which is why its name is written under every weather strip.
 *
 * Two endpoints, chosen by the recording's date:
 *
 * - **Historical Forecast API**, `minutely_15`, for anything since
 *   [FIFTEEN_MINUTE_FROM_UTC_MS]. The archived runs of the high-resolution
 *   models, so for Central Europe a 15-minute, ~2 km picture of the day.
 * - **Archive API** (ERA5 reanalysis), `hourly`, for everything older — a
 *   takeout reaches back years, and ERA5 back to 1940.
 *
 * What is sent: dates (never a time of day) and positions rounded to
 * [WeatherPlan.GRID_DEG], deduplicated. See Appendix A.30.
 */
object OpenMeteo {

    const val ATTRIBUTION: String = "Wetterdaten: Open-Meteo.com (CC BY 4.0)"

    /** 2022-01-01. The archived 15-minute runs are complete from here on. */
    const val FIFTEEN_MINUTE_FROM_UTC_MS: Long = 1_640_995_200_000L

    private const val FORECAST_URL = "https://historical-forecast-api.open-meteo.com/v1/forecast"
    private const val ARCHIVE_URL = "https://archive-api.open-meteo.com/v1/archive"
    private const val VARIABLES = "temperature_2m,precipitation,weather_code,is_day"

    /** One query: its URL, and what is needed to read the answer back. */
    data class Request(
        val url: String,
        val resolution: String,
        val stepS: Long,
        /** The distinct rounded positions, in the order the response lists them. */
        val locations: List<Pair<Double, Double>>,
    )

    /** Null when there is nothing to ask about. */
    fun request(startedAtUtcMs: Long, probes: List<WeatherPlan.Probe>): Request? {
        if (probes.isEmpty()) return null
        val locations = probes.map { it.lat to it.lon }.distinct()
        val fine = startedAtUtcMs >= FIFTEEN_MINUTE_FROM_UTC_MS
        val from = date(startedAtUtcMs + probes.first().tOffsetMs)
        val to = date(startedAtUtcMs + probes.last().tOffsetMs)
        val url = buildString {
            append(if (fine) FORECAST_URL else ARCHIVE_URL)
            append("?latitude=").append(locations.joinToString(",") { coord(it.first) })
            append("&longitude=").append(locations.joinToString(",") { coord(it.second) })
            append("&start_date=").append(from)
            append("&end_date=").append(to)
            append(if (fine) "&minutely_15=" else "&hourly=").append(VARIABLES)
            append("&timezone=GMT&timeformat=unixtime")
        }
        return Request(url, if (fine) "15min" else "hourly", if (fine) 900L else 3600L, locations)
    }

    /**
     * The readings for [probes] out of the response [body] to [request].
     *
     * Open-Meteo's sums are over the step *ending* at the stamped time, and its
     * instantaneous values are at the stamp. So the precipitation and the code
     * for a moment come from the first step stamped at or after it, and the
     * temperature is interpolated between the two stamps around it.
     */
    fun parse(
        body: String,
        request: Request,
        startedAtUtcMs: Long,
        probes: List<WeatherPlan.Probe>,
    ): List<WeatherReading> {
        val root = Json.parseToJsonElement(body)
        // One location answers with an object, several with an array of them.
        val objects: List<JsonObject> = when (root) {
            is JsonArray -> root.map { it.jsonObject }
            is JsonObject -> {
                if (root["error"]?.jsonPrimitive?.content == "true") {
                    throw WeatherException("Open-Meteo: ${root["reason"]?.jsonPrimitive?.content}")
                }
                listOf(root)
            }
            else -> throw WeatherException("Open-Meteo: unerwartete Antwort")
        }
        if (objects.size != request.locations.size) {
            throw WeatherException("Open-Meteo: ${objects.size} Orte statt ${request.locations.size}")
        }
        val key = if (request.resolution == "15min") "minutely_15" else "hourly"
        val series = objects.map { Series.of(it[key]?.jsonObject ?: throw WeatherException("Open-Meteo: $key fehlt")) }
        val index = request.locations.withIndex().associate { (i, loc) -> loc to i }

        return probes.map { p ->
            val s = series[index.getValue(p.lat to p.lon)]
            val t = (startedAtUtcMs + p.tOffsetMs) / 1000
            val after = s.time.indexOfFirst { it >= t }
            val stepIdx = after.takeIf { it >= 0 }
            WeatherReading(
                tOffsetMs = p.tOffsetMs,
                lat = p.lat,
                lon = p.lon,
                tempC = s.interpolateTemp(t),
                precipMmH = stepIdx?.let { s.precip[it] }?.let { it * 3600.0 / request.stepS },
                code = stepIdx?.let { s.code[it] }?.toInt(),
                isDay = s.nearest(t)?.let { s.isDay[it] }?.let { it >= 0.5 },
            )
        }
    }

    private class Series(
        val time: LongArray,
        val temp: Array<Double?>,
        val precip: Array<Double?>,
        val code: Array<Double?>,
        val isDay: Array<Double?>,
    ) {
        fun interpolateTemp(t: Long): Double? {
            val i = time.indexOfFirst { it >= t }
            if (i < 0) return temp.lastOrNull()
            if (time[i] == t || i == 0) return temp[i]
            val a = temp[i - 1] ?: return temp[i]
            val b = temp[i] ?: return a
            val f = (t - time[i - 1]).toDouble() / (time[i] - time[i - 1])
            return a + (b - a) * f
        }

        fun nearest(t: Long): Int? = time.indices.minByOrNull { kotlin.math.abs(time[it] - t) }

        companion object {
            fun of(o: JsonObject): Series {
                val time = o["time"]?.jsonArray?.map { it.jsonPrimitive.longOrNull ?: 0L }?.toLongArray()
                    ?: throw WeatherException("Open-Meteo: time fehlt")
                fun col(name: String): Array<Double?> {
                    val a = o[name]?.jsonArray ?: return arrayOfNulls(time.size)
                    return Array(time.size) { i -> a.getOrNull(i).number() }
                }
                return Series(time, col("temperature_2m"), col("precipitation"), col("weather_code"), col("is_day"))
            }

            private fun JsonElement?.number(): Double? =
                if (this == null || this is JsonNull) null else jsonPrimitive.doubleOrNull
        }
    }

    private fun date(utcMs: Long): String = Instant.ofEpochMilli(utcMs).atZone(ZoneOffset.UTC).toLocalDate().toString()

    /** Two decimals is all a [WeatherPlan.GRID_DEG] grid has. */
    private fun coord(v: Double): String = String.format(Locale.ROOT, "%.2f", v)
}

/** Something went wrong asking for weather. */
open class WeatherException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The server said "not now" — the free tier's daily or per-minute budget is
 *  spent. The caller retries later rather than counting it as a failure. */
class WeatherRateLimited(val retryAfterMs: Long) : WeatherException("Open-Meteo: Kontingent erschöpft")

/** Where weather comes from. One method, so that no test opens a socket — the
 *  same reasoning as [de.hecate.core.map.overpass.OverpassClient]. */
fun interface WeatherClient {
    fun fetch(url: String): String
}

/** The real client over `HttpURLConnection`, which Android and a laptop share.
 *  Sends the URL and a User-Agent that says what this is, and nothing that says
 *  who. */
class UrlWeatherClient(
    private val open: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
) : WeatherClient {
    override fun fetch(url: String): String {
        val c = open(url)
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 60_000
            c.setRequestProperty("Accept-Encoding", "gzip")
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = c.responseCode
            if (code == 429) {
                throw WeatherRateLimited(c.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000) ?: 0L)
            }
            val gz = c.contentEncoding?.contains("gzip", ignoreCase = true) == true
            if (code !in 200..299) {
                val err = runCatching {
                    (if (gz) GZIPInputStream(c.errorStream) else c.errorStream).use { it.readBytes().toString(Charsets.UTF_8) }
                }.getOrNull()
                throw WeatherException("Open-Meteo: HTTP $code ${err.orEmpty().take(200)}".trim())
            }
            val stream = if (gz) GZIPInputStream(c.inputStream) else c.inputStream
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val USER_AGENT: String = "hecate-weather/1"
    }
}

/** A recording's samples in, its stored weather out: plan, ask, read. */
object WeatherFetch {
    fun track(client: WeatherClient, startedAtUtcMs: Long, samples: List<de.hecate.core.model.Sample>, nowUtcMs: Long): WeatherTrack {
        val probes = WeatherPlan.probes(samples)
        val request = OpenMeteo.request(startedAtUtcMs, probes)
            ?: return WeatherTrack(emptyList(), "none", nowUtcMs)
        val body = client.fetch(request.url)
        return WeatherTrack(OpenMeteo.parse(body, request, startedAtUtcMs, probes), request.resolution, nowUtcMs)
    }
}
