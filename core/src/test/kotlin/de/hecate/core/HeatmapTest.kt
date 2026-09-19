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
    fun `the card's extent comes from the routes, not from the recordings`() {
        val routes = listOf(squareRoute())
        val tight = Heatmap.build(routes, listOf(session("near", 0.0 to 0.0)))!!
        // Two recordings that went elsewhere, one of them a long way: on the
        // card they colour what they cross and move nothing.
        val withOthers = Heatmap.build(
            routes,
            listOf(
                session("near", 0.0 to 0.0),
                session("beyond", 900.0 to 900.0),
                session("far", 40_000.0 to 40_000.0),
            ),
        )!!

        assertEquals(tight.spanX, withOthers.spanX, 1e-6)
        assertEquals(tight.spanY, withOthers.spanY, 1e-6)
        assertEquals(tight.minX, withOthers.minX, 1e-6)
        assertEquals(tight.minY, withOthers.minY, 1e-6)
    }

    @Test
    fun `the full picture holds every recording the radius kept`() {
        // The regression this exists for: the full-screen view used to be the
        // card's extent enlarged, so tracks ended in mid-air at an invisible
        // line — on one real history a fifth of all sample points did.
        val g = Heatmap.build(
            listOf(squareRoute()),
            listOf(
                SessionTrack("beyond", List(20) { at(4_000.0 + it * 5, 3_000.0) }),
                SessionTrack("far", List(20) { at(-9_000.0 - it * 5, -7_000.0) }),
            ),
            extent = Heatmap.Extent.EVERYTHING,
        )!!
        val origin = LatLon(g.originLat, g.originLon)
        for (corner in listOf(at(4_095.0, 3_000.0), at(-9_095.0, -7_000.0))) {
            val l = Geo.toLocal(origin, corner)
            assertTrue(l[0] in g.minX..(g.minX + g.spanX), "x ${l[0]} outside ${g.minX}..${g.minX + g.spanX}")
            assertTrue(l[1] in g.minY..g.maxY, "y ${l[1]} outside ${g.minY}..${g.maxY}")
        }
        assertEquals(0, g.sessionsOutsideExtent, "nothing may be left out of this one")
        assertTrue(g.maxCount > 0, "the recordings contributed no heat")
    }

    @Test
    fun `a recording outside the radius moves neither extent`() {
        // The radius filter runs first, and it is the only thing that removes a
        // recording from the full picture. A fix in the Gulf of Guinea would
        // otherwise make the extent a continent wide.
        val routes = listOf(squareRoute())
        val clean = Heatmap.build(routes, emptyList(), extent = Heatmap.Extent.EVERYTHING)!!
        val glitched = Heatmap.build(
            routes,
            listOf(SessionTrack("glitched", listOf(at(0.0, 0.0), LatLon(0.0, 0.0)))),
            extent = Heatmap.Extent.EVERYTHING,
        )!!
        assertEquals(clean.spanX, glitched.spanX, 1e-6)
        assertEquals(clean.spanY, glitched.spanY, 1e-6)
        assertEquals(1, glitched.sessionsDiscarded)
    }

    @Test
    fun `a raster says which ground it was built for`() {
        // So that a build which changed what "the card" means can tell that the
        // stored one answers the old question — see the codec test for what a
        // raster written before this field reads as.
        assertEquals(Heatmap.Extent.ROUTES, Heatmap.build(listOf(squareRoute()), emptyList())!!.extent)
        assertEquals(
            Heatmap.Extent.EVERYTHING,
            Heatmap.build(listOf(squareRoute()), emptyList(), extent = Heatmap.Extent.EVERYTHING)!!.extent,
        )
    }

    @Test
    fun `the card is grown to its ratio and the full picture is not`() {
        // A tall route and a wide one: on the card both come out 16:9, because a
        // card is a fixed shape in a list. The same ground drawn for the view
        // that pans keeps its own proportions.
        val tall = listOf(route(1, 0.0 to -3000.0, 0.0 to 3000.0))
        val wide = listOf(route(1, -8000.0 to 0.0, 8000.0 to 0.0))
        for (r in listOf(tall, wide)) {
            val g = Heatmap.build(r, emptyList())!!
            assertEquals(
                g.width.toDouble() / g.height,
                g.spanX / g.spanY,
                1e-9,
                "extent is not ${g.width}:${g.height}",
            )
            // ...to within the rounding to whole cells, which is where the ratio
            // now comes from: the extent is grown to it and then measured in
            // cells, rather than cut into a fixed number of them.
            assertEquals(Tuning.HEATMAP_ASPECT, g.spanX / g.spanY, 0.01)
        }
        val full = Heatmap.build(tall, emptyList(), extent = Heatmap.Extent.EVERYTHING)!!
        assertTrue(
            full.spanY > full.spanX,
            "a 6 km tall route came out ${full.spanX.toInt()} x ${full.spanY.toInt()} m",
        )
    }

    // -------------------------------------------------------------- cell size

    @Test
    fun `a cell is the same size on the ground whatever the heatmap spans`() {
        // The regression this exists for: with a fixed 192x108 raster, a cell was
        // 40 m across a town and 400 m across a county, so how much detail the
        // picture had depended on where else the user had been.
        val town = Heatmap.build(listOf(squareRoute()), emptyList())!!
        val county = Heatmap.build(listOf(route(1, -8_000.0 to -4_500.0, 8_000.0 to 4_500.0)), emptyList())!!

        assertEquals(Tuning.HEATMAP_CELL_M, town.cellM, 1e-9)
        assertEquals(Tuning.HEATMAP_CELL_M, county.cellM, 1e-9)
        assertTrue(county.width > town.width, "the bigger extent should be the bigger raster")
        for (g in listOf(town, county)) {
            assertEquals(g.width * g.cellM, g.spanX, 1e-6, "cells do not tile the extent")
            assertEquals(g.height * g.cellM, g.spanY, 1e-6)
        }
    }

    @Test
    fun `the cell doubles rather than letting the raster grow without bound`() {
        // 100 km of radius is the most the extent can ever be, and at 12 m that
        // is a hundred million cells. The cap trades resolution for existing.
        // Inside the radius — a route whose own ends are 100 km apart is thrown
        // out before any of this — but far past what 15 m cells can cover.
        val huge = Heatmap.build(
            listOf(route(1, -35_000.0 to -20_000.0, 35_000.0 to 20_000.0)),
            emptyList(),
        )!!
        assertTrue(
            huge.width.toLong() * huge.height <= Tuning.HEATMAP_MAX_CELLS,
            "${huge.width}x${huge.height} is past the cap",
        )
        // Doubled, not scaled to fit: the cells of two rasters of the same ground
        // still line up.
        val factor = huge.cellM / Tuning.HEATMAP_CELL_M
        assertEquals(factor, Math.pow(2.0, Math.round(kotlin.math.ln(factor) / kotlin.math.ln(2.0)).toDouble()), 1e-9)
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
        // Three, not fifteen: the raster counts passes, not samples. Five samples
        // in one place is one recording standing still for five seconds, and
        // under the old rule a wait at a level crossing outweighed a route
        // ridden sixty times.
        assertEquals(3, g.maxCount)
        assertEquals(3, g.cells.max())
        assertEquals(3, g.cells.sum(), "a stationary recording should touch exactly one cell")
    }

    @Test
    fun `the line between two samples is drawn, not just its ends`() {
        // 160 m apart is a few seconds of cycling and ten cells of ground. The
        // old raster left a dotted line; a heatmap of dots is what "pixely" was.
        val g = Heatmap.build(
            listOf(squareRoute()),
            listOf(session("fast", -80.0 to 0.0, 80.0 to 0.0)),
        )!!
        val hot = g.cells.count { it > 0 }
        assertTrue(hot >= 160.0 / g.cellM - 1, "only $hot cells for 160 m of track")

        // And they are contiguous: one run of cells in one row, no gaps.
        val row = g.cells.indexOfFirst { it > 0 } / g.width
        val line = (0 until g.width).map { g.cells[row * g.width + it] }
        val from = line.indexOfFirst { it > 0 }
        val to = line.indexOfLast { it > 0 }
        assertTrue((from..to).all { line[it] > 0 }, "the drawn line has holes in it")
    }

    @Test
    fun `a dropout is not drawn as a straight line through unknown ground`() {
        // Same rule and same constant as resampling a track: past
        // MAX_INTERPOLATION_GAP_M nobody knows what happened in between.
        val g = Heatmap.build(
            listOf(squareRoute()),
            listOf(session("gap", -400.0 to 0.0, 400.0 to 0.0)),
        )!!
        assertEquals(2, g.cells.count { it > 0 }, "the gap was bridged")
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
        // ...and the caption has to be able to say so: a recording that drew
        // nothing looks exactly like one that was never made.
        assertEquals(1, g.sessionsOutsideExtent)
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

    @Test
    fun `a real-sized raster stays a row rather than a file`() {
        // Twelve-metre cells over a 10 km box is a quarter of a million of them,
        // and the whole reason the payload is run-length encoded. What must not
        // happen is that the stored size follows the *area* rather than the
        // ground actually covered.
        val g = Heatmap.build(
            listOf(route(1, -5_000.0 to -3_000.0, 5_000.0 to 3_000.0)),
            listOf(session("commute", -5_000.0 to -3_000.0, 5_000.0 to 3_000.0)),
        )!!
        assertTrue(g.width * g.height > 100_000, "raster is only ${g.width}x${g.height}")
        val out = ByteArrayOutputStream()
        HeatmapCodec.writeGzip(g, out)
        assertTrue(out.size() < 100_000, "grid gzipped to ${out.size()} B")
    }

    @Test
    fun `a raster stored before the cell had a size still reads`() {
        // What a v4 blob looks like: every cell as an integer, no run lengths,
        // and nothing about the size of one. Both are recoverable, and the
        // alternative is a routes screen with no picture until the next
        // clustering.
        val legacy = """
            {"originLat":50.1,"originLon":8.9,"minX":-100.0,"minY":-50.0,
             "spanX":192.0,"spanY":108.0,"width":4,"height":2,
             "cells":[0,0,3,0,0,1,0,0],"maxCount":3,"routeIds":[1],
             "sessionsUsed":2,"sessionsDiscarded":0,"routesDiscarded":0}
        """.trimIndent()

        val g = HeatmapCodec.decode(legacy)
        assertEquals(8, g.cells.size)
        assertEquals(3, g.cells.max())
        assertEquals(48.0, g.cellM, 1e-9, "the old cell size is its extent over its width")
    }

    @Test
    fun `run-length encoding is exactly reversible`() {
        val cells = IntArray(100).also {
            it[40] = 5; it[41] = 5; it[42] = 1; it[99] = 7
        }
        val runs = HeatmapCodec.runLengthEncode(cells)
        assertTrue(runs.size < cells.size, "encoding did not compress a run")
        assertTrue(HeatmapCodec.runLengthDecode(runs, cells.size).contentEquals(cells))
        // And a truncated payload yields a short picture rather than an exception.
        assertTrue(HeatmapCodec.runLengthDecode(runs, 42).contentEquals(cells.copyOf(42)))
    }

    // -------------------------------------------------------------------- blur

    @Test
    fun `the blur spreads a count over its neighbours without inventing more`() {
        val w = 41
        val h = 41
        val cells = IntArray(w * h)
        cells[20 * w + 20] = 100

        val blurred = Heatmap.blur(cells, w, h, sigmaCells = 2.0)
        val centre = blurred[20 * w + 20]
        val near = blurred[20 * w + 22]
        val far = blurred[20 * w + 32]

        assertTrue(centre > near, "the blur did not keep its peak where the data is")
        assertTrue(near > far, "the blur is not falling off with distance")
        assertTrue(far < 0.01f, "a cell ten away from anything is not empty")
        // Mass is conserved: a blur redistributes, it does not amplify. Well away
        // from the edges, where clamping duplicates what is there.
        assertEquals(100.0, blurred.sum().toDouble(), 0.5)
    }

    @Test
    fun `blurring nothing is still nothing`() {
        val blurred = Heatmap.blur(IntArray(16), 4, 4)
        assertTrue(blurred.all { it == 0f })
    }
}
