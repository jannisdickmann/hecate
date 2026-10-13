package de.hecate.core

import de.hecate.core.model.Sample
import de.hecate.core.weather.OpenMeteo
import de.hecate.core.weather.Sky
import de.hecate.core.weather.WeatherClient
import de.hecate.core.weather.WeatherCode
import de.hecate.core.weather.WeatherException
import de.hecate.core.weather.WeatherFetch
import de.hecate.core.weather.WeatherPlan
import de.hecate.core.weather.WeatherReading
import de.hecate.core.weather.WeatherSummary
import de.hecate.core.weather.WeatherTrack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/weather/$name")) { name }.readText()

    /** A recording heading north-east from Hanau at a sample a second. */
    private fun samples(minutes: Int, lat0: Double = 50.141, lon0: Double = 8.921, step: Double = 0.00001): List<Sample> =
        (0..minutes * 60).map { s ->
            Sample(tOffsetMs = s * 1000L, activeMs = s * 1000L, lat = lat0 + s * step, lon = lon0 + s * step, altM = null, hrBpm = null)
        }

    // 2026-10-10 08:00 UTC — inside the fifteen-minute fixture's day.
    private val start = 1_791_619_200_000L

    @Test
    fun `probes every ten minutes, plus the finish`() {
        val probes = WeatherPlan.probes(samples(65))
        assertEquals(listOf(0L, 10, 20, 30, 40, 50, 60, 65).map { it * 60_000 }, probes.map { it.tOffsetMs })
    }

    @Test
    fun `a finish just after a tick is not asked about twice`() {
        val probes = WeatherPlan.probes(samples(62))
        assertEquals(60 * 60_000L, probes.last().tOffsetMs)
    }

    @Test
    fun `positions are rounded to the grid before they go anywhere`() {
        for (p in WeatherPlan.probes(samples(65))) {
            assertTrue(abs(p.lat / WeatherPlan.GRID_DEG - Math.round(p.lat / WeatherPlan.GRID_DEG)) < 1e-9, "${p.lat}")
            assertTrue(abs(p.lon / WeatherPlan.GRID_DEG - Math.round(p.lon / WeatherPlan.GRID_DEG)) < 1e-9, "${p.lon}")
        }
    }

    @Test
    fun `no positions, no probes, no request`() {
        assertTrue(WeatherPlan.probes(emptyList()).isEmpty())
        assertNull(OpenMeteo.request(start, emptyList()))
    }

    @Test
    fun `the request carries dates and rounded positions, never a time of day`() {
        val probes = WeatherPlan.probes(samples(65))
        val r = assertNotNull(OpenMeteo.request(start, probes))
        assertTrue(r.url.startsWith("https://historical-forecast-api.open-meteo.com/"), r.url)
        assertTrue("start_date=2026-10-10&end_date=2026-10-10" in r.url, r.url)
        assertTrue("minutely_15=" in r.url)
        assertFalse(Regex("T\\d\\d:").containsMatchIn(r.url), r.url)
        assertEquals(probes.map { it.lat to it.lon }.distinct(), r.locations)
        assertEquals(900L, r.stepS)
    }

    @Test
    fun `recordings before 2022 go to the hourly archive`() {
        val r = assertNotNull(OpenMeteo.request(1_559_383_200_000L, WeatherPlan.probes(samples(30))))
        assertTrue(r.url.startsWith("https://archive-api.open-meteo.com/"), r.url)
        assertTrue("hourly=" in r.url)
        assertEquals("hourly", r.resolution)
    }

    @Test
    fun `parses two locations, interpolating temperature and reading sums from the step after`() {
        // Starts at 50.14/8.92 and ends past 50.16/8.94: the fixture's two cells.
        val s = samples(65, step = 0.0000055)
        val probes = WeatherPlan.probes(s)
        val r = assertNotNull(OpenMeteo.request(start, probes))
        assertEquals(listOf(50.14 to 8.92, 50.16 to 8.94), r.locations.map { round2(it.first) to round2(it.second) })

        val readings = OpenMeteo.parse(fixture("fifteen-two-locations.json"), r, start, probes)
        assertEquals(probes.size, readings.size)

        val json = Json.parseToJsonElement(fixture("fifteen-two-locations.json")).jsonArray
        val m = json[0].jsonObject["minutely_15"]!!.jsonObject
        val times = m["time"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() }
        val temps = m["temperature_2m"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() }
        val codes = m["weather_code"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble().toInt() }

        // 08:10 sits two thirds of the way from 08:00 to 08:15.
        val t = start / 1000 + 600
        val i = times.indexOfFirst { it >= t }
        val expected = temps[i - 1] + (temps[i] - temps[i - 1]) * (t - times[i - 1]).toDouble() / 900
        val at10 = readings[1]
        assertEquals(expected, at10.tempC!!, 1e-9)
        assertEquals(codes[i], at10.code)
        assertEquals(true, at10.isDay)
        assertEquals(0.0, at10.precipMmH)
    }

    @Test
    fun `parses a single location's object, hourly`() {
        val start2019 = 1_559_383_200_000L // 2019-06-01 10:00 UTC
        val probes = WeatherPlan.probes(samples(30, lat0 = 50.141, lon0 = 8.921, step = 0.0))
        val r = assertNotNull(OpenMeteo.request(start2019, probes))
        val readings = OpenMeteo.parse(fixture("hourly-one-location.json"), r, start2019, probes)
        assertEquals(4, readings.size)
        assertTrue(readings.all { it.tempC != null && it.code != null })
    }

    @Test
    fun `an error body is an error, not empty weather`() {
        val probes = WeatherPlan.probes(samples(5))
        val r = assertNotNull(OpenMeteo.request(start, probes))
        assertFailsWith<WeatherException> {
            OpenMeteo.parse("""{"error":true,"reason":"Parameter out of range"}""", r, start, probes)
        }
    }

    @Test
    fun `fetch asks exactly once, through the client it is given`() {
        var calls = 0
        val client = WeatherClient { calls++; fixture("fifteen-two-locations.json") }
        val track = WeatherFetch.track(client, start, samples(65, step = 0.0000055), nowUtcMs = 42)
        assertEquals(1, calls)
        assertEquals("15min", track.resolution)
        assertEquals(8, track.readings.size)
    }

    @Test
    fun `a recording with nothing to ask about is answered without asking`() {
        val track = WeatherFetch.track({ error("no socket") }, start, emptyList(), nowUtcMs = 42)
        assertTrue(track.readings.isEmpty())
    }

    @Test
    fun `the stored form round-trips every field`() {
        val track = WeatherTrack(
            listOf(WeatherReading(600_000, 50.14, 8.92, 12.5, 0.8, 61, true), WeatherReading(1_200_000, 50.16, 8.94)),
            "15min",
            123,
        )
        assertEquals(track, WeatherTrack.decode(WeatherTrack.encode(track)))
        assertNull(WeatherTrack.decode("not json"))
    }

    @Test
    fun `summary integrates rain and prefers the eventful sky on a tie`() {
        val r = listOf(
            WeatherReading(0, 0.0, 0.0, 10.0, 0.0, 3, true),
            WeatherReading(600_000, 0.0, 0.0, 12.0, 3.0, 61, true),
            WeatherReading(1_200_000, 0.0, 0.0, 11.0, 0.0, 3, true),
            WeatherReading(1_800_000, 0.0, 0.0, 9.0, 3.0, 61, false),
        )
        val s = assertNotNull(WeatherSummary.of(r))
        assertEquals(9.0, s.minTempC)
        assertEquals(12.0, s.maxTempC)
        // Two ten-minute stretches at 3 mm/h: one millimetre.
        assertEquals(1.0, s.precipMm, 1e-9)
        assertEquals(Sky.RAIN, s.sky)
        assertTrue(s.wetAtTimes)
        assertTrue(s.isDay)
        assertNull(WeatherSummary.of(emptyList()))
    }

    @Test
    fun `codes fold into symbols`() {
        assertEquals(Sky.CLEAR, WeatherCode.sky(0))
        assertEquals(Sky.SHOWERS, WeatherCode.sky(81))
        assertEquals(Sky.THUNDER, WeatherCode.sky(99))
        assertEquals(Sky.CLOUDY, WeatherCode.sky(1234))
        assertEquals("Leichter Regen", WeatherCode.label(61))
        assertFalse(Sky.FOG.wet)
        assertTrue(Sky.DRIZZLE.wet)
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0
}
