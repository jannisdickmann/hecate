package de.hecate.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.hecate.core.Tuning
import de.hecate.core.model.Intensity
import kotlin.math.max

/**
 * One point of any chart in this app: where along, how much, and how hard.
 *
 * Deliberately not [de.hecate.core.profile.Elevation.Point] or
 * [de.hecate.core.profile.Timeline.Point]: those are what the arithmetic
 * produces, in the units it produced them in, and this is what a chart draws.
 * The mapping between them is one line at each call site and is the only place
 * that knows whether the x axis is metres or milliseconds.
 */
data class ChartPoint(val x: Double, val y: Double, val hrBpm: Double? = null)

/**
 * Every chart in this app, drawn once.
 *
 * Four of them exist — altitude against distance, speed against time, heart rate
 * against time, on a recording and on a route — and they are one composable
 * because four separate ones would not stay the same shape for a month. What
 * "the same" means here is specific: the same vertical exaggeration, the same
 * intensity colours in the same bands, the same fill under the line, the same
 * thinning ([de.hecate.core.profile.Series.thin]), and the same treatment of a
 * gap.
 *
 * Same approach as [PolylineView] and for the same reasons — a Compose `Canvas`,
 * no chart library, §4.
 *
 * **The axes are deliberately not to the same scale**, and the y axis does not
 * start at zero. A 17 km ride that climbs 120 m drawn honestly is a horizontal
 * line, and so is an hour held between 130 and 150 bpm; every elevation profile
 * ever printed is exaggerated vertically, and the alternative is a chart with
 * nothing on it. What keeps that honest is that the range is always written
 * beside the chart in words — which is why [minSpanY] exists rather than a
 * fixed zero baseline: a series that genuinely does not move gets a band to sit
 * in, in the middle of it, instead of being magnified into drama.
 *
 * The colour is the effort at each point, in the four bands [Intensity] defines,
 * and it is drawn twice: as the line, which is exactly where the data is, and as
 * the fill under it, which is what makes a chart read as ground rather than as
 * an arbitrary line drawing. Never above the line — see A.22.
 *
 * [gapX] is how far apart two points may be on the x axis before the line stops
 * rather than being drawn across: a dropout is not a straight line through
 * unknown terrain, and on a heart-rate chart the strap losing contact for two
 * minutes must not read as two minutes of smooth effort.
 */
@Composable
fun BandedChart(
    points: List<ChartPoint>,
    modifier: Modifier = Modifier,
    height: Dp = 110.dp,
    peakHrBpm: Int = Tuning.DEFAULT_PEAK_HR_BPM,
    minSpanY: Double = 0.0,
    gapX: Double = Double.MAX_VALUE,
) {
    val plain = routeLineColor
    val bandColors = Intensity.entries.associateWith { HecateIntensityColors.of(it) }

    Box(modifier.fillMaxWidth().height(height)) {
        if (points.size < 2) return@Box
        Canvas(Modifier.fillMaxWidth().height(height)) {
            var minY = Double.MAX_VALUE
            var maxY = -Double.MAX_VALUE
            for (p in points) {
                if (p.y < minY) minY = p.y
                if (p.y > maxY) maxY = p.y
            }
            // A series that does not move still gets a band to sit in rather
            // than a division by zero, and sits in the middle of it rather than
            // on the floor.
            val span = max(maxY - minY, minSpanY).takeIf { it > 0 } ?: 1.0
            val mid = (maxY + minY) / 2.0
            val lo = mid - span / 2.0
            val firstX = points.first().x
            val spanX = max(points.last().x - firstX, 1e-9)

            val pad = 10f
            val usableW = size.width - 2 * pad
            val usableH = size.height - 2 * pad
            val floor = size.height - pad

            fun toCanvas(p: ChartPoint) = Offset(
                x = pad + (((p.x - firstX) / spanX) * usableW).toFloat(),
                // Canvas y grows downwards; up must be up.
                y = floor - (((p.y - lo) / span) * usableH).toFloat(),
            )

            // One run per stretch of unchanging effort, as in the track drawing:
            // the zone a heart rate is in does not flicker from second to
            // second, so the runs are long and this is a handful of paths rather
            // than four hundred. A gap ends a run too, and starts the next one
            // after it rather than across it.
            var runStart = 0
            var runBand = bandOf(points, 1, peakHrBpm)
            for (i in 1 until points.size) {
                if (points[i].x - points[i - 1].x > gapX) {
                    drawSection(points, runStart, i - 1, ::toCanvas, floor, runBand, bandColors, plain)
                    runStart = i
                    runBand = if (i + 1 < points.size) bandOf(points, i + 1, peakHrBpm) else null
                    continue
                }
                val band = bandOf(points, i, peakHrBpm)
                if (band != runBand) {
                    drawSection(points, runStart, i - 1, ::toCanvas, floor, runBand, bandColors, plain)
                    runStart = i - 1
                    runBand = band
                }
            }
            drawSection(points, runStart, points.size - 1, ::toCanvas, floor, runBand, bandColors, plain)
        }
    }
}

/**
 * One stretch of a chart in one colour: the ground under it, and the line
 * itself.
 */
private fun DrawScope.drawSection(
    points: List<ChartPoint>,
    from: Int,
    to: Int,
    toCanvas: (ChartPoint) -> Offset,
    floor: Float,
    band: Intensity?,
    bandColors: Map<Intensity, Color>,
    plain: Color,
) {
    if (to <= from) return
    val color = band?.let { bandColors[it] } ?: plain
    val left = toCanvas(points[from]).x
    val right = toCanvas(points[to]).x

    val line = Path().apply {
        val first = toCanvas(points[from])
        moveTo(first.x, first.y)
        for (i in from + 1..to) {
            val c = toCanvas(points[i])
            lineTo(c.x, c.y)
        }
    }

    // Filled underneath, fading out: the area is what makes a chart read as
    // ground at a glance rather than as an arbitrary line chart, and with no
    // band behind the chart it is also the only place the effort has to show.
    // Stronger at the top for that reason — it is the colour beside the line,
    // where the reader is already looking, that has to carry the band.
    val area = Path().apply {
        addPath(line)
        lineTo(right, floor)
        lineTo(left, floor)
        close()
    }
    drawPath(
        area,
        brush = Brush.verticalGradient(
            listOf(color.copy(alpha = 0.42f), color.copy(alpha = 0.06f)),
        ),
    )
    drawPath(
        line,
        color = color,
        style = Stroke(width = 2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round),
    )
}

/** The band of the segment ending at [index], as [PolylineView] computes it. */
private fun bandOf(points: List<ChartPoint>, index: Int, peakHrBpm: Int): Intensity? {
    val a = points[index - 1].hrBpm
    val b = points[index].hrBpm
    val hr = when {
        a != null && b != null -> (a + b) / 2.0
        else -> a ?: b
    }
    return Intensity.of(hr, peakHrBpm)
}
