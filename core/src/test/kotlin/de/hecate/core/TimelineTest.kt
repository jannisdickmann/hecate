package de.hecate.core

import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.model.Sample
import de.hecate.core.profile.Timeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Speed is a derivative, so every error in it arrives multiplied — and it still
 * draws a plausible-looking line. These pin the three things that would be wrong
 * without looking wrong: the units, what a dropout does, and that a pause closes
 * up rather than stretching the axis.
 */
class TimelineTest {

    private val home = LatLon(50.10, 8.90)

    private fun at(east: Double, north: Double) = Geo.fromLocal(home, east, north)

    /** A ride due east at [kmh], one sample a second, [n] of them. */
    private fun ride(kmh: Double, n: Int, startMs: Long = 0L, hr: Int? = 140): List<Sample> {
        val perSecond = kmh * 1000.0 / 3600.0
        return List(n) { i ->
            val p = at(i * perSecond, 0.0)
            Sample(
                tOffsetMs = startMs + i * 1000L,
                activeMs = startMs + i * 1000L,
                lat = p.lat,
                lon = p.lon,
                altM = 100.0,
                hrBpm = hr,
                paused = false,
            )
        }
    }

    @Test
    fun `a steady ride reads as its own speed, in km per hour`() {
        val points = Timeline.speedOfSamples(ride(24.0, 60))
        assertTrue(points.isNotEmpty(), "no speed came out of a ride")
        val stats = Timeline.stats(points)!!
        // Both ends of the range, because the unit is the thing that would be
        // wrong here and it would be wrong by a factor of 3.6.
        assertEquals(24.0, stats.min, 0.5, "slowest")
        assertEquals(24.0, stats.max, 0.5, "fastest")
    }

    @Test
    fun `one wild fix does not become a sprint`() {
        // A single sample 200 m off the line, which at 1 Hz is 720 km/h there
        // and the same coming back. The median removes both; a mean would keep
        // half of it.
        val samples = ride(24.0, 60).toMutableList()
        val bad = at(20 * 6.667, 150.0)
        samples[20] = samples[20].copy(lat = bad.lat, lon = bad.lon)
        val max = Timeline.stats(Timeline.speedOfSamples(samples))!!.max
        assertTrue(max < 40.0, "a fix that jumped 150 m showed as $max km/h")
    }

    @Test
    fun `a dropout is a gap, not an average across it`() {
        // Ten minutes and four kilometres of nothing in the middle: the straight
        // line across it is not a path anybody took, and its average is not a
        // speed anybody rode.
        val before = ride(24.0, 30)
        val jump = at(4_000.0, 0.0)
        val after = List(30) { i ->
            val p = at(4_000.0 + i * 6.667, 0.0)
            Sample(
                tOffsetMs = 600_000L + i * 1000L,
                activeMs = 600_000L + i * 1000L,
                lat = p.lat,
                lon = p.lon,
                altM = 100.0,
                hrBpm = 140,
                paused = false,
            )
        }
        assertEquals(jump.lat, after.first().lat, 1e-9)

        val points = Timeline.speedOfSamples(before + after)
        val max = Timeline.stats(points)!!.max
        assertTrue(max < 40.0, "the gap was drawn as $max km/h of riding")
    }

    @Test
    fun `a pause closes up rather than stretching the axis`() {
        // Active time is what the axis is, so twenty minutes at a café is not
        // twenty minutes of chart. The paused samples are dropped and the
        // active clock does not advance across them.
        val first = ride(24.0, 30)
        val paused = List(20) { i ->
            first.last().copy(tOffsetMs = 30_000L + i * 1000L, paused = true)
        }
        val second = ride(24.0, 30, startMs = 30_000L)
        val points = Timeline.speedOfSamples(first + paused + second)
        assertTrue(points.isNotEmpty())
        assertTrue(
            points.last().tMs <= 60_000L,
            "the axis ran to ${points.last().tMs} ms for 60 s of riding",
        )
    }

