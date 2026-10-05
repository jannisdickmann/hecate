package de.hecate.core

import de.hecate.core.geo.Geo
import de.hecate.core.geo.Polyline
import de.hecate.core.geo.Resampler
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeoTest {

    @Test
    fun `haversine matches a known distance`() {
        // One degree of latitude is 111.19 km on a sphere of the radius we use.
        val d = Geo.haversine(50.0, 9.0, 51.0, 9.0)
        assertTrue(abs(d - 111_195.0) < 50.0, "got $d")
    }

    @Test
    fun `local projection round-trips`() {
        val p = Synthetic.at(1234.0, -567.0)
        val l = Geo.toLocal(Synthetic.ORIGIN, p)
        assertTrue(abs(l[0] - 1234.0) < 0.01)
        assertTrue(abs(l[1] + 567.0) < 0.01)
    }

    @Test
    fun `point projects onto the middle of a segment`() {
        val a = Synthetic.at(0.0, 0.0)
        val b = Synthetic.at(100.0, 0.0)
        val p = Synthetic.at(50.0, 20.0)
        val pr = Geo.projectToSegment(p, a, b)
        assertTrue(abs(pr.t - 0.5) < 0.01, "t=${pr.t}")
        assertTrue(abs(pr.distanceM - 20.0) < 0.1, "d=${pr.distanceM}")
    }

    @Test
    fun `projection clamps beyond the segment ends`() {
        val a = Synthetic.at(0.0, 0.0)
        val b = Synthetic.at(100.0, 0.0)
        val pr = Geo.projectToSegment(Synthetic.at(-50.0, 0.0), a, b)
        assertEquals(0.0, pr.t)
        assertTrue(abs(pr.distanceM - 50.0) < 0.1)
    }

    @Test
    fun `resampling produces even spacing`() {
        val pts = Synthetic.path(listOf(Synthetic.at(0.0, 0.0), Synthetic.at(1000.0, 0.0)), 37.0)
        val rs = Resampler.resample(pts, 10.0)
        for (i in 1 until rs.size - 1) {
            val d = Geo.haversine(rs[i - 1], rs[i])
            assertTrue(abs(d - 10.0) < 0.5, "spacing $d at $i")
        }
        assertTrue(rs.size in 99..102, "got ${rs.size} points")
    }

    @Test
    fun `resampling carries an interpolated value`() {
        val pts = listOf(Synthetic.at(0.0, 0.0), Synthetic.at(100.0, 0.0))
        val (p, v) = Resampler.resampleWithValue(pts, doubleArrayOf(0.0, 100.0), 10.0)
        assertEquals(p.size, v.size)
        assertTrue(abs(v[5] - 50.0) < 1.0, "v5=${v[5]}")
    }

    @Test
    fun `polyline windowed projection ignores geometry outside the window`() {
        // An out-and-back: the same place appears at position 0 and at position 200.
        val pl = Polyline.of(
            Synthetic.path(listOf(Synthetic.at(0.0, 0.0), Synthetic.at(100.0, 0.0), Synthetic.at(0.0, 0.0)), 5.0),
        )
        val p = Synthetic.at(10.0, 3.0)
        val outbound = pl.projectWindow(p, 0.0, 40.0)
        val inbound = pl.projectWindow(p, 170.0, 210.0)
        assertTrue(outbound.positionM < 30.0, "outbound=${outbound.positionM}")
        assertTrue(inbound.positionM > 170.0, "inbound=${inbound.positionM}")
    }

    @Test
    fun `localisation takes the pass that continues, not the nearer one`() {
        // The stem is walked out at east 0 and home at east 6; this fix is on the
        // way home's pavement, so the way home is nearer by a few metres.
        val pl = Polyline.of(Synthetic.lollipop())
        val p = Synthetic.at(5.0, 300.0)
        assertTrue(pl.projectAll(p).positionM > 1500.0, "the fixture must make the late pass the nearer one")

        // Nothing to go on: the start of the route wins.
        assertEquals(300.0, pl.localise(p, 0.0, Tuning.LOCALISE_TIE_M).positionM, 10.0)
        // Last seen on the way home: the way home it is.
        assertTrue(pl.localise(p, 2000.0, Tuning.LOCALISE_TIE_M).positionM > 2000.0)
    }

    @Test
    fun `a closed loop is started at its start, however near its finish is`() {
        val pl = Polyline.of(Synthetic.lollipop())
        val atHome = Synthetic.at(5.0, 2.0)
        assertTrue(pl.projectAll(atHome).positionM > pl.lengthM - 20.0)
        assertTrue(pl.localise(atHome, 0.0, Tuning.LOCALISE_TIE_M).positionM < 20.0)
    }

    @Test
    fun `a different street is not a tie`() {
        // Beside the loop's east side, 380 m from the stem: one stretch is near and
        // nothing else is, so localising is plain nearest-point projection.
        val pl = Polyline.of(Synthetic.lollipop())
        val p = Synthetic.at(380.0, 800.0)
        val hit = pl.localise(p, 0.0, Tuning.LOCALISE_TIE_M)
        assertEquals(pl.projectAll(p).positionM, hit.positionM, 1e-6)
    }

    @Test
    fun `pointAt walks the polyline`() {
        val pl = Polyline.of(Synthetic.path(listOf(Synthetic.at(0.0, 0.0), Synthetic.at(500.0, 0.0)), 5.0))
        val mid = pl.pointAt(250.0)
        val l = Geo.toLocal(Synthetic.ORIGIN, mid)
        assertTrue(abs(l[0] - 250.0) < 1.0, "east=${l[0]}")
        assertTrue(abs(l[1]) < 1.0)
    }
}
