package de.hecate.core

import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.heat.HeatmapGrid
import de.hecate.core.map.MapView
import kotlin.math.abs
import kotlin.math.min
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The refactor guard.
 *
 * `PolylineView` computed its own fit inline for as long as it has existed, and
 * moving that arithmetic into [MapView] is the one change in the whole basemap
 * that can break a picture which currently works. So the old expression is
 * written out here verbatim and the new one has to agree with it.
 */
class MapViewTest {

    /** `PolylineView` as it was before [MapView] existed, copied rather than
     *  called, so that editing the real one cannot edit the reference too. */
    private fun legacy(
        local: List<DoubleArray>,
        widthPx: Float,
        heightPx: Float,
    ): List<FloatArray> {
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (p in local) {
            minX = min(minX, p[0]); maxX = maxOf(maxX, p[0])
            minY = min(minY, p[1]); maxY = maxOf(maxY, p[1])
        }
        val spanX = (maxX - minX).coerceAtLeast(1.0)
        val spanY = (maxY - minY).coerceAtLeast(1.0)
        val pad = 16f
        val usableW = widthPx - 2 * pad
        val usableH = heightPx - 2 * pad
        val scale = min(usableW / spanX, usableH / spanY)
        val offX = pad + (usableW - spanX * scale).toFloat() / 2f
        val offY = pad + (usableH - spanY * scale).toFloat() / 2f
        return local.map {
            floatArrayOf(
                offX + ((it[0] - minX) * scale).toFloat(),
                heightPx - offY - ((it[1] - minY) * scale).toFloat(),
            )
        }
    }

    private fun trackOf(n: Int): List<LatLon> = List(n) { i ->
        LatLon(49.6 + i * 0.00037, 11.0 + i * 0.00061 - (i / 40.0) * 0.0002)
    }

    /**
     * The golden case: the same track, through the old arithmetic and the new,
     * has to land on the same pixels.
     *
     * The tolerance is a thousandth of a pixel and it is not a fudge — it is the
     * exact size of the one difference between the two expressions. The original
     * adds an offset and a scaled distance *as floats*; [MapView] folds the
     * offset into its origin and adds in double before anything narrows. Those
     * can differ by one unit in the last place of a float, which is a ten
     * thousandth of a pixel here, and by nothing else: there is no other
     * operation in either version.
     */
    @Test
    fun `fit reproduces the drawing PolylineView has always made`() {
        for (size in listOf(220f to 1080f, 411f to 980f, 360f to 360f)) {
            val (height, width) = size
            val points = trackOf(300)
            val origin = points.first()
            val local = points.map { Geo.toLocal(origin, it) }
            val expected = legacy(local, width, height)

            var minX = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (p in local) {
                minX = min(minX, p[0]); maxX = maxOf(maxX, p[0])
                minY = min(minY, p[1]); maxY = maxOf(maxY, p[1])
            }
            val view = MapView.fit(
                origin = origin,
                minX = minX, minY = minY,
                spanX = (maxX - minX).coerceAtLeast(1.0),
                spanY = (maxY - minY).coerceAtLeast(1.0),
                widthPx = width.toDouble(), heightPx = height.toDouble(), padPx = 16.0,
            )

            for (i in local.indices) {
                assertTrue(
                    abs(view.canvasX(local[i][0]) - expected[i][0]) < 1e-3,
                    "x at $i on ${width}x$height: ${view.canvasX(local[i][0])} vs ${expected[i][0]}",
                )
                assertTrue(
                    abs(view.canvasY(local[i][1]) - expected[i][1]) < 1e-3,
                    "y at $i on ${width}x$height: ${view.canvasY(local[i][1])} vs ${expected[i][1]}",
                )
            }
        }
    }

    @Test
    fun `north points up and east points right`() {
        val origin = LatLon(49.6, 11.0)
        val view = MapView(origin, pxPerM = 0.1, xPx = 50.0, yPx = 200.0)
        assertTrue(view.canvasX(100.0) > view.canvasX(0.0))
        assertTrue(view.canvasY(100.0) < view.canvasY(0.0))
    }

    /**
     * The corners of the canvas, not the corners of the track.
     *
     * `PolylineView` pads by sixteen pixels and centres what is left, so there
     * is always ground on screen the track does not reach — and a basemap query
     * that asked for the track's own box would leave that ground empty, which is
     * precisely where the streets the runner did not take are.
     */
    @Test
    fun `visibleBounds covers the whole canvas and round trips`() {
        val origin = LatLon(49.6, 11.0)
        val view = MapView.fit(origin, -500.0, -300.0, 1000.0, 600.0, 1080.0, 220.0, 16.0)
        val bounds = view.visibleBounds(0.0, 0.0, 1080.0, 220.0)

        val sw = Geo.toLocal(origin, LatLon(bounds.minLat, bounds.minLon))
        val ne = Geo.toLocal(origin, LatLon(bounds.maxLat, bounds.maxLon))
        assertEquals(0.0, view.canvasX(sw[0]), 1e-6)
        assertEquals(1080.0, view.canvasX(ne[0]), 1e-6)
        assertEquals(220.0, view.canvasY(sw[1]), 1e-6)
        assertEquals(0.0, view.canvasY(ne[1]), 1e-6)

        // The track's own box is strictly inside what the canvas shows.
        assertTrue(bounds.contains(Geo.fromLocal(origin, -500.0, -300.0)))
        assertTrue(bounds.contains(Geo.fromLocal(origin, 500.0, 300.0)))
    }

    @Test
    fun `the heatmap projection agrees with the one HeatmapView draws with`() {
        val grid = HeatmapGrid(
            originLat = 49.6, originLon = 11.0,
            minX = -2000.0, minY = -1500.0,
            spanX = 4000.0, spanY = 2250.0,
            width = 267, height = 150, cellM = 15.0,
            cells = IntArray(267 * 150), maxCount = 1,
            routeIds = emptyList(), sessionsUsed = 1, sessionsDiscarded = 0, routesDiscarded = 0,
        )
        val widthPx = 1080.0
        val heightPx = widthPx * grid.spanY / grid.spanX
        val view = MapView.of(grid, widthPx, heightPx)

        // HeatmapView's own arithmetic, as `drawRoutes` performs it.
        val scaleX = widthPx / grid.spanX
        val scaleY = heightPx / grid.spanY
        for (p in listOf(LatLon(49.6, 11.0), LatLon(49.61, 11.02), LatLon(49.59, 10.98))) {
            val l = Geo.toLocal(LatLon(grid.originLat, grid.originLon), p)
            assertEquals((l[0] - grid.minX) * scaleX, view.canvasX(l[0]), 1e-6)
            assertEquals((grid.maxY - l[1]) * scaleY, view.canvasY(l[1]), 1e-6)
        }
    }
}
