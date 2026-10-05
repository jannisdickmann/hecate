package de.hecate.core

import de.hecate.core.display.HeadingUp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadingUpTest {

    @Test
    fun `heading is read over the last stretch of path`() {
        // North for 200 m, then east for 50 m.
        val trail = Synthetic.path(listOf(Synthetic.at(0.0, 0.0), Synthetic.at(0.0, 200.0), Synthetic.at(50.0, 200.0)), 2.0)
        assertEquals(90.0, HeadingUp.heading(trail, 25.0)!!, 1.0)
        // Read over more than the last leg, it is somewhere between the two.
        assertTrue(HeadingUp.heading(trail, 100.0)!! in 10.0..80.0)
    }

    @Test
    fun `standing still or turning on the spot has no heading`() {
        val still = List(20) { Synthetic.at(0.0, 0.0) }
        assertNull(HeadingUp.heading(still, 25.0))
        // Out and straight back: plenty of path, no distance covered.
        val uTurn = Synthetic.path(listOf(Synthetic.at(0.0, 0.0), Synthetic.at(0.0, 15.0), Synthetic.at(0.0, 0.0)), 1.0)
        assertNull(HeadingUp.heading(uTurn, 25.0))
    }

    @Test
    fun `blending goes round the short side`() {
        assertEquals(0.0, HeadingUp.blend(350.0, 10.0, 0.5), 1e-9)
        assertEquals(355.0, HeadingUp.blend(350.0, 10.0, 0.25), 1e-9)
        assertEquals(42.0, HeadingUp.blend(null, 42.0, 0.3), 1e-9)
    }

    @Test
    fun `the way you are going is up`() {
        val centre = Synthetic.at(0.0, 0.0)
        // Heading east; a point 100 m east lands straight above the centre.
        val ahead = HeadingUp.project(centre, 90.0, 1.0, 200.0, 300.0, Synthetic.at(100.0, 0.0))
        assertEquals(200.0, ahead[0], 0.5)
        assertEquals(200.0, ahead[1], 0.5)
        // ...and a point 100 m north is to the left.
        val left = HeadingUp.project(centre, 90.0, 1.0, 200.0, 300.0, Synthetic.at(0.0, 100.0))
        assertEquals(100.0, left[0], 0.5)
        assertEquals(300.0, left[1], 0.5)
    }

    @Test
    fun `the trail is thinned and bounded`() {
        var trail = emptyList<de.hecate.core.geo.LatLon>()
        repeat(10) { trail = HeadingUp.extend(trail, Synthetic.at(0.0, 0.0)) }
        assertEquals(1, trail.size)
        for (i in 1..2000) trail = HeadingUp.extend(trail, Synthetic.at(0.0, i * 5.0))
        assertEquals(Tuning.WATCH_TRAIL_MAX_POINTS, trail.size)
        assertEquals(10_000.0, de.hecate.core.geo.Geo.toLocal(Synthetic.at(0.0, 0.0), trail.last())[1], 0.5)
    }
}
