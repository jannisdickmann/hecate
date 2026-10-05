package de.hecate.phone.ui

import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.heat.HeatmapGrid
import de.hecate.core.map.KarteIndex
import de.hecate.core.map.MapView
import de.hecate.core.model.Intensity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Every map in the app, drawn one way (US-8, Appendix A.29).
 *
 * There used to be three: a track drawing on the recording and route screens
 * that could not be touched at all, a heatmap card, and a full-screen heatmap
 * whose pinch was its own. Same ground, three projections of it, two of them
 * frozen. Now there is one content type ([MapContent]), one renderer
 * ([HecateMap]), one card ([MapCard]) and one full-screen view ([MapFullScreen])
 * — so a recording, a route and the heatmap look like the same kind of thing,
 * open the same way, and move the same way under the fingers.
 *
 * What did not change is what the maps *are*: minimal. A dim OpenStreetMap
 * ground with no tiles and no SDK under the user's own lines, nothing on top
 * that the user did not put there by running. §4 still holds where it was
 * about something.
 */

// ------------------------------------------------------------------ content

/**
 * One point of a drawn track: where it was, and how hard the user was working
 * when they were there. The heart rate travels with the position rather than
 * beside it, because everything that happens to a track downstream — thinning,
 * dropping paused samples, cutting at a dropout — has to happen to both or the
 * colours slide along the line.
 */
data class TrackPoint(val at: LatLon, val hrBpm: Double? = null)

/**
 * One line on a map, in local metres from [MapContent.origin] (x east, y north,
 * interleaved), coloured run by run.
 */
@Immutable
class MapLine(val local: DoubleArray, val runs: List<MapRun>, val widthDp: Float)

/** Points [from]..[to] of a [MapLine], drawn in one colour. */
@Immutable
data class MapRun(val from: Int, val to: Int, val color: Color)

/** A dot on the map that stays the same size however far it is zoomed in. */
@Immutable
data class MapMarker(val east: Double, val north: Double, val color: Color)

/** A density raster over the grid it was computed on. */
@Immutable
class MapHeat(val grid: HeatmapGrid, val raster: ImageBitmap?)

/** Which of [BasemapColors]' two palettes the ground is drawn in. */
enum class MapGround { TRACK, HEAT }

/**
 * What a map shows, independent of how big it is drawn or where it is looking.
 *
 * [minX]..[maxY] is the extent it is fitted to, in local metres: the track's
 * bounding box, or the heatmap grid's own extent. [padDp] is the breathing
 * room around it when fitted — a track should not touch the frame, a raster
 * that fills its extent edge to edge has none to give.
 */
@Immutable
class MapContent(
    val origin: LatLon,
    val minX: Double,
    val minY: Double,
    val maxX: Double,
    val maxY: Double,
    val lines: List<MapLine>,
    val markers: List<MapMarker> = emptyList(),
    val heat: MapHeat? = null,
    val ground: MapGround = MapGround.TRACK,
    val padDp: Float = 16f,
) {
    /** How far in it may be zoomed: until about [MIN_SPAN_M] of it spans
     *  the screen, and at least four times — further than that a track is two
     *  dots and a heatmap is its own cells. */
    val maxScale: Float
        get() = (max(maxX - minX, maxY - minY) / MIN_SPAN_M).toFloat().coerceIn(4f, 64f)
}

/** The narrowest stretch of ground a map can be zoomed in to [m]. */
private const val MIN_SPAN_M = 150.0

/**
 * A recording or a route as a map: the line coloured by effort where there is
 * a heart rate to colour it by (A.21), start and end marked.
 *
 * Each segment takes the band its own reading falls in, so the shape of the
 * ride and the shape of the effort are one picture; consecutive segments of
 * one band are one run, because heart rate does not flicker between zones from
 * second to second and a two-hour ride is otherwise seven thousand paths.
 * Where there is no reading the line falls back to the scheme's line colour —
 * or, on a line that does have readings elsewhere, to a quiet grey, so that
 * the holes do not read as emphasis.
 *
 * Null for fewer than two points, which is not a line.
 */
