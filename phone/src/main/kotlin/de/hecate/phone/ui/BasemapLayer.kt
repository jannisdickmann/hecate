package de.hecate.phone.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.hecate.core.Tuning
import de.hecate.core.geo.LatLon
import de.hecate.core.map.LabelBox
import de.hecate.core.map.LabelCandidate
import de.hecate.core.map.LabelPlacer
import de.hecate.core.map.MapClass
import de.hecate.core.map.MapFeature
import de.hecate.core.map.MapPlace
import de.hecate.core.map.MapScene
import de.hecate.core.map.MapView
import de.hecate.core.map.PlaceClass
import de.hecate.core.map.PlacedLabel
import de.hecate.phone.AppGraph
import de.hecate.phone.map.BasemapRepository
import kotlin.math.log2
import kotlin.math.roundToInt

/**
 * The basemap, projected and ready to draw.
 *
 * **One `Path` per class, not per feature.** A twelve-kilometre street view is a
 * few thousand ways; that is eight `drawPath` calls per frame instead of a few
 * thousand. The paths are built off the main thread — a `Path` here is a
 * display-list recording rather than a GPU resource, so that is safe — exactly
 * as `rememberHeatmapRaster` already builds its bitmap.
 */
class BasemapScene(
    val fills: List<Pair<MapClass, Path>>,
    val lines: List<Pair<MapClass, Path>>,
    val labels: List<LabelCandidate>,
) {
    val isEmpty: Boolean get() = fills.isEmpty() && lines.isEmpty() && labels.isEmpty()
}

/**
 * The scene for one viewport, or null while it is still being built.
 *
 * Null draws the track on empty ground for a frame or two, which is the same
 * honest behaviour the heatmap raster has and the same picture this app drew
 * before it had a map at all.
 *
 * [visible] is the canvas rectangle actually on screen, which is the whole
 * canvas everywhere except the pinched full-screen heatmap. [zoom] is what that
 * view's `graphicsLayer` is scaling by, and it belongs here rather than in the
 * drawing because it changes *which* map is wanted: a map drawn at eight times
 * magnification should be a denser map, not a bigger one.
 */
@Composable
fun rememberBasemapScene(
    view: MapView,
    visible: Rect,
    zoom: Float = 1f,
    repository: BasemapRepository? = runCatching { AppGraph.basemap }.getOrNull(),
): BasemapScene? {
    if (repository == null) return null
    val density = LocalDensity.current.density
    // Level of detail in **dp** per metre, never pixels: a 3x screen must not
    // show a denser map than a 1x one at the same apparent size.
    val dpPerM = view.pxPerM * zoom / density
    // Quantised, so a pinch does not rebuild the scene sixty times a second.
    // Six steps over 1..8, which is one rebuild per halving of the ground.
    val zoomKey = remember(zoom) { (log2(zoom.coerceAtLeast(0.01f).toDouble()) * 2).roundToInt() }
    val generation by repository.generation.collectAsState()

    val state = produceState<BasemapScene?>(
        initialValue = null,
        view, visible, zoomKey, generation,
    ) {
        val bounds = view.visibleBounds(
            visible.left.toDouble(), visible.top.toDouble(),
            visible.right.toDouble(), visible.bottom.toDouble(),
        )
        val scene = repository.scene(bounds, dpPerM)
        value = project(scene, view)
        // Asked for after the scene is on screen, not before: the map that is
        // already here is worth more than the one that might arrive.
        repository.request(bounds, dpPerM)
    }
    return state.value
}

/** Lat/lon into canvas pixels, one path per class. */
private fun project(scene: MapScene, view: MapView): BasemapScene {
    val fills = LinkedHashMap<MapClass, Path>()
    val lines = LinkedHashMap<MapClass, Path>()

    fun add(into: MutableMap<MapClass, Path>, f: MapFeature) {
        val path = into.getOrPut(f.cls) { Path() }
        for (i in 0 until f.size) {
            val c = view.canvas(LatLon(f.lat[i], f.lon[i]))
            if (i == 0) path.moveTo(c[0].toFloat(), c[1].toFloat())
            else path.lineTo(c[0].toFloat(), c[1].toFloat())
        }
        if (f.closedRing) path.close()
    }

    for (f in scene.urbanPatches) add(fills, f)
    for (f in scene.features) {
        if (f.closedRing && f.cls.closed) add(fills, f) else add(lines, f)
    }

    val labels = scene.places.map { p ->
        val c = view.canvas(p.at)
        LabelCandidate(p, c[0], c[1])
    }
    // Back to front, so a motorway is never interrupted by the footpath that
    // crosses it.
    return BasemapScene(
        fills = fills.entries.sortedBy { it.key.drawOrder }.map { it.key to it.value },
        lines = lines.entries.sortedBy { it.key.drawOrder }.map { it.key to it.value },
        labels = labels,
    )
}

