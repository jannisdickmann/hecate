package de.hecate.core.weather

import de.hecate.core.model.Sample
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * The sky in one of the handful of shapes a symbol can draw.
 *
 * The WMO code an API hands back has twenty-eight values, most of which differ
 * only in intensity. A symbol is not the place for intensity — the
 * precipitation figure beside it is — so the codes fold into these, and the
 * full wording stays available through [WeatherCode.label].
 */
enum class Sky {
    CLEAR,
    PARTLY_CLOUDY,
    CLOUDY,
    FOG,
    DRIZZLE,
    RAIN,
    SHOWERS,
    SLEET,
    SNOW,
    THUNDER,
    ;

    /** Something is falling: what the summary line has to mention even when
     *  it was not most of the time. */
    val wet: Boolean get() = this >= DRIZZLE
}

/** The WMO 4677 subset Open-Meteo reports as `weather_code`. */
object WeatherCode {

    fun sky(code: Int): Sky = when (code) {
        0 -> Sky.CLEAR
        1, 2 -> Sky.PARTLY_CLOUDY
        3 -> Sky.CLOUDY
        45, 48 -> Sky.FOG
        51, 53, 55 -> Sky.DRIZZLE
        56, 57, 66, 67 -> Sky.SLEET
        61, 63, 65 -> Sky.RAIN
        71, 73, 75, 77, 85, 86 -> Sky.SNOW
        80, 81, 82 -> Sky.SHOWERS
        95, 96, 99 -> Sky.THUNDER
        // An unknown code is weather we cannot draw. Cloud is the least
        // committal thing to claim about it.
        else -> Sky.CLOUDY
    }

    /** The German wording of a code, as the detail screen writes it. */
    fun label(code: Int): String = when (code) {
        0 -> "Klar"
        1 -> "Überwiegend klar"
        2 -> "Teilweise bewölkt"
        3 -> "Bedeckt"
        45 -> "Nebel"
        48 -> "Raureifnebel"
        51 -> "Leichter Niesel"
        53 -> "Niesel"
        55 -> "Starker Niesel"
        56, 57 -> "Gefrierender Niesel"
        61 -> "Leichter Regen"
        63 -> "Regen"
        65 -> "Starker Regen"
        66, 67 -> "Gefrierender Regen"
        71 -> "Leichter Schneefall"
        73 -> "Schneefall"
        75 -> "Starker Schneefall"
        77 -> "Schneegriesel"
        80 -> "Leichte Regenschauer"
        81 -> "Regenschauer"
        82 -> "Heftige Regenschauer"
        85, 86 -> "Schneeschauer"
        95 -> "Gewitter"
        96, 99 -> "Gewitter mit Hagel"
        else -> "Unbekannt"
    }
}

/**
 * The weather at one moment of a recording.
 *
 * [tOffsetMs] is wall-clock time since the start, like [Sample.tOffsetMs] — the
 * weather does not pause when the recording does. [lat]/[lon] are the rounded
 * position that was asked about ([WeatherPlan.round]), not where the user was:
 * that is all the API ever heard, so it is all that is stored.
 *
 * Every value is nullable because the model is allowed to have none for a
 * place and time, and a missing temperature must not become 0 °C.
 */
@Serializable
data class WeatherReading(
    val tOffsetMs: Long,
    val lat: Double,
    val lon: Double,
    val tempC: Double? = null,
    /** Precipitation as an intensity, mm per hour, over the model step that
     *  contains this moment. */
    val precipMmH: Double? = null,
    val code: Int? = null,
    val isDay: Boolean? = null,
) {
    val sky: Sky? get() = code?.let(WeatherCode::sky)
}

/**
 * A recording's weather as stored: the readings and where they came from.
 *
 * An empty [readings] list is a real answer — a recording without a single
 * position, an indoor import — and is stored so that the backfill does not ask
 * again for ever.
 */
@Serializable
data class WeatherTrack(
    val readings: List<WeatherReading>,
    /** "15min" or "hourly": the resolution the model delivered. Shown under
     *  the strip, because a 10-minute grid over hourly data repeats itself and
     *  the reader should know why. */
    val resolution: String,
    val fetchedAtUtcMs: Long,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun encode(track: WeatherTrack): String = json.encodeToString(serializer(), track)

        fun decode(text: String): WeatherTrack? = runCatching { json.decodeFromString(serializer(), text) }.getOrNull()
    }
}