    @Test
    fun `a route's speed comes from its median times and its bin width`() {
        // Ten metre bins, two seconds each: 5 m/s, which is 18 km/h.
        val times = List(40) { it * 2.0 }
        val points = Timeline.speedOfBins(times, binM = 10.0)
        assertEquals(40, points.size, "one point per bin")
        for (p in points) assertEquals(18.0, p.value, 1e-6)
        assertEquals(78_000L, points.last().tMs, "the axis is the median elapsed time")
    }

    @Test
    fun `two bins whose medians nearly coincide are not four thousand km per hour`() {
        // The bug this exists for, seen on the first real route: per-bin medians
        // are taken independently, so the sequence is monotone only on average.
        // A pair a hundredth of a second apart, differenced on its own, is
        // 3600 km/h over a ten metre bin, and one of them flattens the chart.
        val times = MutableList(40) { it * 2.0 }
        times[20] = times[19] + 0.01
        val points = Timeline.speedOfBins(times, binM = 10.0)
        val max = Timeline.stats(points)!!.max
        assertTrue(max < 40.0, "a hundredth of a second between two bins read as $max km/h")
    }

    @Test
    fun `bins that do not advance at all are dropped rather than divided by`() {
        // Every median identical: no time passes anywhere, so there is no speed
        // to be had and none is invented.
        assertTrue(Timeline.speedOfBins(List(30) { 5.0 }, binM = 10.0).isEmpty())
    }

    @Test
    fun `a heart rate series is its own colour, and a gap is a gap`() {
        val samples = ride(24.0, 10).mapIndexed { i, s -> if (i in 3..5) s.copy(hrBpm = null) else s }
        val points = Timeline.heartRateOfSamples(samples)
        assertEquals(7, points.size, "three samples had no reading and cannot be invented")
        for (p in points) assertEquals(p.value, p.hrBpm)
    }

    @Test
    fun `thinning a long series keeps its holes and does not invent any`() {
        // The regression: a seven-hour walk thinned to 400 points leaves two
        // minutes between neighbours, so a chart that called distance-in-time a
        // gap drew two hundred separate bars instead of one hike.
        val long = List(27_000) { Timeline.Point(it * 1000L, 4.0 + (it % 7)) }
        val thinned = Timeline.simplify(long, 400)
        assertTrue(thinned.size <= 420, "thinned to ${thinned.size}")
        assertEquals(1, thinned.count { it.startsRun }, "one continuous walk is one run")
    }

    @Test
    fun `a hole in the series survives being thinned`() {
        val before = List(2_000) { Timeline.Point(it * 1000L, 20.0) }
        // Ten minutes after the first half stopped, not ten minutes after it
        // started: the hole is what is between them.
        val after = List(2_000) { Timeline.Point(2_000_000L + 600_000L + it * 1000L, 20.0) }
        val thinned = Timeline.simplify(before + after, 400)
        assertEquals(2, thinned.count { it.startsRun }, "the ten-minute hole is still a hole")
        // Each side gets points in proportion to its own length, so neither is
        // thinned away to make room for the other.
        val second = thinned.indexOfLast { it.startsRun }
        assertTrue(second > 100 && second < thinned.size - 100, "lopsided at $second of ${thinned.size}")
    }

    @Test
    fun `a two-sample fragment beside a long run is not thinned to nothing`() {
        val run = List(5_000) { Timeline.Point(it * 1000L, 20.0) }
        val fragment = listOf(
            Timeline.Point(5_600_000L, 12.0),
            Timeline.Point(5_601_000L, 13.0),
        )
        val thinned = Timeline.simplify(run + fragment, 400)
        assertEquals(2, thinned.count { it.startsRun })
        assertEquals(13.0, thinned.last().value, 1e-9, "the fragment's own points")
    }

    @Test
    fun `nothing measured is null rather than a chart of zero`() {
        assertNull(Timeline.stats(emptyList()))
        assertTrue(Timeline.speedOfSamples(emptyList()).isEmpty())
        assertTrue(Timeline.heartRateOfBins(emptyList(), emptyList()).isEmpty())
    }
}
