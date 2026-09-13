package de.hecate.phone.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.heat.Heatmap
import de.hecate.core.heat.HeatmapGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * The grid arrives precomputed from `ClusterWorker` (Appendix A.14); this
 * colours it and nothing else. Three things make the difference between a
 * heatmap and a spreadsheet with the numbers hidden, and only the last of them
 * is here: cells a fixed 15 m on the ground however much ground there is, tracks
 * drawn as lines rather than as the points a receiver happened to report — both
 * in `Heatmap.build` — and a Gaussian blur before the colour ramp, which is what
 * turns counts into density (A.21).
 */
@Composable
fun HeatmapView(
    grid: HeatmapGrid,
    routes: List<List<LatLon>>,
    raster: ImageBitmap?,
    modifier: Modifier = Modifier,
    dark: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
) {
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val routeColor = if (dark) HecateMapColors.RouteOnHeatDark else HecateMapColors.RouteOnHeatLight
    val strokePx = with(androidx.compose.ui.platform.LocalDensity.current) { 1.2.dp.toPx() }

    // The aspect ratio is the grid's own, so the picture is never stretched: the
    // extent was already grown to match it.
    Canvas(modifier.aspectRatio(grid.width.toFloat() / grid.height.toFloat())) {
        drawRect(surface)
        raster?.let {
            drawImage(
                image = it,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
        }
        drawRoutes(grid, routes, routeColor, strokePx)
    }
}

/**
 * The coloured raster, built off the main thread and kept until the data or the
 * theme changes.
 *
 * Hoisted out of [HeatmapView] so that the card on the routes list and the
 * full-screen view share one bitmap rather than building the same twelve
 * megabytes twice, and suspended rather than computed in composition because at
 * a fixed cell size the raster is now millions of cells: blurring and colouring
 * them is tens of milliseconds of real work, and composition is the frame the
 * user is waiting for.
 *
 * Null until it is ready, which is a frame or two showing the empty extent —
 * that is the honest thing to show while there is nothing to draw yet, and it is
 * the same surface colour the finished picture sits on, so nothing flashes.
 */
@Composable
fun rememberHeatmapRaster(
    grid: HeatmapGrid,
    dark: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
): ImageBitmap? {
    val state = produceState<ImageBitmap?>(initialValue = null, grid, dark) {
        value = withContext(Dispatchers.Default) { rasterOf(grid, dark) }
    }
    return state.value
}

/**
 * The heatmap, full screen, draggable and pinchable.
 *
 * The card on the routes list is a thumbnail of a raster that is now far finer
 * than the card can show; this is where that resolution is for. A `Dialog` with
 * the platform's own width rule turned off, so it covers the navigation bar too
 * — the alternative, another entry in the app's screen enum, would put a
 * picture into the back stack beside the panels and the details, which it is
 * not.
 *
 * Panning is clamped to the picture's own edges: dragging it off the screen
 * entirely and being left with a blank surface and no way back is the failure
 * mode of every hand-rolled pan, and the fix is four lines.
 */
@Composable
fun HeatmapFullScreen(
    grid: HeatmapGrid,
    routes: List<List<LatLon>>,
    raster: ImageBitmap?,
    caption: String,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                var scale by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                val widthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
                val heightPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxHeight.toPx() }

                Box(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                                // How far the scaled picture may travel before
                                // its own edge comes inside the screen. Measured
                                // against the *drawn* picture rather than the
                                // window: the picture is 16:9 inside a taller
                                // screen, so its vertical slack is not the
                                // window's.
                                val drawnH = widthPx * grid.height / grid.width
                                val slackX = maxOf(0f, (widthPx * scale - widthPx) / 2f)
                                val slackY = maxOf(0f, (drawnH * scale - heightPx) / 2f)
                                offset = Offset(
                                    (offset.x + pan.x).coerceIn(-slackX, slackX),
                                    (offset.y + pan.y).coerceIn(-slackY, slackY),
                                )
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    HeatmapView(
                        grid = grid,
                        routes = routes,
                        raster = raster,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            ),
                    )
                }

                Text(
                    caption,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                            MaterialTheme.shapes.small,
                        ),
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Schließen")
                }
            }
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
 *
 * The blur comes first, and the normalisation is against the *blurred* maximum
 * rather than the raw one — a blur spreads a peak out and lowers it, and
 * dividing by the height of a peak that is no longer there would leave the whole
 * picture a shade too faint.
 */
private fun rasterOf(grid: HeatmapGrid, dark: Boolean): ImageBitmap? {
    if (grid.isEmpty) return null
    val ramp = if (dark) DARK_RAMP else LIGHT_RAMP
    val density = Heatmap.blur(grid.cells, grid.width, grid.height)
    val peak = density.max()
    if (peak <= 0f) return null
    val denominator = ln(1.0 + peak)

    val pixels = IntArray(density.size)
    for (i in density.indices) {
        val value = density[i]
        if (value < FADE_FLOOR) continue // stays fully transparent
        val t = (ln(1.0 + value) / denominator).coerceIn(0.0, 1.0)
        val argb = sample(ramp, t)
        // The outermost ring of the blur fades out rather than stopping at the
        // ramp's lowest step, which would draw a hard edge around every track —
        // the exact artefact the blur is there to remove.
        val fade = (t / LOW_FADE).coerceAtMost(1.0)
        pixels[i] = scaleAlpha(argb, fade)
    }
    return Bitmap.createBitmap(pixels, grid.width, grid.height, Bitmap.Config.ARGB_8888)
        .asImageBitmap()
}

/** Below this, a cell is the tail of somebody else's blur and is not drawn. */
private const val FADE_FLOOR = 0.02f

/** The share of the scale over which the lowest step fades in from nothing. */
private const val LOW_FADE = 0.18

/** How far the full-screen view zooms in. Past about eight the cells are visible
 *  as cells and there is nothing more to see. */
private const val MAX_ZOOM = 8f

/** Linear interpolation between the ramp's steps, premultiplied-safe ARGB. */
private fun sample(ramp: IntArray, t: Double): Int {
    val pos = t * (ramp.size - 1)
    val lo = pos.toInt().coerceIn(0, ramp.size - 1)
    val hi = (lo + 1).coerceAtMost(ramp.size - 1)
    val f = pos - lo
    return lerpArgb(ramp[lo], ramp[hi], f)
}

private fun scaleAlpha(argb: Int, factor: Double): Int {
    val alpha = (((argb ushr 24) and 0xFF) * factor).roundToInt().coerceIn(0, 255)
    return (alpha shl 24) or (argb and 0x00FFFFFF)
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
