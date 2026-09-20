package de.hecate.core.map

import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.heat.HeatmapGrid
import kotlin.math.min

/**
 * The projection every track drawing in this app has always computed inline, now
 * with a name.
 *
 * `PolylineView` and `HeatmapView` each carried their own copy of
 *
 * ```
 * x = xPx + east  * pxPerM
 * y = yPx - north * pxPerM
 * ```
 *
 * which was harmless while the only thing drawn was the track, because a
 * drawing cannot disagree with itself. It stops being harmless the moment a
 * second layer is drawn under the first: a basemap computed from one copy of
 * that formula and a track from another would line up only for as long as
 * nobody edited either. Naming it once means they read one object instead of
 * two formulas, and cannot drift.
 *
 * [origin] is the point local metres are measured from, exactly as
 * [Geo.toLocal] measures them; [xPx] and [yPx] are where that origin lands on
 * the canvas. Canvas y grows downwards and north must point up, hence the minus.
 */
data class MapView(
    val origin: LatLon,
    val pxPerM: Double,
    val xPx: Double,
    val yPx: Double,
) {
    fun canvasX(eastM: Double): Double = xPx + eastM * pxPerM
    fun canvasY(northM: Double): Double = yPx - northM * pxPerM

    /** Where a position lands, as (x, y) canvas pixels. */
    fun canvas(p: LatLon): DoubleArray {
        val l = Geo.toLocal(origin, p)
        return doubleArrayOf(canvasX(l[0]), canvasY(l[1]))
    }

    fun eastAt(x: Double): Double = (x - xPx) / pxPerM
    fun northAt(y: Double): Double = (yPx - y) / pxPerM

    /**
     * The ground inside a canvas rectangle.
     *
     * Exact rather than padded-approximate: the local projection is linear in
     * latitude and longitude, so two opposite corners of the rectangle determine
     * the box completely.
     *
     * It matters that this covers the canvas *corners* and not the track's own
     * bounding box. `PolylineView` pads by 16 px and centres what is left, so
     * there is always ground on screen that the track does not reach — and that
     * ground is exactly where a map is most worth having, because it is where
     * the streets the track did not take are.
     */
    fun visibleBounds(x0: Double, y0: Double, x1: Double, y1: Double): LatLonBounds {
        val sw = Geo.fromLocal(origin, eastAt(minOf(x0, x1)), northAt(maxOf(y0, y1)))
        val ne = Geo.fromLocal(origin, eastAt(maxOf(x0, x1)), northAt(minOf(y0, y1)))
        return LatLonBounds(sw.lat, sw.lon, ne.lat, ne.lon)
    }

    companion object {

        /**
         * The fit `PolylineView` has always done: one scale on both axes so the
         * shape is the shape of the route, centred in what is left, and padded
         * so the outermost point is not the frame.
         *
         * The arithmetic is deliberately the old arithmetic, down to the order
         * of the operations and the one place the original narrowed to `Float`
         * before dividing — this is a refactor, and a refactor that moves a
         * picture by a pixel is not one. `MapViewTest` holds the golden case.
         */
        fun fit(
            origin: LatLon,
            minX: Double,
            minY: Double,
            spanX: Double,
            spanY: Double,
            widthPx: Double,
            heightPx: Double,
            padPx: Double,
        ): MapView {
            val usableW = widthPx - 2 * padPx
            val usableH = heightPx - 2 * padPx
            val scale = min(usableW / spanX, usableH / spanY)
            val offX = padPx + (usableW - spanX * scale).toFloat() / 2f
            val offY = padPx + (usableH - spanY * scale).toFloat() / 2f
            return MapView(
                origin = origin,
                pxPerM = scale,
                xPx = offX - minX * scale,
                yPx = heightPx - offY + minY * scale,
            )
        }

        /**
         * The heatmap's own projection: the raster fills the canvas exactly, so
         * the scale follows from the extent rather than from a fit.
         *
         * Read off the width alone, and that is safe rather than sloppy: the
         * grid's cells are square on the ground and the canvas is given the
         * grid's own aspect ratio, so `spanX/spanY` and `widthPx/heightPx` are
         * the same number by construction and the height would only restate it.
         * One axis rather than two also means the basemap cannot end up scaled
         * by a hair's breadth differently from the route lines beside it.
         */
        fun of(grid: HeatmapGrid, widthPx: Double, heightPx: Double): MapView {
            val pxPerM = if (grid.spanX > 0) widthPx / grid.spanX else 1.0
            return MapView(
                origin = LatLon(grid.originLat, grid.originLon),
                pxPerM = pxPerM,
                xPx = -grid.minX * pxPerM,
                // The raster's top edge is the extent's north edge, and y is
                // measured down from there.
                yPx = grid.maxY * pxPerM,
            )
        }
    }
}
