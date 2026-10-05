package de.hecate.phone.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.text.rememberTextMeasurer
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.heat.Heatmap
import de.hecate.core.heat.HeatmapGrid
import de.hecate.core.map.KarteIndex
import de.hecate.core.map.MapView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Every recording at once, as a density raster, with the routes drawn over it
 * (US-8).
 *
 * §4 still holds where it was about something: no maps SDK, no API key, no
 * billing, no tile library. There *is* now a map behind this, and it is a dim
 * one drawn under the raster rather than over it (A.29) — so it shows through
 * where you have not been and is progressively buried where you have, and the
 * picture is still your own tracks. For a route you have run two hundred times
 * that was always the more recognisable half; what the ground adds is *where*.
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
    /** What the picture is currently magnified by, when it sits inside a
     *  `graphicsLayer` that pinches. It changes *which* map is wanted, not only
     *  how it is drawn: at eight times zoom the ground on screen is an eighth as
     *  wide and deserves a denser map, and every stroke has to be divided back
     *  down or a 1 dp road becomes an 8 dp blob. */
    zoom: Float = 1f,
    /** The part of the drawn picture actually on screen, in the picture's own
     *  pixels. Null means all of it, which is true of every view but the pinched
     *  one. */
    window: Rect? = null,
) {
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val routeColor = if (dark) HecateMapColors.RouteOnHeatDark else HecateMapColors.RouteOnHeatLight
    val density = androidx.compose.ui.platform.LocalDensity.current
    val strokePx = with(density) { 1.2.dp.toPx() }
    // The heat is a one-hue blue ramp, so water here is drawn in the same
    // neutral ink as the roads: a blue river crossing the ramp would read as a
    // value on it.
    val basemapColors = remember(dark) { BasemapColors.forHeat(dark) }
    val measurer = rememberTextMeasurer()

    // The aspect ratio is the grid's own, so the picture is never stretched: the
    // extent is the shape of the ground it covers, within the range the two
    // views can show (Tuning.HEATMAP_MIN_ASPECT/MAX_ASPECT).
    BoxWithConstraints(modifier.aspectRatio(grid.width.toFloat() / grid.height.toFloat())) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val view = remember(grid, widthPx, heightPx) {
            MapView.of(grid, widthPx.toDouble(), heightPx.toDouble())
        }
        val visible = window ?: Rect(0f, 0f, widthPx, heightPx)
        val scene = rememberBasemapScene(view, visible, zoom)
        val placed = rememberPlacedLabels(scene, visible, zoom)

        Canvas(Modifier.fillMaxSize()) {
            drawRect(surface)
            // Under the raster, and that is the right way round: the raster is
            // transparent on ground nobody has been on, so the map shows through
            // where you have not been and is progressively buried where you have.
            scene?.let { drawBasemap(it, placed, basemapColors, measurer, zoom) }
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
 * A different picture from the card, not an enlargement of it: this one covers
 * every recording the radius filter kept, in whatever shape that ground is
 * (`Heatmap.Extent.EVERYTHING`), because a view that pans has no reason to leave
 * anything out and no reason to be 16:9. Its raster is the larger of the two and
 * is built here rather than hoisted, so that it exists while this dialog does
 * and not a moment longer.
 *
 * A `Dialog` with the platform's own width rule turned off and drawn behind both
 * system bars, so the picture's surface runs to every edge in light and dark
 * alike rather than stopping at a grey (light) or black (dark) strip top and
 * bottom; only the controls are kept clear of the bars. The bars' icons are set
 * to match the surface explicitly, since a dialog window does not inherit the
 * activity's edge-to-edge styling — the alternative, another entry in the app's screen enum,
 * would put a picture into the back stack beside the panels and the details,
 * which it is not.
 *
 * [state] is null while the raster is still being read off the database, which
 * on a large history is long enough to notice; a spinner is the honest thing to
 * show in that window.
 */
@Composable
fun HeatmapFullScreen(
    state: HeatmapState?,
    caption: String,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val dark = androidx.compose.foundation.isSystemInDarkTheme()
        val view = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
            if (window != null) {
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars),
            ) {
                val density = androidx.compose.ui.platform.LocalDensity.current
                val widthPx = with(density) { maxWidth.toPx() }
                val heightPx = with(density) { maxHeight.toPx() }
                val grid = state?.grid
                val raster = grid?.let { rememberHeatmapRaster(it) }

                if (grid == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    // Fitted, not cropped: whichever side runs out first decides
                    // the drawn size, so the whole picture is on screen before a
                    // finger touches it.
                    val pictureAspect = grid.width.toFloat() / grid.height.toFloat()
                    val fitWidth = pictureAspect >= widthPx / heightPx
                    val drawnW = if (fitWidth) widthPx else heightPx * pictureAspect
                    val drawnH = drawnW / pictureAspect

                    var scale by remember { mutableFloatStateOf(1f) }
                    var offset by remember { mutableStateOf(Offset.Zero) }

                    Box(
                        Modifier
                            .fillMaxSize()
                            .pointerInput(grid) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    val next = Viewport(scale, offset).pinched(
                                        centroid = centroid - Offset(size.width / 2f, size.height / 2f),
                                        pan = pan,
                                        zoom = zoom,
                                        windowW = widthPx,
                                        windowH = heightPx,
                                        drawnW = drawnW,
                                        drawnH = drawnH,
                                    )
                                    scale = next.scale
                                    offset = next.offset
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        HeatmapView(
                            grid = grid,
                            routes = state.routes,
                            raster = raster,
                            modifier = (if (fitWidth) Modifier.fillMaxWidth() else Modifier.fillMaxHeight())
                                .graphicsLayer(
                                    scaleX = scale,
                                    scaleY = scale,
                                    translationX = offset.x,
                                    translationY = offset.y,
                                ),
                            // The basemap must not simply ride along inside the
                            // layer. It is the one thing on this screen that is
                            // a vector, and at eight times zoom the raster below
                            // it is an interpolated bitmap — so this is where a
                            // crisp map earns the most, and where a magnified
                            // one would look worst.
                            zoom = scale,
                            window = Viewport(scale, offset).window(widthPx, heightPx, drawnW, drawnH),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        caption,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // ODbL: the obligation is met by naming the source wherever
                    // the data is presented at size, and in the app's own
                    // information. This is the "at size" half; the settings
                    // card is the other. Not on the small drawings, which have
                    // no room for it.
                    Text(
                        KarteIndex.ATTRIBUTION,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
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
 * Where the picture is and how big, as the full-screen view holds it.
 *
 * [offset] is the `graphicsLayer` translation, in pixels, of a picture that
 * scales about its own centre — so both fields are exactly what that modifier
 * takes, and the pinch arithmetic below has somewhere to be tested from.
 */
internal data class Viewport(val scale: Float, val offset: Offset)

/**
 * One pinch, applied.
 *
 * The part worth writing down is that a zoom has a *place*. The picture scales
 * about its own centre, so left to itself a pinch magnifies the middle of the
 * screen however far from the middle the fingers are — which is what "it seems
 * to zoom to some arbitrary point" describes, and it is arbitrary in the precise
 * sense that it has nothing to do with the gesture.
 *
 * Holding the ground still under the fingers is one line of algebra. A point p
 * of the picture is drawn at `s·p + t`; for the point currently under the
 * centroid c to stay under it after the scale becomes `s' = k·s`, the
 * translation has to become
 *
 *     t' = c − k·(c − t) = c·(1 − k) + k·t
 *
 * with [centroid] measured from the centre the picture scales about, which is
 * why the caller subtracts half the window before passing it. Panning is then
 * added on top.
 *
 * What is left is how far it may travel: only as far as its own edge, so no
 * margin of surface is ever dragged into view and the picture cannot be lost off
 * the side of the screen. Measured against the *drawn* picture rather than
 * against the window, whose shape it does not share.
 *
 * That clamp also decides what happens on the axis where the picture is still
 * smaller than the window — a wide picture on an upright phone, before it has
 * been magnified enough to overflow. There it stays centred, and the anchoring
 * on that axis is quietly given up, because honouring it would mean sliding the
 * picture up the screen and pulling empty surface in below it. Once the picture
 * covers the window, which is exactly when there is somewhere to go, the ground
 * stays under the fingers on both axes and provably so: the offset the anchoring
 * asks for is |c|·(s − 1), the clamp allows (drawn·s − window) / 2, and for any
 * centroid inside a window the picture covers, the first is the smaller.
 */
/**
 * Which part of the drawn picture is actually on screen.
 *
 * The picture is drawn [drawnW] by [drawnH], centred in the window, then scaled
 * about its own centre and translated — so a picture point p lands at
 * `windowW/2 + (p − drawnW/2)·s + t`, and setting that to the two edges of the
 * window gives the two ends of what is visible:
 *
 *     centre   = drawnW/2 − offset.x/scale
 *     half     = windowW/(2·scale)
 *
 * This is what makes the basemap a *different* map at eight times zoom rather
 * than the same one magnified: the query is for the ground in this rectangle,
 * at a level of detail that follows from how much of it there is.
 */
internal fun Viewport.window(windowW: Float, windowH: Float, drawnW: Float, drawnH: Float): Rect {
    val cx = drawnW / 2f - offset.x / scale
    val cy = drawnH / 2f - offset.y / scale
    val halfW = windowW / (2f * scale)
    val halfH = windowH / (2f * scale)
    return Rect(
        left = (cx - halfW).coerceIn(0f, drawnW),
        top = (cy - halfH).coerceIn(0f, drawnH),
        right = (cx + halfW).coerceIn(0f, drawnW),
        bottom = (cy + halfH).coerceIn(0f, drawnH),
    )
}

internal fun Viewport.pinched(
    centroid: Offset,
    pan: Offset,
    zoom: Float,
    windowW: Float,
    windowH: Float,
    drawnW: Float,
    drawnH: Float,
    maxZoom: Float = MAX_ZOOM,
): Viewport {
    val next = (scale * zoom).coerceIn(1f, maxZoom)
    val factor = next / scale
    val moved = Offset(
        centroid.x * (1 - factor) + factor * offset.x,
        centroid.y * (1 - factor) + factor * offset.y,
    ) + pan
    val slackX = maxOf(0f, (drawnW * next - windowW) / 2f)
    val slackY = maxOf(0f, (drawnH * next - windowH) / 2f)
    return Viewport(
        scale = next,
        offset = Offset(
            moved.x.coerceIn(-slackX, slackX),
            moved.y.coerceIn(-slackY, slackY),
        ),
    )
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

/**
 * The share of the scale over which the lowest step fades in from nothing.
 *
 * Narrow on purpose. It exists to stop the outermost ring of a blur drawing a
 * hard edge, and nothing else — at 0.18 it was also fading out the lanes
 * themselves: a cell crossed once lands at about t = 0.10 on a history whose
 * busiest cell has been crossed forty times, so a lane came out at little
 * more than half of an already faint step and was, in practice, invisible.
 */
private const val LOW_FADE = 0.06

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
 * Light surface: the blue ramp's steps 200 → 800, lightest first.
 *
 * The bottom of the ramp is where a heatmap is won or lost, because almost
 * everything on it is down there: the lane you crossed twice is most of the
 * picture by area, and the commute is a handful of cells. The first version put
 * the lowest step at 27 % alpha of a very pale blue, which on a near-white
 * surface is a colour you have to be told is there. It is now a step darker and
 * half again as opaque — still the quietest thing on the picture, and now
 * quietly visible, which is the difference between a scale and a decoration.
 *
 * The top of the ramp went darker by the same amount, so the range between a
 * lane and a commute is no smaller for the bottom having come up.
 */
private val LIGHT_RAMP = intArrayOf(
    argb(115, 0x9ec5f4),
    argb(170, 0x6da7ec),
    argb(215, 0x3987e5),
    argb(240, 0x1f5fb0),
    argb(255, 0x144684),
    argb(255, 0x0a2a55),
)

/**
 * Dark surface: the same hue, run the other way — dimmest at the bottom so low
 * density sinks into the ground, bright at the top.
 *
 * Lifted at the bottom for the reason the light ramp is, and further: a dark
 * blue at low alpha over a dark surface is nearly the surface, so the lowest
 * step is a mid blue rather than the darkest one on the scale.
 */
private val DARK_RAMP = intArrayOf(
    argb(120, 0x2f6fc4),
    argb(175, 0x3987e5),
    argb(215, 0x6da7ec),
    argb(240, 0x9ec5f4),
    argb(255, 0xcde2fb),
    argb(255, 0xeef4ff),
)
