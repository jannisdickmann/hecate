package de.hecate.core

import de.hecate.core.profile.Elevation
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The elevation arithmetic, and mostly one question: does the hysteresis keep
 * noise out without eating the hill?
 *
 * That is the whole difficulty of an ascent total. Summing every positive
 * difference is trivial and gives a number that is wrong by an order of
 * magnitude; the threshold is what makes it mean something, and these are the
 * cases that say whether the threshold is doing its job or hiding the terrain.
 */
class ElevationTest {

    private fun series(vararg alts: Double): List<Elevation.Point> =
        alts.mapIndexed { i, a -> Elevation.Point(i * 10.0, a) }

    @Test
    fun `a plain climb is counted in full`() {
        val s = Elevation.stats(series(100.0, 150.0, 200.0, 250.0))!!
        assertEquals(150.0, s.ascentM, 0.001)
        assertEquals(0.0, s.descentM, 0.001)
        assertEquals(100.0, s.minM, 0.001)
        assertEquals(250.0, s.maxM, 0.001)
        assertEquals(150.0, s.netM, 0.001)
    }

    @Test
    fun `up and back down again nets nothing but still climbed`() {
        val s = Elevation.stats(series(100.0, 200.0, 300.0, 200.0, 100.0))!!
        assertEquals(200.0, s.ascentM, 0.001)
        assertEquals(200.0, s.descentM, 0.001)
        assertEquals(0.0, s.netM, 0.001)
        assertEquals(200.0, s.rangeM, 0.001)
    }

    /**
     * The reason the threshold exists. An hour of flat ground at 1 Hz, jittering
     * by a couple of metres, sums to hundreds of metres of imaginary climbing if
     * every rise is counted.
     */
    @Test
    fun `jitter on flat ground is not a mountain`() {
        val rng = Random(7)
        val flat = (0 until 3600).map { Elevation.Point(it * 3.0, 500.0 + rng.nextDouble(-2.0, 2.0)) }

        val naive = flat.zipWithNext().sumOf { (a, b) -> (b.altM - a.altM).coerceAtLeast(0.0) }
        assertTrue(naive > 1000.0, "the naive sum should be absurd, was $naive")

        val s = Elevation.stats(flat)!!
        assertEquals(0.0, s.ascentM, 0.001)
        assertEquals(0.0, s.descentM, 0.001)
    }

    @Test
    fun `a real climb survives the same jitter riding on it`() {
        val rng = Random(11)
        // 400 m of climb over 2000 points, with the same +-2 m of noise on top.
        val climb = (0 until 2000).map {
            Elevation.Point(it * 5.0, 500.0 + it * 0.2 + rng.nextDouble(-2.0, 2.0))
        }
        val s = Elevation.stats(climb)!!
        assertTrue(abs(s.ascentM - 400.0) < 25.0, "ascent=${s.ascentM}, expected about 400")
        assertTrue(s.descentM < 25.0, "descent=${s.descentM}, expected about nothing")
    }

    @Test
    fun `a climb gentler than the threshold is not counted`() {
        // Deliberate, and documented on Tuning.ELEVATION_THRESHOLD_M: the price of
        // not counting noise is not counting a drag of a few metres either.
        val s = Elevation.stats(series(100.0, 101.0, 102.0, 103.0))!!
        assertEquals(0.0, s.ascentM, 0.001)
        // The range still sees it, which is why the band is shown beside the total.
        assertEquals(3.0, s.rangeM, 0.001)
    }

    @Test
    fun `too little to say anything says nothing`() {
        assertNull(Elevation.stats(emptyList()))
        assertNull(Elevation.stats(series(100.0)))
    }

    // --------------------------------------------------------------- despiking

    /**
     * The four openings below are transcribed from real recordings on this watch,
     * and they are why the filter exists: the altimeter is 10–25 m out for a few
     * seconds, in either direction, and then settles.
     */
    @Test
    fun `the altimeter settling at the start is taken out`() {
        // gh-448447: one sample 16 m high, then a clean descent.
        val a = Elevation.despike(
            doubleArrayOf(315.0, 299.0, 298.0, 297.0, 296.0, 295.0, 295.0, 295.0, 295.0, 294.0, 293.0),
        )
        assertTrue(a[0] < 300.0, "the 315 m spike survived as ${a[0]}")

        // gh-236452: three bad samples in a row, high then low then high.
        val b = Elevation.despike(
            doubleArrayOf(298.0, 275.0, 291.0, 271.0, 271.0, 271.0, 271.0, 271.0, 270.0, 270.0, 270.0),
        )
        for (i in 0..2) assertTrue(abs(b[i] - 271.0) <= 2.0, "sample $i settled to ${b[i]}, expected about 271")

        // gh-584042: a step the wrong way, upward, at the third sample.
        val c = Elevation.despike(
            doubleArrayOf(282.0, 282.0, 291.0, 289.0, 289.0, 289.0, 288.0, 288.0, 288.0, 287.0, 287.0),
        )
        assertTrue(c[2] < 291.0, "the upward step survived as ${c[2]}")
    }

    @Test
    fun `an isolated spike in the middle goes too`() {
        // gh-233802 dips 9 m at the seventh sample and comes straight back.
        val alts = doubleArrayOf(
            300.0, 298.0, 298.0, 302.0, 301.0, 297.0, 289.0, 300.0, 299.0,
            298.0, 298.0, 297.0, 296.0, 296.0, 296.0,
        )
        val out = Elevation.despike(alts)
        assertTrue(out[6] > 295.0, "the dip survived as ${out[6]}")
    }