@Composable
fun rememberTrackMap(points: List<TrackPoint>, peakHrBpm: Int): MapContent? {
    val measured = points.any { it.hrBpm != null }
    val plain = if (measured) HecateIntensityColors.Unmeasured else routeLineColor
    val bands = Intensity.entries.associateWith { HecateIntensityColors.of(it) }
    return remember(points, peakHrBpm, plain, bands) {
        if (points.size < 2) return@remember null
        val origin = points.first().at
        val local = DoubleArray(points.size * 2)
        var minX = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE
        var minY = Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for ((i, p) in points.withIndex()) {
            val l = Geo.toLocal(origin, p.at)
            local[2 * i] = l[0]; local[2 * i + 1] = l[1]
            minX = min(minX, l[0]); maxX = max(maxX, l[0])
            minY = min(minY, l[1]); maxY = max(maxY, l[1])
        }
        val runs = ArrayList<MapRun>()
        var start = 0
        var band = bandOf(points, 1, peakHrBpm)
        for (i in 1 until points.size) {
            val b = bandOf(points, i, peakHrBpm)
            if (b != band) {
                runs.add(MapRun(start, i - 1, band?.let { bands[it] } ?: plain))
                start = i - 1
                band = b
            }
        }
        runs.add(MapRun(start, points.size - 1, band?.let { bands[it] } ?: plain))
        MapContent(
            origin = origin,
            minX = minX, minY = minY,
            // A track that is a single spot still gets a metre of extent, so
            // the fit has something to divide by.
            maxX = max(maxX, minX + 1.0), maxY = max(maxY, minY + 1.0),
            lines = listOf(MapLine(local, runs, TRACK_WIDTH_DP)),
            markers = listOf(
                MapMarker(local[0], local[1], HecateMapColors.Start),
                MapMarker(local[local.size - 2], local[local.size - 1], HecateMapColors.End),
            ),
        )
    }
}

/**
 * The band the segment ending at [index] belongs to: the mean of its two ends,
 * so a segment is coloured by what was happening along it.
 */
private fun bandOf(points: List<TrackPoint>, index: Int, peakHrBpm: Int): Intensity? {
    val a = points[index - 1].hrBpm
    val b = points[index].hrBpm
    val hr = if (a != null && b != null) (a + b) / 2.0 else a ?: b
    return Intensity.of(hr, peakHrBpm)
}

/**
 * Every recording at once as a density raster, the routes drawn over it in a
 * warm thin line (US-8). Warm, because the heat underneath is a one-hue blue
 * ramp and an annotation in any blue would read as a value on it; thin and
 * uncased, because a route sits exactly on its own heat and a casing would
 * erase the density it is drawn on.
 */
@Composable
fun rememberHeatMap(state: HeatmapState, raster: ImageBitmap?, dark: Boolean = isSystemInDarkTheme()): MapContent {
    val routeColor = if (dark) HecateMapColors.RouteOnHeatDark else HecateMapColors.RouteOnHeatLight
    return remember(state, raster, routeColor) {
        val grid = state.grid
        val origin = LatLon(grid.originLat, grid.originLon)
        val lines = state.routes.filter { it.size >= 2 }.map { pts ->
            val local = DoubleArray(pts.size * 2)
            for ((i, p) in pts.withIndex()) {
                val l = Geo.toLocal(origin, p)
                local[2 * i] = l[0]; local[2 * i + 1] = l[1]
            }
            MapLine(local, listOf(MapRun(0, pts.size - 1, routeColor)), HEAT_ROUTE_WIDTH_DP)
        }
        MapContent(
            origin = origin,
            minX = grid.minX,
            minY = grid.maxY - grid.spanY,
            maxX = grid.minX + grid.spanX,
            maxY = grid.maxY,
            lines = lines,
            heat = MapHeat(grid, raster),
            ground = MapGround.HEAT,
            padDp = 0f,
        )
    }
}

private const val TRACK_WIDTH_DP = 2.2f
private const val HEAT_ROUTE_WIDTH_DP = 1.2f

// ----------------------------------------------------------------- renderer

/**
 * Draw [content], fitted to the space it is given, as [camera] looks at it.
 *
 * The content is fitted to the canvas once — one scale on both axes, centred,
 * padded — and the camera magnifies and moves that fitted picture. What is left
 * over around the content when its shape is not the canvas's is ground, not
 * nothing: the basemap covers the whole canvas, so a route that is tall and
 * thin sits in its streets rather than in grey bars.
 *
 * Lines are projected into the fitted picture once and then drawn through the
 * camera's transform with their widths divided by its scale, so they stay as
 * thin as they are at every zoom — a vector map should get sharper, not fatter.
 * Markers are drawn outside the transform for the same reason.
 */
