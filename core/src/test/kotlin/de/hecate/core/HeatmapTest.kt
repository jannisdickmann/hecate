package de.hecate.core

import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.heat.Heatmap
import de.hecate.core.heat.HeatmapCodec
import de.hecate.core.heat.RouteTrack
import de.hecate.core.heat.SessionTrack
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The heatmap is drawn, not read, so a wrong answer still looks like a picture.
 * These pin the parts that would fail silently: the extent, the radius filter,
 * and which way up the raster is.
 */
class HeatmapTest {

    private val home = LatLon(50.10, 8.90)

    /** A point [east]/[north] metres from [home], so tests can speak in metres. */
    private fun at(east: Double, north: Double) = Geo.fromLocal(home, east, north)

    private fun route(id: Long, vararg pts: Pair<Double, Double>) =
        RouteTrack(id, pts.map { at(it.first, it.second) })

    private fun session(id: String, vararg pts: Pair<Double, Double>) =
        SessionTrack(id, pts.map { at(it.first, it.second) })

    /** A square kilometre of route, centred on home. */
    private fun squareRoute(id: Long = 1L) =
        route(id, -500.0 to -500.0, 500.0 to -500.0, 500.0 to 500.0, -500.0 to 500.0)

    // ------------------------------------------------------------ median start

    @Test
    fun `the median start ignores a minority of far-away routes`() {
        val centre = Heatmap.medianStart(
            listOf(
                route(1, 0.0 to 0.0),
                route(2, 100.0 to 100.0),
                route(3, 200.0 to 200.0),
                // Two holidays, a long way off, must not move the centre.
                RouteTrack(4, listOf(LatLon(40.0, 3.0))),
                RouteTrack(5, listOf(LatLon(60.0, 20.0))),
            ),
        )
        assertNotNull(centre)
        assertTrue(Geo.haversine(home, centre) < 500, "centre drifted to $centre")
    }

    @Test
    fun `no routes means no heatmap, because there is no extent`() {
        assertNull(Heatmap.build(emptyList(), listOf(session("s", 0.0 to 0.0))))
    }

    @Test
    fun `a route that runs off the edge of the radius is discarded too`() {
        // Note what this is *not* testing. The centre is an order statistic, so
        // it always sits on some route's start, and that route is therefore
        // never excluded by its distance from itself. A route is only rejected
        // when it genuinely reaches beyond the radius — as this one does.
        assertNull(
            Heatmap.build(
                listOf(route(1, 0.0 to 0.0, 0.0 to 300_000.0)),
                listOf(session("s", 0.0 to 0.0)),
            ),
        )
    }

    // ---------------------------------------------------------- radius filter

    @Test
    fun `a route beyond the radius is discarded and counted`() {
        val g = Heatmap.build(
            listOf(squareRoute(1), squareRoute(2), squareRoute(3), route(9, 0.0 to 300_000.0)),
            emptyList(),
        )
        assertNotNull(g)
        assertEquals(listOf(1L, 2L, 3L), g.routeIds)
        assertEquals(1, g.routesDiscarded)
    }

    @Test
    fun `a recording with one wild fix is discarded whole, not trimmed`() {
        val g = Heatmap.build(
            listOf(squareRoute()),
            listOf(
                session("good", 0.0 to 0.0, 10.0 to 10.0),
                // Everything sane except one sample in the Gulf of Guinea.
                SessionTrack("glitched", listOf(at(0.0, 0.0), LatLon(0.0, 0.0))),
            ),
        )
        assertNotNull(g)
        assertEquals(1, g.sessionsUsed)
        assertEquals(1, g.sessionsDiscarded)
    }

    @Test
    fun `the radius is configurable`() {
        val far = listOf(squareRoute(1), route(2, 0.0 to 50_000.0))
        assertEquals(2, Heatmap.build(far, emptyList())!!.routeIds.size)
        assertEquals(1, Heatmap.build(far, emptyList(), maxRadiusM = 10_000.0)!!.routeIds.size)
    }

    // ------------------------------------------------------------------ extent

    @Test
    fun `the extent comes from the routes, not from the recordings`() {
        val routes = listOf(squareRoute())
        val tight = Heatmap.build(routes, listOf(session("near", 0.0 to 0.0)))!!
        // An outlier recording, well inside the radius but far outside the
        // routes: it must not stretch the picture.
        val withOutlier = Heatmap.build(
            routes,
            listOf(session("near", 0.0 to 0.0), session("far", 40_000.0 to 40_000.0)),
        )!!

        assertEquals(tight.spanX, withOutlier.spanX, 1e-6)
        assertEquals(tight.spanY, withOutlier.spanY, 1e-6)
        assertEquals(tight.minX, withOutlier.minX, 1e-6)
        assertEquals(tight.minY, withOutlier.minY, 1e-6)
    }

    @Test
    fun `the extent always has the raster's aspect ratio`() {
        // A tall route and a wide one must both come out 16:9.
        val tall = Heatmap.build(listOf(route(1, 0.0 to -3000.0, 0.0 to 3000.0)), emptyList())!!
        val wide = Heatmap.build(listOf(route(1, -8000.0 to 0.0, 8000.0 to 0.0)), emptyList())!!
        for (g in listOf(tall, wide)) {
            assertEquals(
                g.width.toDouble() / g.height,
                g.spanX / g.spanY,
                1e-9,
                "extent is not ${g.width}:${g.height}",
            )
        }
    }

