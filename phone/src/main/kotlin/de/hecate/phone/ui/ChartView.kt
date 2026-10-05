package de.hecate.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.hecate.core.Tuning
import de.hecate.core.model.Intensity
import de.hecate.core.profile.Elevation
import de.hecate.core.profile.Timeline
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
data class ChartPoint(
    val x: Double,
    val y: Double,
    val hrBpm: Double? = null,
    /** Whether the line starts again here. See [de.hecate.core.profile.Timeline.Point]:
     *  a hole is known where the full series is, not here, where the points may
     *  legitimately be minutes apart. */
    val startsRun: Boolean = false,
)

/**
 * The three things this app charts, and the two facts that differ between
 * them: how flat a series may be before the chart stops magnifying it, and how
 * its values are written where the chart labels them.
 *
 * The least span keeps a steady series looking steady. Ten metres of hill over
 * a chart's height is already an exaggeration and less would be drawing noise;
 * a ride held within 5 km/h was steady, and steady is the fact worth showing; a
 * flat hour at 140 bpm is a flat hour, not a ragged line across the card.
 */
enum class ChartKind(val minSpanY: Double, val label: (Double) -> String) {
    /** Altitude against distance. */
    ELEVATION(10.0, { Format.elevation(it) }),

    /** Speed against time, coloured by the heart rate rather than the speed:
     *  30 km/h in blue is a descent, in orange it is work. */
    SPEED(5.0, { Format.speedLabel(it) }),

    /** Heart rate against time, coloured by itself — which is not a tautology:
     *  it puts the four zone boundaries onto the trace, so the chart answers
     *  "how long was I in the orange" without an axis to read. */
    HEART_RATE(20.0, { "${Format.hr(it)} bpm" }),
}

/**
 * An altitude profile as chart points.
 *
 * There is no gap rule here: [Elevation.of] advances distance across a dropout,
 * because the ground was covered even where its shape is unknown.
 */
@JvmName("elevationAsChart")
fun List<Elevation.Point>.asChart(): List<ChartPoint> = map { ChartPoint(it.distanceM, it.altM, it.hrBpm) }

/** A time series as chart points, keeping where the line has to break. */
@JvmName("timelineAsChart")
fun List<Timeline.Point>.asChart(): List<ChartPoint> =
    map { ChartPoint(it.tMs.toDouble(), it.value, it.hrBpm, it.startsRun) }

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
 * Same approach as the track map ([HecateMap]) and for the same reasons — a Compose `Canvas`,
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
 * A hole in the series — a dropout, a pause, a strap that lost contact — breaks
 * the line rather than being drawn across, because a straight line through
 * unknown terrain is not a measurement. Which points begin a new stretch is
 * decided where the whole series is still in hand and arrives on the point
 * itself: after thinning, two kept points on a seven-hour walk are two minutes
 * apart whether anything happened between them or not.
 *
 * **The highest and the lowest point are labelled on the chart**, with a dot on
 * the line and the value beside it. There is no y axis — an axis is a lot of
 * ink for two numbers — and the range used to be written in a line underneath,
 * where it said how high but not *where*. On the line it says both: the top of
 * the climb is the dot at the top of the climb.
 */
@Composable
fun Chart(
    points: List<ChartPoint>,
    kind: ChartKind,
    modifier: Modifier = Modifier,
    height: Dp = CHART_HEIGHT,
    peakHrBpm: Int = Tuning.DEFAULT_PEAK_HR_BPM,
) {
    val minSpanY = kind.minSpanY
    val measurer = rememberTextMeasurer()
    // No colour in the style: a colour there wins over the one passed to
    // drawText, and the halo pass came out in the text's own colour — a blob.
    val labelStyle = MaterialTheme.typography.labelSmall
    val halo = MaterialTheme.colorScheme.surface
    val dot = MaterialTheme.colorScheme.onSurface
    // A chart with no reading anywhere is one plain line and takes the scheme's
    // colour; one with readings has *holes*, and a hole must recede rather than
    // glow — see [HecateIntensityColors.of].
    val measured = points.any { it.hrBpm != null }
    val plain = if (measured) HecateIntensityColors.Unmeasured else routeLineColor
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

            // Room above the plot for the top label and below it for the bottom
            // one, so neither is ever cut off by the edge of the chart.
            val maxIdx = points.indices.maxBy { points[it].y }
            val minIdx = points.indices.minBy { points[it].y }
            val maxText = measurer.measure(kind.label(points[maxIdx].y), labelStyle)
            val minText = measurer.measure(kind.label(points[minIdx].y), labelStyle)
            val gap = 4.dp.toPx()
            val padX = 10f
            val padTop = maxText.size.height + gap + 2f
            val padBottom = minText.size.height + gap + 2f
            val usableW = size.width - 2 * padX
            val usableH = size.height - padTop - padBottom
            val floor = size.height - padBottom

            fun toCanvas(p: ChartPoint) = Offset(
                x = padX + (((p.x - firstX) / spanX) * usableW).toFloat(),
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
                if (points[i].startsRun) {
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

            drawExtreme(toCanvas(points[maxIdx]), maxText, above = true, gap, dot, halo, dot)
            // A series that never moved has one value, and one label says it.
            if (minText.layoutInput.text != maxText.layoutInput.text) {
                drawExtreme(toCanvas(points[minIdx]), minText, above = false, gap, dot, halo, dot)
            }
        }
    }
}

/** The default height of every chart, labels included. */
val CHART_HEIGHT: Dp = 124.dp

/**
 * A dot on the line and its value beside it — above for the highest point,
 * below for the lowest — kept inside the chart at both ends, where a peak in
 * the first or last metres would otherwise put half its label off the card.
 */
private fun DrawScope.drawExtreme(
    at: Offset,
    text: androidx.compose.ui.text.TextLayoutResult,
    above: Boolean,
    gap: Float,
    dot: Color,
    halo: Color,
    ink: Color,
) {
    drawCircle(halo, radius = 4.dp.toPx(), center = at)
    drawCircle(dot, radius = 2.5.dp.toPx(), center = at)
    val w = text.size.width.toFloat()
    val x = (at.x - w / 2f).coerceIn(0f, (size.width - w).coerceAtLeast(0f))
    val y = if (above) at.y - gap - text.size.height else at.y + gap
    // A halo, as on the map labels: the label may sit over the fill.
    drawText(text, color = halo, topLeft = Offset(x, y), drawStyle = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
    // Fill said explicitly: the halo's stroke stays on the layout's paint, and
    // a second pass that does not override it strokes the label too.
    drawText(text, color = ink, topLeft = Offset(x, y), drawStyle = androidx.compose.ui.graphics.drawscope.Fill)
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

/** The band of the segment ending at [index], as the track map ([rememberTrackMap]) computes it. */
private fun bandOf(points: List<ChartPoint>, index: Int, peakHrBpm: Int): Intensity? {
    val a = points[index - 1].hrBpm
    val b = points[index].hrBpm
    val hr = when {
        a != null && b != null -> (a + b) / 2.0
        else -> a ?: b
    }
    return Intensity.of(hr, peakHrBpm)
}