@Composable
fun HecateMap(
    content: MapContent,
    modifier: Modifier = Modifier,
    camera: MapCamera = MapCamera(),
    dark: Boolean = isSystemInDarkTheme(),
    /** The frame the content is fitted into, inside the canvas. Null fits it to
     *  the whole canvas with the content's own [MapContent.padDp]; the full
     *  screen passes one that keeps the start and end clear of its title bar,
     *  its caption and the system bars, which all sit over the map. */
    frame: PaddingValues? = null,
) {
    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val basemapColors = remember(dark, content.ground) {
        if (content.ground == MapGround.HEAT) BasemapColors.forHeat(dark) else BasemapColors.forTrack(dark)
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val markerOutline = MaterialTheme.colorScheme.surface

    BoxWithConstraints(modifier.clipToBounds()) {
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        if (widthPx <= 0f || heightPx <= 0f) return@BoxWithConstraints
        val layoutDirection = LocalLayoutDirection.current
        val inset = with(density) {
            frame?.let {
                floatArrayOf(
                    it.calculateLeftPadding(layoutDirection).toPx(),
                    it.calculateTopPadding().toPx(),
                    it.calculateRightPadding(layoutDirection).toPx(),
                    it.calculateBottomPadding().toPx(),
                )
            } ?: FloatArray(4) { content.padDp.dp.toPx() }
        }
        val insetKey = inset.toList()

        val view = remember(content, widthPx, heightPx, insetKey) {
            // Fitted into the frame, then moved to where the frame is: the fit
            // itself is symmetric, and the frame need not be.
            val innerW = (widthPx - inset[0] - inset[2]).coerceAtLeast(1f)
            val innerH = (heightPx - inset[1] - inset[3]).coerceAtLeast(1f)
            val fitted = MapView.fit(
                origin = content.origin,
                minX = content.minX,
                minY = content.minY,
                spanX = content.maxX - content.minX,
                spanY = content.maxY - content.minY,
                widthPx = innerW.toDouble(),
                heightPx = innerH.toDouble(),
                padPx = 0.0,
            )
            fitted.copy(xPx = fitted.xPx + inset[0], yPx = fitted.yPx + inset[1])
        }
        val paths = remember(content, view) {
            content.lines.map { line ->
                line.runs.map { run ->
                    Path().apply {
                        for (i in run.from..run.to) {
                            val x = view.canvasX(line.local[2 * i]).toFloat()
                            val y = view.canvasY(line.local[2 * i + 1]).toFloat()
                            if (i == run.from) moveTo(x, y) else lineTo(x, y)
                        }
                    } to run.color
                }
            }
        }

        // The ground for a window a little larger than the screen, snapped to a
        // coarse grid, rather than for the exact screen: the exact one changes on
        // every frame of a pan, and every change is a new scene to build.
        val window = remember(camera, widthPx, heightPx) { stableWindow(camera, widthPx, heightPx) }
        val scene = rememberBasemapScene(view, window, camera.scale)
        val placed = rememberPlacedLabels(scene, window, camera.scale)

        Canvas(Modifier.fillMaxSize()) {
            drawRect(surface)
            withTransform({
                translate(camera.tx, camera.ty)
                scale(camera.scale, camera.scale, pivot = Offset.Zero)
            }) {
                // Under everything, and under the raster too: the raster is
                // transparent where nobody has been, so the ground shows through
                // there and is progressively buried where you have.
                scene?.let { drawBasemap(it, placed, basemapColors, measurer, camera.scale) }
                content.heat?.raster?.let { raster ->
                    val g = content.heat.grid
                    val left = view.canvasX(g.minX).toFloat()
                    val top = view.canvasY(g.maxY).toFloat()
                    val right = view.canvasX(g.minX + g.spanX).toFloat()
                    val bottom = view.canvasY(g.maxY - g.spanY).toFloat()
                    drawImage(
                        image = raster,
                        dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                        dstSize = IntSize((right - left).roundToInt(), (bottom - top).roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
                for ((line, runs) in content.lines.zip(paths)) {
                    val stroke = Stroke(
                        width = line.widthDp.dp.toPx() / camera.scale,
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                    )
                    for ((path, color) in runs) drawPath(path, color, style = stroke)
                }
            }
            val r = MARKER_RADIUS_DP.dp.toPx()
            for (m in content.markers) {
                val at = camera.toScreen(Offset(view.canvasX(m.east).toFloat(), view.canvasY(m.north).toFloat()))
                drawCircle(markerOutline, radius = r + 1.5.dp.toPx(), center = at)
                drawCircle(m.color, radius = r, center = at)
            }
        }
    }
}

private const val MARKER_RADIUS_DP = 4f

/**
 * The part of the fitted picture to fetch ground for: the visible part, padded
 * by half a screen and snapped to half-screen steps, so that a pan rebuilds the
 * scene only when it has crossed one. The step follows the zoom in quarter
 * octaves rather than continuously, for the same reason.
 */
internal fun stableWindow(camera: MapCamera, width: Float, height: Float): Rect {
    if (camera.scale <= 1f) return Rect(0f, 0f, width, height)
    val q = 2f.pow((log2(camera.scale) * 4f).roundToInt() / 4f)
    val stepX = width / q / 2f
    val stepY = height / q / 2f
    val v = camera.visible(width, height)
    return Rect(
        left = (floor(v.left / stepX) * stepX - stepX).coerceAtLeast(0f),
        top = (floor(v.top / stepY) * stepY - stepY).coerceAtLeast(0f),
        right = (ceil(v.right / stepX) * stepX + stepX).coerceAtMost(width),
        bottom = (ceil(v.bottom / stepY) * stepY + stepY).coerceAtMost(height),
    )
}

// --------------------------------------------------------------------- card

/**
 * A map as it sits in a screen: one shape for every map in the app, a quiet
 * corner glyph that says it opens, and an optional caption underneath.
 *
 * Not interactive in place, deliberately. These cards live in scrolling
 * columns, and a map that also took one-finger drags would fight the scroll for
 * every swipe that started on it. A tap opens [MapFullScreen], where a finger
 * on the map means the map.
 */
@Composable
fun MapCard(
    content: MapContent?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    caption: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Card(
        onClick = onOpen,
        enabled = content != null,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(MAP_CARD_ASPECT)) {
            if (content != null) HecateMap(content, Modifier.fillMaxSize())
            ExpandGlyph(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), MaterialTheme.shapes.small)
                    .padding(5.dp)
                    .size(14.dp),
            )
        }
        if (caption != null) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), content = caption)
        }
    }
}