/**
 * Which names fit, measured for real.
 *
 * Recomputed per zoom step, and not because the anchors move: a label is a
 * constant size on the screen, so as the map is magnified it covers less ground
 * and more of them fit.
 */
@Composable
fun rememberPlacedLabels(
    scene: BasemapScene?,
    visible: Rect,
    zoom: Float = 1f,
): List<PlacedLabel> {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val zoomKey = (log2(zoom.coerceAtLeast(0.01f).toDouble()) * 2).roundToInt()
    return remember(scene, visible, zoomKey, density) {
        if (scene == null || scene.labels.isEmpty()) return@remember emptyList()
        val areaDp2 = with(density) {
            (visible.width / zoom).toDp().value.toDouble() * (visible.height / zoom).toDp().value.toDouble()
        }
        val marginPx = with(density) { Tuning.MAP_LABEL_MARGIN_DP.toFloat().dp.toPx() }.toDouble()
        LabelPlacer.place(
            candidates = scene.labels,
            measure = { place ->
                val size = measurer.measure(place.name, styleFor(place.cls)).size
                // In the pinched layer's own pixels: a label is a constant size
                // on the *screen*, so inside a layer magnified by `zoom` it
                // occupies 1/zoom of what it would at rest — which is why more
                // of them fit the further in you go.
                doubleArrayOf(size.width.toDouble() / zoom, size.height.toDouble() / zoom)
            },
            visible = LabelBox(
                visible.left.toDouble(), visible.top.toDouble(),
                visible.right.toDouble(), visible.bottom.toDouble(),
            ),
            maxLabels = LabelPlacer.maxLabelsFor(areaDp2),
            marginPx = marginPx / zoom,
            gapPx = LabelPlacer.DOT_GAP_PX / zoom,
        )
    }
}

private fun styleFor(cls: PlaceClass) = TextStyle(
    // In sp, so the names follow the user's font scale — and not the map's
    // zoom, which is what makes them legible at every magnification.
    fontSize = when (cls) {
        PlaceClass.CITY -> 13.sp
        PlaceClass.TOWN -> 12.sp
        else -> 11.sp
    },
)

/**
 * Draw it.
 *
 * [zoom] divides every stroke, because the whole layer is inside a
 * `graphicsLayer` that scales: without this a 1 dp road becomes an 8 dp blob at
 * full magnification, which is the one thing a *vector* map should never do.
 */
fun DrawScope.drawBasemap(
    scene: BasemapScene,
    placed: List<PlacedLabel>,
    colors: BasemapColors,
    measurer: TextMeasurer,
    zoom: Float = 1f,
) {
    for ((cls, path) in scene.fills) {
        drawPath(path, colors.of(cls))
    }
    for ((cls, path) in scene.lines) {
        val width = (colors.widthDpOf(cls) * density / zoom).coerceAtLeast(0.4f)
        drawPath(
            path,
            color = colors.of(cls),
            style = Stroke(
                width = width,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                // A railway is the one thing on the map that is not a road, and
                // dashes are how every map in the world says so.
                pathEffect = if (cls == MapClass.RAIL) {
                    PathEffect.dashPathEffect(floatArrayOf(6f / zoom, 5f / zoom))
                } else {
                    null
                },
            ),
        )
    }

    val haloPx = 1.dp.toPx()
    for (label in placed) {
        val anchor = Offset(label.x.toFloat(), label.y.toFloat())
        drawCircle(colors.dot, radius = 1.dp.toPx() / zoom, center = anchor)
        // Rasterised once at final size rather than magnified: an 11 sp village
        // name must not become a headline at eight times zoom.
        withTransform({ scale(1f / zoom, 1f / zoom, pivot = anchor) }) {
            val layout = measurer.measure(label.place.name, styleFor(label.place.cls))
            val topLeft = Offset(
                anchor.x - layout.size.width / 2f,
                anchor.y + (LabelPlacer.DOT_GAP_PX / zoom).toFloat(),
            )
            // Three passes is what makes a name legible over a street grid: a
            // dot at the place, a halo, then the name.
            //
            // The halo is `drawText`'s own `drawStyle` rather than a second
            // layout measured with the stroke baked into its `TextStyle`. That
            // way round is a trap: the stroke widens the glyphs themselves, so
            // what came out was not a haloed name but a fat one with its
            // counters filled in — and a thousandth of a millimetre of it was
            // visible on the emulator as an unreadable grey blob. One layout,
            // two passes, and the stroke stays outside the letters.
            drawText(
                layout,
                color = colors.labelHalo,
                topLeft = topLeft,
                drawStyle = Stroke(width = haloPx, join = StrokeJoin.Round, cap = StrokeCap.Round),
            )
            // Fill said explicitly: the halo's stroke stays on the layout's
            // paint, and a pass that does not override it strokes the name
            // too — which is what made every label look bold and smudged.
            drawText(
                layout,
                color = colors.label,
                topLeft = topLeft,
                drawStyle = androidx.compose.ui.graphics.drawscope.Fill,
            )
        }
    }
}
