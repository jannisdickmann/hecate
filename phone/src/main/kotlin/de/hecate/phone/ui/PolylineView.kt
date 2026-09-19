package de.hecate.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.hecate.core.Tuning
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
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
 * §4: no maps SDK. A plain polyline on a Compose `Canvas` — no API key, no
 * billing, no tile cache, no library — which is all US-8 asks for: "a plain
 * polyline drawing of the route geometry".
 *
 * Latitude and longitude are projected locally to metres before scaling so the
 * drawing has the right aspect ratio rather than being stretched by the cosine
 * of the latitude.
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
 * else — see [HecateMapColors] and [HecateIntensityColors].
 */
@Composable
fun PolylineView(
    points: List<TrackPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    peakHrBpm: Int = Tuning.DEFAULT_PEAK_HR_BPM,
    startColor: Color = HecateMapColors.Start,
    endColor: Color = HecateMapColors.End,
) {
    // As on the charts: a track with no reading anywhere is simply a track, and
    // one with readings has holes that must not read as emphasis.
    val measured = points.any { it.hrBpm != null }
    val plain = if (measured) HecateIntensityColors.Unmeasured else routeLineColor
    val bandColors = Intensity.entries.associateWith { HecateIntensityColors.of(it) }

    Box(modifier.fillMaxWidth().height(height)) {
        if (points.size < 2) return@Box
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val origin = points.first().at
            val local = points.map { Geo.toLocal(origin, it.at) }

            var minX = Double.MAX_VALUE
            var maxX = -Double.MAX_VALUE
            var minY = Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (p in local) {
                minX = min(minX, p[0]); maxX = max(maxX, p[0])
                minY = min(minY, p[1]); maxY = max(maxY, p[1])
            }
            val spanX = (maxX - minX).coerceAtLeast(1.0)
            val spanY = (maxY - minY).coerceAtLeast(1.0)

            val pad = 16f
            val usableW = size.width - 2 * pad
            val usableH = size.height - 2 * pad
            // One scale for both axes: a route must not be distorted into a shape
            // it does not have.
            val scale = min(usableW / spanX, usableH / spanY)
            val offX = pad + (usableW - spanX * scale).toFloat() / 2f
            val offY = pad + (usableH - spanY * scale).toFloat() / 2f

            fun toCanvas(p: DoubleArray) = Offset(
                x = offX + ((p[0] - minX) * scale).toFloat(),
                // Canvas y grows downwards; north must point up.
                y = size.height - offY - ((p[1] - minY) * scale).toFloat(),
            )

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