/** Every map card's shape: wide enough to read a commute, short enough that the
 *  charts under it are still on the first screen. */
private const val MAP_CARD_ASPECT = 1.6f

/** Two corners pointing outwards: "this opens bigger", drawn rather than taken
 *  from the extended icon set this app does not ship (see libs.versions.toml). */
@Composable
private fun ExpandGlyph(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier) {
        val w = size.width
        val arm = w * 0.38f
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val p = Path().apply {
            moveTo(w - arm, 0f); lineTo(w, 0f); lineTo(w, arm)
            moveTo(arm, w); lineTo(0f, w); lineTo(0f, w - arm)
            moveTo(w, 0f); lineTo(w * 0.6f, w * 0.4f)
            moveTo(0f, w); lineTo(w * 0.4f, w * 0.6f)
        }
        drawPath(p, color, style = stroke)
    }
}

// -------------------------------------------------------------- full screen

/**
 * A map, full screen, to be moved around.
 *
 * Pinch zooms about the fingers and pans with them in the same gesture; one
 * finger pans; a flick keeps going and slows down, as a map on a phone always
 * has; a double tap zooms in on the tapped spot, and a double tap when zoomed
 * in goes back to the whole picture. The picture never leaves the screen and
 * the ground fills in at the detail the zoom calls for.
 *
 * [content] is null while it is being prepared — the heatmap's full raster is
 * read off the database when this opens — and a spinner is the honest thing to
 * show in that window.
 *
 * A `Dialog`, edge to edge behind both system bars with their icons set to
 * match the surface: the picture is not a screen in the app's back stack beside
 * the panels and the details, and the alternative would put it there.
 */
