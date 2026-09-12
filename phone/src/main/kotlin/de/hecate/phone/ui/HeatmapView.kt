package de.hecate.phone.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.heat.HeatmapGrid
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Every recording at once, as a density raster, with the routes drawn over it
 * (US-8).
 *
 * §4 still holds: no maps SDK, no tiles, no API key. There is no map behind
 * this — it is your own tracks against an empty ground, which for a route you
 * have run two hundred times is a more recognisable picture than a street map
 * would be.
 *
 * The grid arrives precomputed from `ClusterWorker` (Appendix A.14); this draws
 * it and nothing else. The raster goes through an `ImageBitmap` rather than
 * 20,736 `drawRect` calls, and is scaled up with filtering, which is also what
 * smooths the cell edges into something that reads as density rather than as
 * a spreadsheet.
 */
@Composable
fun HeatmapView(
    grid: HeatmapGrid,
    routes: List<List<LatLon>>,
    modifier: Modifier = Modifier,
    dark: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
    val surface = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow
    val routeColor = if (dark) HecateMapColors.RouteOnHeatDark else HecateMapColors.RouteOnHeatLight
    val strokePx = with(androidx.compose.ui.platform.LocalDensity.current) { 1.2.dp.toPx() }

    // Rebuilt only when the data or the theme changes — this allocates a bitmap.
    val raster = remember(grid, dark) { rasterOf(grid, dark) }

    Box(
        modifier
            .fillMaxWidth()
            // The aspect ratio is the grid's own, so the picture is never
            // stretched: the extent was already grown to match it.
            .aspectRatio(grid.width.toFloat() / grid.height.toFloat()),
    ) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(grid.width.toFloat() / grid.height.toFloat())) {
            drawRect(surface)
            raster?.let {
                drawImage(
                    image = it,
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
                )
            }
            drawRoutes(grid, routes, routeColor, strokePx)
        }
    }
}

/**
 * The routes, as thin lines over the density.
 *
 * Thin and uncased. The first version drew them as ink lines with a surface
 * casing, and the result was three black shapes with a faint blue fringe: the
 * lines sat exactly on top of their own heat — recordings follow routes, that is
 * what makes them routes — and erased the thing the picture exists to show.
 * A warm hue against the blue ramp separates the layers without covering
 * anything, and [strokePx] is a real 1.2 dp so the weight is the same on every
 * screen rather than a hairline on a dense one.
 */
private fun DrawScope.drawRoutes(
    grid: HeatmapGrid,
    routes: List<List<LatLon>>,
    color: Color,
    strokePx: Float,
) {
    val origin = LatLon(grid.originLat, grid.originLon)
    val scaleX = size.width / grid.spanX.toFloat()
    val scaleY = size.height / grid.spanY.toFloat()

    for (points in routes) {
        if (points.size < 2) continue
        val path = Path()
        var started = false
        for (p in points) {
            val l = Geo.toLocal(origin, p)
            val x = ((l[0] - grid.minX) * scaleX).toFloat()
            // Canvas y grows downwards; north must point up.
            val y = ((grid.maxY - l[1]) * scaleY).toFloat()
            if (started) path.lineTo(x, y) else { path.moveTo(x, y); started = true }
        }
        drawPath(path, color, style = Stroke(width = strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * Turns counts into pixels.
 *
 * Density is a magnitude, so the ramp is **one hue, stepped light to dark** —
 * never a rainbow, which encodes order nobody can read back. Blue, so that the
 * one thing drawn on top of it can be warm and stay separable from the scale at
 * every step (see [drawRoutes]).
 *
 * Dark mode is its own ramp rather than an inversion of this one: on a dark
 * ground, "near zero" has to recede *towards* the surface, so the ramp runs
 * dark-to-light, and the steps are chosen against the dark surface rather than
 * flipped from the light ones.
 *
 * The scale is logarithmic. A route run two hundred times and a lane crossed
 * twice differ by two orders of magnitude, and on a linear scale everything but
 * the commute would be indistinguishable from empty.
 */
private fun rasterOf(grid: HeatmapGrid, dark: Boolean): ImageBitmap? {
    if (grid.isEmpty) return null
    val ramp = if (dark) DARK_RAMP else LIGHT_RAMP
    val denominator = ln(1.0 + grid.maxCount)
    val pixels = IntArray(grid.cells.size)
    for (i in grid.cells.indices) {
        val count = grid.cells[i]
        if (count == 0) continue // stays fully transparent: no data is not "cold"
        val t = (ln(1.0 + count) / denominator).coerceIn(0.0, 1.0)
        pixels[i] = sample(ramp, t)
    }
    return Bitmap.createBitmap(pixels, grid.width, grid.height, Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

/** Linear interpolation between the ramp's steps, premultiplied-safe ARGB. */
private fun sample(ramp: IntArray, t: Double): Int {
    val pos = t * (ramp.size - 1)
    val lo = pos.toInt().coerceIn(0, ramp.size - 1)
    val hi = (lo + 1).coerceAtMost(ramp.size - 1)
    val f = pos - lo
    return lerpArgb(ramp[lo], ramp[hi], f)
}

private fun lerpArgb(a: Int, b: Int, f: Double): Int {
    fun ch(shift: Int): Int {
        val x = (a shr shift) and 0xFF
        val y = (b shr shift) and 0xFF
        return (x + (y - x) * f).roundToInt().coerceIn(0, 255)
    }
    return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

private fun argb(alpha: Int, hex: Long): Int =
    Color(hex.toInt() or 0xFF000000.toInt()).copy(alpha = alpha / 255f).toArgb()

/**
 * Light surface: the blue ramp's steps 150 → 700, lightest first.
 *
 * The first step is also the most transparent, so a cell crossed once recedes
 * toward the surface instead of stamping a hard dot on it.
 */
private val LIGHT_RAMP = intArrayOf(
    argb(70, 0xb7d3f6),
    argb(140, 0x86b6ef),
    argb(200, 0x3987e5),
    argb(235, 0x256abf),
    argb(255, 0x184f95),
    argb(255, 0x0d366b),
)

/**
 * Dark surface: the same hue, run the other way — dark and nearly transparent
 * at the bottom so low density sinks into the ground, bright at the top.
 */
private val DARK_RAMP = intArrayOf(
    argb(70, 0x184f95),
    argb(140, 0x256abf),
    argb(200, 0x3987e5),
    argb(235, 0x6da7ec),
    argb(255, 0x9ec5f4),
    argb(255, 0xcde2fb),
)