/** What one line of text says about a recording's weather. */
data class WeatherSummary(
    val minTempC: Double?,
    val maxTempC: Double?,
    /** Total precipitation over the recording, mm. */
    val precipMm: Double,
    /** The sky most of the recording was under. */
    val sky: Sky?,
    /** The code that goes with [sky], for its wording. */
    val code: Int?,
    /** Something fell at some point, even if [sky] is dry. */
    val wetAtTimes: Boolean,
    val isDay: Boolean,
) {
    companion object {
        /**
         * Null when there is nothing to summarise.
         *
         * Precipitation is integrated reading by reading: each intensity holds
         * until the next reading, so a 10-minute grid over a 15-minute model
         * counts every step once, near enough.
         */
        fun of(readings: List<WeatherReading>): WeatherSummary? {
            if (readings.isEmpty()) return null
            val temps = readings.mapNotNull { it.tempC }
            var mm = 0.0
            for (i in readings.indices) {
                val rate = readings[i].precipMmH ?: continue
                val next = readings.getOrNull(i + 1)?.tOffsetMs ?: (readings[i].tOffsetMs + WeatherPlan.EVERY_MS)
                val hours = (next - readings[i].tOffsetMs).coerceAtLeast(0) / 3_600_000.0
                mm += rate * hours
            }
            val codes = readings.mapNotNull { it.code }
            // The most common code, ties to the more eventful one: a ride that
            // was half cloud and half rain was a rainy ride.
            val code = codes.groupingBy { it }.eachCount()
                .maxWithOrNull(compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { WeatherCode.sky(it.key).ordinal })
                ?.key
            val sky = code?.let(WeatherCode::sky)
            return WeatherSummary(
                minTempC = temps.minOrNull(),
                maxTempC = temps.maxOrNull(),
                precipMm = mm,
                sky = sky,
                code = code,
                wetAtTimes = codes.any { WeatherCode.sky(it).wet } || mm >= WET_MM,
                isDay = readings.count { it.isDay != false } * 2 >= readings.size,
            )
        }

        /** Less than this over a whole recording is a few drops, not rain. */
        const val WET_MM = 0.2
    }
}

/**
 * Where and when a recording's weather is asked for: every [EVERY_MS] of wall
 * clock, at the position the recording had then, plus its last moment.
 */
object WeatherPlan {

    /** The cadence the user asked for. The 15-minute model is finer than any
     *  station network a phone could reach, and a 10-minute grid over it lands
     *  in every step at least once. */
    const val EVERY_MS: Long = 10 * 60_000L

    /** The rounding applied to every position before it leaves the phone, in
     *  degrees: about 2.2 km north–south. That is the grid of the finest model
     *  Open-Meteo serves for Central Europe (ICON-D2), so nothing is lost by it
     *  — and the API learns which squares, never the track. */
    const val GRID_DEG: Double = 0.02

    data class Probe(val tOffsetMs: Long, val lat: Double, val lon: Double)

    fun round(deg: Double): Double = (deg / GRID_DEG).roundToLong() * GRID_DEG

    /** The probes for a recording, empty when it has no positions at all. */
    fun probes(samples: List<Sample>, everyMs: Long = EVERY_MS): List<Probe> {
        val sorted = samples.filter { it.lat.isFinite() && it.lon.isFinite() && !(it.lat == 0.0 && it.lon == 0.0) }
            .sortedBy { it.tOffsetMs }
        if (sorted.isEmpty()) return emptyList()
        val first = sorted.first().tOffsetMs
        val last = sorted.last().tOffsetMs
        val times = buildList {
            var t = first
            while (t <= last) {
                add(t); t += everyMs
            }
            // The end, when it is more than a few minutes past the last tick:
            // the finish line is the moment people remember the weather of.
            if (last - this.last() >= everyMs / 2) add(last)
        }
        var j = 0
        return times.map { t ->
            while (j + 1 < sorted.size && abs(sorted[j + 1].tOffsetMs - t) <= abs(sorted[j].tOffsetMs - t)) j++
            Probe(t, round(sorted[j].lat), round(sorted[j].lon))
        }
    }
}