@Composable
fun MapFullScreen(
    content: MapContent?,
    title: String,
    caption: String?,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val dark = isSystemInDarkTheme()
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window
            if (window != null) {
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            var camera by remember(content) { mutableStateOf(MapCamera()) }
            Box(Modifier.fillMaxSize()) {
                if (content == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    val scope = rememberCoroutineScope()
                    // Drawn under the system bars too: the ground runs to every
                    // edge, and only the controls keep clear of them.
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        val w = with(LocalDensity.current) { maxWidth.toPx() }
                        val h = with(LocalDensity.current) { maxHeight.toPx() }
                        val bars = WindowInsets.systemBars.asPaddingValues()
                        HecateMap(
                            content,
                            Modifier.fillMaxSize().mapGestures(scope, content.maxScale, w, h, { camera }) { camera = it },
                            camera = camera,
                            // Clear of the title bar at the top and the caption
                            // at the bottom, and with room to spare at the sides,
                            // so the start and the end are never under anything.
                            frame = PaddingValues(
                                start = FULL_SCREEN_SIDE_DP.dp,
                                end = FULL_SCREEN_SIDE_DP.dp,
                                top = bars.calculateTopPadding() + FULL_SCREEN_TOP_DP.dp,
                                bottom = bars.calculateBottomPadding() + FULL_SCREEN_BOTTOM_DP.dp,
                            ),
                        )
                    }
                }

                Row(
                    Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 8.dp)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), MaterialTheme.shapes.small)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                            MaterialTheme.shapes.small,
                        ),
                    ) { Icon(Icons.Filled.Close, contentDescription = "Schließen") }
                }

                Row(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.systemBars)
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Column(Modifier.weight(1f)) {
                        caption?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Start,
                            )
                        }
                        // ODbL: the source named wherever the data is presented at
                        // size. The cards are too small to owe it; this is not.
                        Text(
                            KarteIndex.ATTRIBUTION,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    // Back to the whole picture; only there once there is
                    // somewhere to come back from.
                    if (!camera.isIdentity) {
                        TextButton(
                            onClick = { camera = MapCamera() },
                            modifier = Modifier.background(
                                MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                                MaterialTheme.shapes.small,
                            ),
                        ) { Text("Alles zeigen") }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- gestures

/**
 * The gestures of [MapFullScreen], on top of [MapCamera]'s arithmetic.
 *
 * One loop for pinch and pan rather than `detectTransformGestures`, because a
 * map needs the one thing that detector does not give: how fast the finger was
 * going when it let go. A flick then carries on and decays, stopped by the
 * picture's edge like everything else; a new touch stops it dead.
 */
private fun Modifier.mapGestures(
    scope: CoroutineScope,
    maxScale: Float,
    width: Float,
    height: Float,
    camera: () -> MapCamera,
    onCamera: (MapCamera) -> Unit,
): Modifier = this
    .pointerInput(maxScale, width, height) {
        var settle: Job? = null
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            settle?.cancel()
            val tracker = VelocityTracker()
            val slop = viewConfiguration.touchSlop
            var moving = false
            var travelled = 0f
            var fingers = 1
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) break
                fingers = max(fingers, pressed.size)
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                val centroid = event.calculateCentroid(useCurrent = true)
                if (!moving) {
                    travelled += hypot(pan.x, pan.y)
                    moving = travelled > slop || abs(zoom - 1f) > 0.02f
                }
                if (moving && centroid != Offset.Unspecified) {
                    onCamera(camera().pinched(centroid, pan, zoom, width, height, maxScale))
                    tracker.addPosition(event.changes.first().uptimeMillis, centroid)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            } while (true)

            // A flick: only after a one-finger pan, never at the end of a pinch,
            // where the fingers leaving one at a time would read as a throw.
            if (moving && fingers == 1) {
                val v = tracker.calculateVelocity()
                val speed = hypot(v.x, v.y)
                if (speed > FLING_MIN_PX_S) {
                    settle = scope.launch {
                        var last = Offset.Zero
                        AnimationState(
                            typeConverter = Offset.VectorConverter,
                            initialValue = Offset.Zero,
                            initialVelocity = Offset(v.x, v.y),
                        ).animateDecay(exponentialDecay(frictionMultiplier = 1.6f)) {
                            val step = value - last
                            last = value
                            val before = camera()
                            val after = before.panned(step, width, height)
                            onCamera(after)
                            // Ran into the edge on both axes: nothing left to move.
                            if (after == before) cancelAnimation()
                        }
                    }
                }
            }
        }
    }
    .pointerInput(maxScale, width, height) {
        detectTapGestures(
            onDoubleTap = { at ->
                val from = camera()
                val target = if (from.scale < DOUBLE_TAP_ZOOM * 0.9f) {
                    from.zoomedTo((from.scale * DOUBLE_TAP_ZOOM).coerceAtMost(maxScale), at, width, height, maxScale)
                } else {
                    MapCamera()
                }
                scope.launch {
                    animate(0f, 1f, animationSpec = spring(stiffness = 400f)) { f, _ ->
                        onCamera(
                            MapCamera(
                                scale = from.scale + (target.scale - from.scale) * f,
                                tx = from.tx + (target.tx - from.tx) * f,
                                ty = from.ty + (target.ty - from.ty) * f,
                            ),
                        )
                    }
                }
            },
        )
    }

/** The full screen's frame around the content [dp]: the sides, and beyond the
 *  system bars the room its title bar and its caption take. */
private const val FULL_SCREEN_SIDE_DP = 40f
private const val FULL_SCREEN_TOP_DP = 88f
private const val FULL_SCREEN_BOTTOM_DP = 88f

/** Below this a release is a lift, not a flick [px/s]. */
private const val FLING_MIN_PX_S = 300f

/** How much one double tap magnifies. */
private const val DOUBLE_TAP_ZOOM = 2.5f
