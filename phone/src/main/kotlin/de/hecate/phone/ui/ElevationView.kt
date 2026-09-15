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
import de.hecate.core.profile.Elevation
import kotlin.math.max

/**
 * The altitude profile under the track: height against distance travelled,
 * coloured by how hard the user was working when they were there.
 *
 * Same approach as [PolylineView] and for the same reasons — a Compose `Canvas`,
 * no chart library, §4. Half its height, because it is the second thing on the
 * screen and a chart that competes with the map for attention wins an argument
 * it should not be having.
 *
 * **The axes are deliberately not to the same scale**, which is the one way this
 * differs from the map above it. A 17 km ride that climbs 120 m drawn honestly is
 * a horizontal line; every elevation profile ever printed is exaggerated
 * vertically, and the alternative is a chart with nothing on it. The height
 * labels beside it are what keeps that honest, so they are not optional
 * decoration — without them the reader has no way to know whether a dramatic
 * peak is 30 m or 800.
 *
 * The effort is the colour of the line and the ground under it, and nothing
 * above it. It used to be drawn a third time as a full-height band behind the
 * whole chart, on the argument that a hard stretch on flat ground leaves the
 * curve too little to colour — but a band that runs above the line colours sky,
 * and the sky is the one part of this picture where nobody was: the eye reads
 * the tint as area under something, finds the curve in the middle of it, and the
 * chart stops saying where the ground is. Under the line the same colour says
 * the same thing about the same stretch, and says it where the data is.
 */
@Composable
fun ElevationView(
    points: List<Elevation.Point>,
    modifier: Modifier = Modifier,
    height: Dp = 110.dp,
    peakHrBpm: Int = Tuning.DEFAULT_PEAK_HR_BPM,
) {
    val plain = routeLineColor
    val bandColors = Intensity.entries.associateWith { HecateIntensityColors.of(it) }

    Box(modifier.fillMaxWidth().height(height)) {
        if (points.size < 2) return@Box
        Canvas(Modifier.fillMaxWidth().height(height)) {
            var minAlt = Double.MAX_VALUE
            var maxAlt = -Double.MAX_VALUE
            for (p in points) {
                if (p.altM < minAlt) minAlt = p.altM
                if (p.altM > maxAlt) maxAlt = p.altM
            }
            // A flat route still gets a band to sit in rather than a division by
            // zero, and sits in the middle of it rather than on the floor.
            val span = max(maxAlt - minAlt, 10.0)
            val mid = (maxAlt + minAlt) / 2.0
            val lo = mid - span / 2.0
            val firstD = points.first().distanceM
            val spanD = max(points.last().distanceM - firstD, 1.0)

            val pad = 10f
            val usableW = size.width - 2 * pad
            val usableH = size.height - 2 * pad
            val floor = size.height - pad

            fun toCanvas(p: Elevation.Point) = Offset(
                x = pad + (((p.distanceM - firstD) / spanD) * usableW).toFloat(),
                // Canvas y grows downwards; up must be up.
                y = floor - (((p.altM - lo) / span) * usableH).toFloat(),
            )

            // One run per stretch of unchanging effort, as in the track drawing:
            // the zone a heart rate is in does not flicker from second to second,
            // so the runs are long and this is a handful of paths rather than
            // four hundred.
            var runStart = 0
            var runBand = bandOf(points, 1, peakHrBpm)
            for (i in 1 until points.size) {
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
 * One stretch of the profile in one colour: the ground under it, and the line
 * itself.
 */
private fun DrawScope.drawSection(
    points: List<Elevation.Point>,
    from: Int,
    to: Int,
    toCanvas: (Elevation.Point) -> Offset,
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

    // Filled underneath, fading out: the area is what makes a profile read as
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
private fun bandOf(points: List<Elevation.Point>, index: Int, peakHrBpm: Int): Intensity? {
    val a = points[index - 1].hrBpm
    val b = points[index].hrBpm
    val hr = when {
        a != null && b != null -> (a + b) / 2.0
        else -> a ?: b
    }
    return Intensity.of(hr, peakHrBpm)
}
