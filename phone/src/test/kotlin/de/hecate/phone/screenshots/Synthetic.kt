package de.hecate.phone.screenshots

import de.hecate.core.geo.Geo
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.SessionSource
import de.hecate.core.weather.WeatherReading
import de.hecate.core.weather.WeatherSummary
import de.hecate.core.weather.WeatherTrack
import de.hecate.phone.db.HcWriteState
import de.hecate.phone.db.RouteEntity
import de.hecate.phone.db.SampleEntity
import de.hecate.phone.db.SessionEntity
import de.hecate.phone.db.SessionSummary
import de.hecate.phone.ui.WeatherView
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Believable recordings for the screenshot harness: no fixture file, no
 * randomness — the same pictures every run, so two runs can be compared.
 *
 * The run is a 10 km loop along a river in a city with a hill on the far side,
 * three surges, a two-minute stop at a light, and a shower in the middle of it.
 */
object Synthetic {

    /** 2026-10-10 06:12 UTC — a Saturday morning, 08:12 local. */
    const val START_UTC = 1_791_612_720_000L
    const val TZ = 120

    /** The loop's waypoints, walked in order and back to the first. */
    private val waypoints = listOf(
        50.1327 to 8.9145, 50.1302 to 8.9227, 50.1281 to 8.9320, 50.1259 to 8.9418,
        50.1240 to 8.9502, 50.1213 to 8.9571, 50.1171 to 8.9555, 50.1150 to 8.9478,
        50.1166 to 8.9380, 50.1188 to 8.9282, 50.1215 to 8.9190, 50.1258 to 8.9121,
        50.1300 to 8.9098,
    )

    fun samples(sessionId: String = "run"): List<SampleEntity> {
        val path = (waypoints + waypoints.first())
        val legs = path.zipWithNext().map { (a, b) -> Geo.haversine(a.first, a.second, b.first, b.second) }
        val total = legs.sum()
        val out = ArrayList<SampleEntity>()
        var d = 0.0
        var t = 0L
        var active = 0L
        var paused = 0
        while (d < total) {
            val f = d / total
            // A light at 4.1 km: two minutes standing, the clock paused.
            val atLight = d in 4_100.0..4_105.0 && paused < 120
            // Speed: a base of 11 km/h, three surges, slower up the hill.
            val hill = exp(-((f - .55) * (f - .55)) / .004)
            val surge = listOf(.22, .38, .8).sumOf { c -> exp(-((f - c) * (f - c)) / .0004) }
            val kmh = 11.0 + 2.6 * surge - 2.2 * hill + 0.35 * sin(f * 9)
            val (lat, lon) = along(path, legs, d)
            val alt = 104 + 58 * hill + 6 * sin(f * 2 * PI * 3)
            val hr = 128 + 22 * f + 18 * surge + 14 * hill + 3 * sin(f * 90) - (if (paused > 20) 12.0 else 0.0)
            out += SampleEntity(
                sessionId = sessionId,
                tOffsetMs = t,
                activeMs = active,
                lat = lat,
                lon = lon,
                altM = alt,
                hrBpm = hr.roundToInt(),
                accuracyM = 4.0,
                paused = atLight,
            )
            t += 1000
            if (atLight) {
                paused++
                if (paused >= 120) d += 6.0
            } else {
                active += 1000
                d += kmh / 3.6
            }
        }
        return out
    }

    private fun along(path: List<Pair<Double, Double>>, legs: List<Double>, d: Double): Pair<Double, Double> {
        var rest = d
        for (i in legs.indices) {
            if (rest <= legs[i]) {
                val f = rest / legs[i]
                val a = path[i]
                val b = path[i + 1]
                // A gentle wiggle so the line is a path, not a polygon.
                val w = sin(f * PI * 3) * 0.00003
                return (a.first + (b.first - a.first) * f + w) to (a.second + (b.second - a.second) * f - w)
            }
            rest -= legs[i]
        }
        return path.last()
    }

    fun session(samples: List<SampleEntity>, id: String = "run"): SessionEntity {
        var dist = 0.0
        for (i in 1 until samples.size) {
            if (samples[i].paused) continue
            dist += Geo.haversine(samples[i - 1].lat, samples[i - 1].lon, samples[i].lat, samples[i].lon)
        }
        return SessionEntity(
            id = id,
            startedAtUtc = START_UTC,
            endedAtUtc = START_UTC + samples.last().tOffsetMs,
            tzOffsetMinutes = TZ,
            exerciseType = ExerciseType.RUNNING,
            activeDurationMs = samples.last().activeMs,
            totalDistanceM = dist,
            source = SessionSource.WATCH,
            hcWriteState = HcWriteState.WRITTEN,
            matchedRouteId = 7,
            matchCoverage = 0.94,
            committedAtM = 640.0,
            avgHr = samples.mapNotNull { it.hrBpm }.average(),
        )
    }

    val route = RouteEntity(
        id = 7,
        name = "Flussufer-Runde",
        nameIsCustom = true,
        exerciseType = ExerciseType.RUNNING,
        lengthM = 8_240.0,
        memberCount = 14,
        medoidSessionId = "run",
        createdAt = 0,
    )