    /**
     * The property that makes a median filter safe here rather than merely
     * convenient: on a monotonic run it is *exactly* the identity, so no climb
     * and no ski descent is touched however steep — everywhere it has symmetric
     * context, which is everywhere but the first and last half-window.
     */
    @Test
    fun `a monotonic run passes through untouched`() {
        val edge = Tuning.ELEVATION_MEDIAN_WINDOW / 2
        for (rate in listOf(7.0, -5.0, 0.5)) {
            val ramp = DoubleArray(60) { 2400.0 + it * rate }
            val out = Elevation.despike(ramp)
            for (i in edge until ramp.size - edge) {
                assertEquals(ramp[i], out[i], 0.001, "rate $rate changed at $i")
            }
        }
    }

    /**
     * And the cost of that, stated rather than discovered later: the first and
     * last half-window have no symmetric context and follow their neighbourhood.
     * At the vertical rates a recording actually starts at — standing at a lift
     * base, at a front door — it is a metre or two.
     */
    @Test
    fun `the ends follow their neighbourhood, by little at real rates`() {
        val bike = DoubleArray(60) { 500.0 + it * 0.5 }
        assertTrue(abs(Elevation.despike(bike)[0] - bike[0]) <= 2.0)

        val hike = DoubleArray(60) { 500.0 + it * 0.3 }
        assertTrue(abs(Elevation.despike(hike)[0] - hike[0]) <= 2.0)
    }

    @Test
    fun `a real hill is still a hill afterwards`() {
        // Up 300 m over 200 samples and down again: the shape has to survive.
        val hill = DoubleArray(400) { if (it < 200) 500.0 + it * 1.5 else 800.0 - (it - 200) * 1.5 }
        val out = Elevation.despike(hill)
        // The apex is clipped by about half a window of climb — 3 m in 300, and
        // the one place a median filter is knowingly lossy in the interior. The
        // foot of the hill sits on the edge, whose behaviour the test above owns.
        assertEquals(800.0, out.max(), 4.0)
        // Under a tenth of the hill goes missing, and it is accounted for rather
        // than mysterious: about 6 m at each foot, where the edge follows its
        // neighbourhood at 1,5 m per sample, 3 m off the apex, and a last
        // sub-threshold step at either end. This is the worst case by
        // construction — a tent with a corner no ground has — and it never
        // overstates, which is the direction that matters for a total somebody
        // compares between days.
        val s = Elevation.stats(out.mapIndexed { i, a -> Elevation.Point(i * 10.0, a) })!!
        assertTrue(s.ascentM in 270.0..300.0, "ascent=${s.ascentM}")
        assertTrue(s.descentM in 270.0..300.0, "descent=${s.descentM}")
    }

    @Test
    fun `missing altitude stays missing`() {
        val out = Elevation.despike(doubleArrayOf(500.0, Double.NaN, 501.0, 500.0, 502.0, 501.0, 500.0))
        assertTrue(out[1].isNaN(), "a gap was filled in with ${out[1]}")
    }

    @Test
    fun `a series too short to filter is returned as it is`() {
        val a = doubleArrayOf(500.0, 900.0)
        assertEquals(a.toList(), Elevation.despike(a).toList())
    }

    @Test
    fun `the spike no longer counts towards the climb`() {
        // The point of all of this: a 16 m opening spike is more than the 5 m
        // threshold, so before the filter it was a real entry in the day's total.
        val raw = doubleArrayOf(315.0, 299.0, 298.0, 297.0, 296.0, 295.0, 295.0, 295.0, 295.0, 294.0, 293.0)
        val before = Elevation.stats(raw.mapIndexed { i, a -> Elevation.Point(i * 10.0, a) })!!
        assertTrue(before.descentM > 15.0, "expected the spike to show up, got ${before.descentM}")

        val after = Elevation.stats(
            Elevation.despike(raw).mapIndexed { i, a -> Elevation.Point(i * 10.0, a) },
        )!!
        assertTrue(after.descentM < 6.0, "the spike still counts: ${after.descentM}")
    }

    // ------------------------------------------------------------------- bins

    @Test
    fun `bins without an altitude are left out rather than invented`() {
        val points = Elevation.ofBins(listOf(100.0, null, 120.0, null), binM = 10.0)
        assertEquals(listOf(0.0, 20.0), points.map { it.distanceM })
        assertEquals(listOf(100.0, 120.0), points.map { it.altM })
    }

    // --------------------------------------------------------------- thinning

    @Test
    fun `thinning keeps the peaks it is there to show`() {
        // A spike one sample wide, in the middle of otherwise flat ground: the
        // case an averaging thin-out erases completely.
        val raw = (0 until 2000).map {
            Elevation.Point(it * 5.0, if (it == 1000) 900.0 else 500.0)
        }
        val thinned = Elevation.simplify(raw, 200)
        assertTrue(thinned.size <= 200, "size=${thinned.size}")
        assertEquals(900.0, thinned.maxOf { it.altM }, 0.001)
        assertEquals(500.0, thinned.minOf { it.altM }, 0.001)
    }

    @Test
    fun `thinning never reorders the ground`() {
        val rng = Random(3)
        val raw = (0 until 5000).map { Elevation.Point(it * 2.0, 400.0 + rng.nextDouble(0.0, 300.0)) }
        val thinned = Elevation.simplify(raw, 300)
        for (i in 1 until thinned.size) {
            assertTrue(
                thinned[i].distanceM >= thinned[i - 1].distanceM,
                "distance went backwards at $i",
            )
        }
    }

    @Test
    fun `a series already short enough is left alone`() {
        val raw = series(100.0, 110.0, 120.0)
        assertEquals(raw, Elevation.simplify(raw, 400))
    }
}
