package de.hecate.core

import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.geo.Polyline
import de.hecate.core.geo.Simplify
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Douglas–Peucker, checked against the property it exists to guarantee — every
 * original vertex is still within tolerance of the simplified line — rather than
 * against a hand-computed answer, which would only prove that the test and the
 * code were written by the same person on the same afternoon.
 */
class SimplifyTest {

    private fun wander(n: Int, amplitudeDeg: Double = 0.0004): List<LatLon> = List(n) { i ->
        LatLon(
            49.6 + i * 0.00004 + amplitudeDeg * sin(i / 7.0),
            11.0 + i * 0.00006 + amplitudeDeg * cos(i / 11.0),
        )
    }

    @Test
    fun `every original point stays within the tolerance`() {
        for (tolerance in listOf(2.0, 6.0, 12.0, 30.0)) {
            val original = wander(400)
            val simplified = Simplify.douglasPeucker(original, tolerance)
            val line = Polyline.of(simplified)
            for (p in original) {
                assertTrue(
                    line.projectAll(p).distanceM <= tolerance + 1e-6,
                    "point ${p.lat},${p.lon} is ${line.projectAll(p).distanceM} m off at tolerance $tolerance",
                )
            }
        }
    }

    @Test
    fun `endpoints survive`() {
        val original = wander(120)
        val simplified = Simplify.douglasPeucker(original, 25.0)
        assertEquals(original.first(), simplified.first())
        assertEquals(original.last(), simplified.last())
        assertTrue(simplified.size >= 2)
    }

    @Test
    fun `a looser tolerance never keeps more points`() {
        val original = wander(400)
        var previous = Int.MAX_VALUE
        for (tolerance in listOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0, 64.0)) {
            val n = Simplify.douglasPeucker(original, tolerance).size
            assertTrue(n <= previous, "tolerance $tolerance kept $n, more than the tighter one's $previous")
            previous = n
        }
    }

    @Test
    fun `a straight line collapses to its ends`() {
        val straight = List(200) { LatLon(49.6, 11.0 + it * 0.0001) }
        assertEquals(2, Simplify.douglasPeucker(straight, 1.0).size)
    }

    /**
     * A ring simplified to two points is not a smaller lake, it is no lake —
     * a degenerate shape that a fill draws as nothing.
     */
    @Test
    fun `a ring never collapses below four vertices`() {
        val tiny = buildList {
            for (i in 0 until 12) {
                val a = i / 12.0 * 2 * Math.PI
                add(LatLon(49.6 + 0.00002 * sin(a), 11.0 + 0.00003 * cos(a)))
            }
            add(first())
        }
        val simplified = Simplify.ring(tiny, 500.0)
        assertTrue(simplified.size >= 4, "a ring came out with ${simplified.size} vertices")
        assertEquals(simplified.first(), simplified.last())
    }

    /**
     * A main-stem river arrives from Overpass with tens of thousands of
     * near-sorted vertices, which is the exact worst case for the recursive
     * form. This is why the implementation carries its own stack.
     */
    @Test
    fun `a hundred thousand near-sorted points do not blow the stack`() {
        val many = List(100_000) { LatLon(49.0 + it * 1e-7, 11.0 + it * 1e-7) }
        val simplified = Simplify.douglasPeucker(many, 0.5)
        assertTrue(simplified.size >= 2)
        assertEquals(many.first(), simplified.first())
        assertEquals(many.last(), simplified.last())
    }

    @Test
    fun `a closed ring's degenerate closing segment does not blow up`() {
        val ring = buildList {
            for (i in 0 until 40) {
                val a = i / 40.0 * 2 * Math.PI
                add(LatLon(49.6 + 0.002 * sin(a), 11.0 + 0.003 * cos(a)))
            }
            add(first())
        }
        val simplified = Simplify.ring(ring, 10.0)
        assertTrue(simplified.size in 4..ring.size)
        for (p in ring) {
            val nearest = simplified.minOf { Geo.haversine(it, p) }
            assertTrue(nearest < 200.0, "ring vertex lost by $nearest m")
        }
    }
}