    /** Fourteen times round the same loop, this one third-fastest. */
    fun routeDurations(mine: Long): List<Long> =
        listOf(-210, -95, 0, 25, 60, 88, 104, 150, 171, 230, 260, 315, 402, 540).map { mine + it * 1000L }

    fun weather(samples: List<SampleEntity>): WeatherView.Ready {
        val end = samples.last().tOffsetMs
        val readings = (0..end step 600_000).map { t ->
            val m = t / 60_000.0
            val shower = m in 25.0..45.0
            val code = when {
                m < 15 -> 3
                m < 25 -> 2
                shower -> if (m < 35) 80 else 61
                m < 50 -> 2
                else -> 1
            }
            WeatherReading(
                tOffsetMs = t,
                lat = 50.12,
                lon = 8.94,
                tempC = 11.8 + m * 0.045 - (if (shower) 0.9 else 0.0),
                precipMmH = if (shower) (if (m < 35) 2.4 else 0.9) else 0.0,
                code = code,
                isDay = true,
            )
        } + WeatherReading(end, 50.12, 8.94, 14.6, 0.0, 1, true)
        val track = WeatherTrack(readings, "15min", START_UTC + end)
        return WeatherView.Ready(track, WeatherSummary.of(readings)!!)
    }

    /** A history for the lists: sixty recordings over four months. */
    fun history(): List<SessionSummary> {
        val types = listOf(ExerciseType.RUNNING, ExerciseType.BIKING, ExerciseType.RUNNING, ExerciseType.WALKING, ExerciseType.HIKING)
        val routes = listOf("Flussufer-Runde" to 7L, "Zur Arbeit" to 8L, null, "Parkrunde" to 9L, null)
        return (0 until 60).map { i ->
            val type = types[i % types.size]
            val km = when (type) {
                ExerciseType.BIKING -> 18.0 + (i % 4) * 3.5
                ExerciseType.WALKING -> 3.2 + (i % 3) * 0.8
                ExerciseType.HIKING -> 12.0 + (i % 5) * 2.0
                else -> 8.0 + (i % 5) * 0.6
            }
            val kmh = when (type) {
                ExerciseType.BIKING -> 24.0 + (i % 6)
                ExerciseType.WALKING -> 5.1
                ExerciseType.HIKING -> 4.4
                else -> 10.8 + (i % 4) * 0.4
            }
            val r = routes[i % routes.size]
            val code = listOf(1, 3, 61, 0, 2, 80, 3, 45)[i % 8]
            SessionSummary(
                id = "s$i",
                startedAtUtc = START_UTC - i * 47L * 3_600_000L,
                tzOffsetMinutes = TZ,
                exerciseType = type,
                activeDurationMs = (km / kmh * 3_600_000).toLong(),
                totalDistanceM = km * 1000,
                source = SessionSource.WATCH,
                hcWriteState = HcWriteState.WRITTEN,
                matchedRouteId = r?.second,
                routeName = r?.first,
                avgHr = 120.0 + (i * 7 % 35),
                routeTemporary = false,
                weatherCode = if (i % 9 == 4) null else code,
                tempMinC = 9.0 + (i * 3 % 9),
                tempMaxC = 11.0 + (i * 3 % 9),
                precipMm = if (code >= 51) 1.4 else 0.0,
                weatherIsDay = i % 7 != 3,
            )
        }
    }

    /** Three routes and sixty tracks on them, for the heatmap card. */
    fun heat(): de.hecate.phone.ui.HeatmapState {
        val loop = (waypoints + waypoints.first()).map { de.hecate.core.geo.LatLon(it.first, it.second) }
        val commute = (0..40).map { i -> de.hecate.core.geo.LatLon(50.1327 + i * 0.0011, 8.9145 - i * 0.0019 + sin(i / 4.0) * 0.0006) }
        val park = (0..36).map { i ->
            val a = i / 36.0 * 2 * PI
            de.hecate.core.geo.LatLon(50.1430 + 0.006 * sin(a), 8.8790 + 0.010 * kotlin.math.cos(a))
        }
        fun dense(p: List<de.hecate.core.geo.LatLon>) = p.zipWithNext().flatMap { (a, b) ->
            (0 until 12).map { k -> de.hecate.core.geo.LatLon(a.lat + (b.lat - a.lat) * k / 12, a.lon + (b.lon - a.lon) * k / 12) }
        }
        val routes = listOf(
            de.hecate.core.heat.RouteTrack(7, loop),
            de.hecate.core.heat.RouteTrack(8, commute),
            de.hecate.core.heat.RouteTrack(9, park),
        )
        val sessions = (0 until 60).map { i ->
            val base = when (i % 5) { 0, 2 -> loop; 1 -> commute; 3 -> park; else -> loop.take(8) }
            val j = (i % 7 - 3) * 0.00004
            de.hecate.core.heat.SessionTrack("s$i", dense(base).map { de.hecate.core.geo.LatLon(it.lat + j, it.lon - j) })
        }
        val grid = de.hecate.core.heat.Heatmap.build(routes, sessions)!!
        return de.hecate.phone.ui.HeatmapState(grid, routes.map { it.points })
    }
}
