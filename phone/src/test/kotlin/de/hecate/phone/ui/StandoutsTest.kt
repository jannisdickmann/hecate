package de.hecate.phone.ui

import de.hecate.phone.db.SessionSummary
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The highlight on the recordings list is the one piece of arithmetic in the
 * phone UI, and a wrong threshold still highlights *something* — so it fails
 * quietly and plausibly rather than visibly. Hence these.
 */
class StandoutsTest {

    /** One hour of active time, so km == km/h and the speeds read directly. */
    private fun rec(
        id: String,
        routeId: Long? = 1L,
        kmh: Double = 10.0,
        hr: Double? = 140.0,
    ) = SessionSummary(
        id = id,
        startedAtUtc = 1_700_000_000_000,
        tzOffsetMinutes = 60,
        exerciseType = "RUNNING",
        activeDurationMs = 3_600_000,
        totalDistanceM = kmh * 1000.0,
        source = "WATCH",
        hcWriteState = 1,
        matchedRouteId = routeId,
        routeName = routeId?.let { "Route $it" },
        avgHr = hr,
    )

    // ------------------------------------------------------------- percentile

    @Test
    fun `percentile interpolates the way a spreadsheet does`() {
        // PERCENTILE({1,2,3,4}, 0.75) = 3.25 — rank 2.25, a quarter of the way
        // from 3 to 4.
        assertEquals(3.25, Standouts.percentile(listOf(1.0, 2.0, 3.0, 4.0), 0.75)!!, 1e-9)
        assertEquals(2.5, Standouts.percentile(listOf(1.0, 2.0, 3.0, 4.0), 0.5)!!, 1e-9)
    }

    @Test
    fun `percentile does not care what order it is given`() {
        val shuffled = listOf(4.0, 1.0, 3.0, 2.0)
        assertEquals(3.25, Standouts.percentile(shuffled, 0.75)!!, 1e-9)
    }

    @Test
    fun `percentile of nothing is nothing, and of one thing is that thing`() {
        assertNull(Standouts.percentile(emptyList(), 0.75))
        assertEquals(7.0, Standouts.percentile(listOf(7.0), 0.75)!!, 1e-9)
    }

    // ---------------------------------------------------------------- speedOf

    @Test
    fun `a recording that covered no ground has no speed rather than zero`() {
        assertNull(Standouts.speedOf(rec("a", kmh = 0.0)))
        assertNull(Standouts.speedOf(rec("b").copy(activeDurationMs = 0)))
    }

    // ---------------------------------------------------------------- compute

    @Test
    fun `the fastest of four on a route is marked, the rest are not`() {
        val out = Standouts.compute(
            listOf(
                rec("slow", kmh = 8.0),
                rec("mid", kmh = 9.0),
                rec("quick", kmh = 10.0),
                rec("fastest", kmh = 12.0),
            ),
        )
        assertTrue(out["fastest"]?.fast == true)
        for (id in listOf("slow", "mid", "quick")) {
            assertFalse(out[id]?.fast == true, "$id should not be marked fast")
        }
    }

    /** Three members is where a route begins to exist, and is still too few for
     *  a quartile to be anything but "the best one". */
    @Test
    fun `a route with fewer than four recordings marks nothing`() {
        val out = Standouts.compute(
            listOf(rec("a", kmh = 8.0), rec("b", kmh = 9.0), rec("c", kmh = 20.0)),
        )
        assertTrue(out.isEmpty(), "expected no highlights, got $out")
    }

    @Test
    fun `recordings without a route are never marked, however fast`() {
        val out = Standouts.compute(
            (1..6).map { rec("free$it", routeId = null, kmh = 5.0 + it * 10) },
        )
        assertTrue(out.isEmpty(), "expected no highlights, got $out")
    }

    @Test
    fun `each route is ranked against itself, not against the others`() {
        val out = Standouts.compute(
            // Route 2 is a flat, fast route; route 1 is a slow hilly one. The
            // best of route 1 must still be marked despite being slower than
            // every single recording on route 2.
            listOf(
                rec("hill1", routeId = 1, kmh = 6.0),
                rec("hill2", routeId = 1, kmh = 6.5),
                rec("hill3", routeId = 1, kmh = 7.0),
                rec("hillBest", routeId = 1, kmh = 8.0),
                rec("flat1", routeId = 2, kmh = 20.0),
                rec("flat2", routeId = 2, kmh = 21.0),
                rec("flat3", routeId = 2, kmh = 22.0),
                rec("flatBest", routeId = 2, kmh = 26.0),
            ),
        )
        assertTrue(out["hillBest"]?.fast == true, "the best of the slow route should still stand out")
        assertTrue(out["flatBest"]?.fast == true)
        assertFalse(out["flat1"]?.fast == true)
    }

    @Test
    fun `speed and heart rate are ranked separately`() {
        val out = Standouts.compute(
            listOf(
                // Fastest of the four, but the calmest heart rate.
                rec("easy", kmh = 14.0, hr = 120.0),
                rec("b", kmh = 9.0, hr = 140.0),
                rec("c", kmh = 8.0, hr = 150.0),
                // Slowest, but by far the hardest effort.
                rec("grind", kmh = 7.0, hr = 180.0),
            ),
        )
        assertEquals(Standout(fast = true, highHr = false), out["easy"])
        assertEquals(Standout(fast = false, highHr = true), out["grind"])
    }

    /** A route whose recordings mostly predate a heart rate strap must not let
     *  those absences lower the bar until the few that have one all qualify. */
    @Test
    fun `heart rate is not ranked when too few recordings have one`() {
        val out = Standouts.compute(
            listOf(
                rec("a", kmh = 8.0, hr = null),
                rec("b", kmh = 9.0, hr = null),
                rec("c", kmh = 10.0, hr = 100.0),
                rec("d", kmh = 11.0, hr = 190.0),
            ),
        )
        assertFalse(out["d"]?.highHr == true, "heart rate should be unranked with only two readings")
        // Speed still ranks, because every recording has one.
        assertTrue(out["d"]?.fast == true)
    }

    @Test
    fun `a recording with no distance is skipped rather than counted as slowest`() {
        val out = Standouts.compute(
            listOf(
                rec("broken", kmh = 0.0),
                rec("a", kmh = 8.0),
                rec("b", kmh = 9.0),
                rec("c", kmh = 10.0),
                rec("d", kmh = 11.0),
            ),
        )
        assertFalse(out["broken"]?.fast == true)
        assertTrue(out["d"]?.fast == true)
    }
}