    @Test
    fun `growing to the aspect ratio never crops the routes`() {
        // A tall route: the extent grows sideways, and must still contain it.
        // Asserted in the grid's own frame — its origin is the median route
        // start, not `home`, and assuming otherwise is how this test first
        // failed for the wrong reason.
        val r = route(1, 0.0 to -3000.0, 0.0 to 3000.0)
        val g = Heatmap.build(listOf(r), emptyList())!!
        val origin = LatLon(g.originLat, g.originLon)
        for (p in r.points) {
            val l = Geo.toLocal(origin, p)
            assertTrue(l[0] in g.minX..(g.minX + g.spanX), "x ${l[0]} outside ${g.minX}..${g.minX + g.spanX}")
            assertTrue(l[1] in g.minY..g.maxY, "y ${l[1]} outside ${g.minY}..${g.maxY}")
        }
    }

    @Test
    fun `an even number of routes still yields a centre a route started at`() {
        // The regression this was written for: averaging the two middle values
        // put the centre halfway to the outliers, where nothing is, and the
        // radius filter then threw away every route including the real ones.
        val routes = listOf(
            route(1, 0.0 to 0.0),
            route(2, 50.0 to 50.0),
            RouteTrack(3, listOf(LatLon(41.0, 3.0))),
            RouteTrack(4, listOf(LatLon(41.1, 3.1))),
        )
        val centre = Heatmap.medianStart(routes)!!
        assertTrue(
            routes.any { it.points.first() == centre } ||
                routes.any { Geo.haversine(it.points.first(), centre) < 1.0 },
            "centre $centre is not at any route's start",
        )
    }

    @Test
    fun `a route that goes nowhere still gets a usable extent`() {
        val g = Heatmap.build(listOf(route(1, 0.0 to 0.0, 1.0 to 1.0)), emptyList())!!
        assertTrue(g.spanX >= Tuning.HEATMAP_MIN_SPAN_M, "spanX ${g.spanX} collapsed")
        assertTrue(g.spanY > 0)
    }

    // -------------------------------------------------------------------- heat

    @Test
    fun `counts accumulate where recordings overlap`() {
        val g = Heatmap.build(
            listOf(squareRoute()),
            // Three recordings of the same spot, five samples each.
            (1..3).map { SessionTrack("s$it", List(5) { at(0.0, 0.0) }) },
        )!!
        assertEquals(15, g.maxCount)
        assertEquals(15, g.cells.max())
        assertEquals(15, g.cells.sum(), "every sample should land in exactly one cell")
    }

    @Test
    fun `row zero is the north edge`() {
        val g = Heatmap.build(
            listOf(squareRoute()),
            listOf(session("north", 0.0 to 400.0)),
        )!!
        val hot = g.cells.indexOfFirst { it > 0 }
        assertTrue(hot >= 0, "nothing was counted")
        val row = hot / g.width
        assertTrue(row < g.height / 2, "a northern sample landed in row $row of ${g.height}")
    }

    @Test
    fun `samples outside the extent are dropped rather than smeared onto the border`() {
        val g = Heatmap.build(
            listOf(squareRoute()),
            // Inside the radius, far outside the routes' box.
            listOf(SessionTrack("far", List(50) { at(40_000.0, 40_000.0) })),
        )!!
        assertEquals(1, g.sessionsUsed, "the recording itself is within the radius and kept")
        assertEquals(0, g.maxCount, "its samples are off the map and must not pile up on an edge")
        assertTrue(g.isEmpty)
    }

    @Test
    fun `an empty heatmap is still a valid extent to draw routes in`() {
        val g = Heatmap.build(listOf(squareRoute()), emptyList())!!
        assertTrue(g.isEmpty)
        assertEquals(listOf(1L), g.routeIds)
        assertTrue(g.spanX > 0 && g.spanY > 0)
    }

    // ------------------------------------------------------------------- codec

    @Test
    fun `a grid survives the gzip round trip`() {
        val g = Heatmap.build(
            listOf(squareRoute(), route(2, -200.0 to -200.0, 200.0 to 200.0)),
            (1..4).map { i -> SessionTrack("s$i", List(20) { at(it * 10.0, i * 10.0) }) },
        )!!
        val out = ByteArrayOutputStream()
        HeatmapCodec.writeGzip(g, out)
        val back = HeatmapCodec.readGzip(ByteArrayInputStream(out.toByteArray()))
        assertEquals(g, back)
        assertTrue(back.cells.contentEquals(g.cells))
    }

    @Test
    fun `the compressed grid is small enough to keep in a row`() {
        val g = Heatmap.build(listOf(squareRoute()), listOf(session("s", 0.0 to 0.0)))!!
        val out = ByteArrayOutputStream()
        HeatmapCodec.writeGzip(g, out)
        // A mostly empty raster compresses to almost nothing; this is a guard
        // against the format quietly becoming something that does not.
        assertTrue(out.size() < 16_000, "grid gzipped to ${out.size()} B")
    }
}
