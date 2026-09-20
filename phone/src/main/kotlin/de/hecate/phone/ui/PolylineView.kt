package de.hecate.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.hecate.core.Tuning
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.map.MapView
import de.hecate.core.model.Intensity
import kotlin.math.max
import kotlin.math.min

/**
 * One point of a drawn track: where it was, and how hard the user was working
 * when they were there.
 *
 * The heart rate travels with the position rather than beside it in a second
 * list, because everything that happens to a track downstream — thinning it,
 * dropping paused samples, cutting it at a dropout — has to happen to both or
 * the colours slide along the line.
 */
data class TrackPoint(val at: LatLon, val hrBpm: Double? = null)

/**
 * A route on a Compose `Canvas`, over a dim drawing of the streets it was run
 * on (US-8, Appendix A.29).
 *
 * §4 still holds where it was actually about something: there is no maps SDK
 * here, no API key, no billing and no tile library. What changed is that the
 * ground is no longer empty — a route not run in a year used to be a shape
 * without a place. The basemap is OpenStreetMap data fetched once, simplified
 * and stored locally; where it has not been fetched, this draws exactly the
 * picture it always did, on nothing.
 *
 * Latitude and longitude are projected locally to metres before scaling so the
 * drawing has the right aspect ratio rather than being stretched by the cosine
 * of the latitude. That projection is [MapView] now rather than arithmetic
 * inlined here, which is what stops the map and the track drifting apart: they
 * read one object instead of two copies of a formula.
 *
 * **The line is coloured by effort** where there is a heart rate to colour it by
 * (A.21): each segment takes the band its own reading falls in, so the shape of
 * the ride and the shape of the effort are one picture rather than two. Where
 * there is no reading — an import without heart rate, a watch worn loose — the
 * segment falls back to the scheme's line colour, which is what the whole line
 * used to be.
 *
 * Neither the markers nor the bands follow the theme. Which end of a track is
 * the start, and which colour means a maximal effort, are facts about the
 * recording, and a wallpaper must not be able to recolour them into something
 * else — see [HecateMapColors], [HecateIntensityColors] and [BasemapColors].
 */
@Composable
fun PolylineView(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    peakHrBpm: Int = Tuning.DEFAULT_PEAK_HR_BPM,
    startColor: Color = HecateMapColors.Start,
    endColor: Color = HecateMapColors.End,
    dark: Boolean = isSystemInDarkTheme(),
) {
    // As on the charts: a track with no reading anywhere is simply a track, and
    // one with readings has holes that must not read as emphasis.
    val measured = points.any { it.hrBpm != null }
    val plain = if (measured) HecateIntensityColors.Unmeasured else routeLineColor
    val bandColors = Intensity.entries.associateWith { HecateIntensityColors.of(it) }
    val basemapColors = remember(dark) { BasemapColors.forTrack(dark) }
    val measurer = rememberTextMeasurer()

    // `clipToBounds` is not optional. A `Canvas` does not clip, and the track
    // can never escape because it is fitted with padding — but the basemap
    // covers the whole visible bounds, and a road at the edge would otherwise
    // paint over the elevation chart below.
    BoxWithConstraints(modifier.fillMaxWidth().height(height).clipToBounds()) {
        if (points.size < 2) return@BoxWithConstraints
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }

        // Out of the `Canvas` lambda and into a `remember`: this used to run on
        // every recomposition, which for a two-hour ride is seven thousand
        // projections per frame — and the basemap needs the fit *before* the
        // draw, so that its scene can start being built.
        val view = remember(points, widthPx, heightPx) { fitTo(points, widthPx, heightPx) }
        val local = remember(points) {
            val origin = points.first().at
            points.map { Geo.toLocal(origin, it.at) }
        }
        val visible = remember(widthPx, heightPx) { Rect(0f, 0f, widthPx, heightPx) }
        val scene = rememberBasemapScene(view, visible)
        val placed = rememberPlacedLabels(scene, visible)

        Canvas(Modifier.fillMaxSize()) {
            scene?.let { drawBasemap(it, placed, basemapColors, measurer) }

            fun toCanvas(p: DoubleArray) =
                Offset(view.canvasX(p[0]).toFloat(), view.canvasY(p[1]).toFloat())

            // Consecutive segments of the same band are drawn as one path rather
            // than one path each: a two-hour ride is seven thousand segments and
            // four colours, and the run lengths are long because heart rate does
            // not flicker between zones from second to second.
            var runStart = 0
            var runBand = bandOf(points, 1, peakHrBpm)
            for (i in 1 until points.size) {
                val band = bandOf(points, i, peakHrBpm)
                if (band != runBand) {
                    drawRun(local, runStart, i - 1, ::toCanvas, runBand?.let { bandColors[it] } ?: plain)
                    runStart = i - 1
                    runBand = band
                }
            }
            drawRun(local, runStart, points.size - 1, ::toCanvas, runBand?.let { bandColors[it] } ?: plain)

            drawCircle(startColor, radius = 6f, center = toCanvas(local.first()))
            drawCircle(endColor, radius = 6f, center = toCanvas(local.last()))
        }
    }
}

/**
 * The fit this view has always made: one scale on both axes, centred, padded by
 * [PAD_PX].
 *
 * The arithmetic itself lives in [MapView.fit] and is unchanged to the last
 * float; `MapViewTest` holds the golden case that says so.
 */
private fun fitTo(points: List<TrackPoint>, widthPx: Float, heightPx: Float): MapView {
    val origin = points.first().at
    var minX = Double.MAX_VALUE
    var maxX = -Double.MAX_VALUE
    var minY = Double.MAX_VALUE
    var maxY = -Double.MAX_VALUE
    for (p in points) {
        val l = Geo.toLocal(origin, p.at)
        minX = min(minX, l[0]); maxX = max(maxX, l[0])
        minY = min(minY, l[1]); maxY = max(maxY, l[1])
    }
    return MapView.fit(
        origin = origin,
        minX = minX,
        minY = minY,
        spanX = (maxX - minX).coerceAtLeast(1.0),
        spanY = (maxY - minY).coerceAtLeast(1.0),
        widthPx = widthPx.toDouble(),
        heightPx = heightPx.toDouble(),
        padPx = PAD_PX.toDouble(),
    )
}

/** Breathing room, so the outermost point of the track is not the frame. */
private const val PAD_PX = 16f

/**
 * The band the segment ending at [index] belongs to.
 *
 * The mean of the two ends rather than either one of them, so that a segment is
 * coloured by what was happening along it. Where only one end has a reading that
 * one stands for both; where neither has, the segment has no band and is drawn
 * plain.
 */
private fun bandOf(points: List<TrackPoint>, index: Int, peakHrBpm: Int): Intensity? {
    val a = points[index - 1].hrBpm
    val b = points[index].hrBpm
    val hr = when {
        a != null && b != null -> (a + b) / 2.0
        else -> a ?: b
    }
    return Intensity.of(hr, peakHrBpm)
}

/** One run of same-coloured segments, from point [from] to point [to]. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawRun(
    local: List<DoubleArray>,
    from: Int,
    to: Int,
    toCanvas: (DoubleArray) -> Offset,
    color: Color,
) {
    if (to <= from) return
    val path = Path().apply {
        val first = toCanvas(local[from])
        moveTo(first.x, first.y)
        for (i in from + 1..to) {
            val c = toCanvas(local[i])
            lineTo(c.x, c.y)
        }
    }
    drawPath(
        path,
        color = color,
        style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}
