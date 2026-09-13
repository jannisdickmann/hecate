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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.hecate.core.profile.Elevation
import kotlin.math.max

/**
 * The altitude profile under the track: height against distance travelled.
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
 */
@Composable
fun ElevationView(
    points: List<Elevation.Point>,
    modifier: Modifier = Modifier,
    height: Dp = 110.dp,
    color: Color = routeLineColor,
) {
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

            fun toCanvas(p: Elevation.Point) = Offset(
                x = pad + (((p.distanceM - firstD) / spanD) * usableW).toFloat(),
                // Canvas y grows downwards; up must be up.
                y = size.height - pad - (((p.altM - lo) / span) * usableH).toFloat(),
            )

            val line = Path().apply {
                val first = toCanvas(points.first())
                moveTo(first.x, first.y)
                for (i in 1 until points.size) {
                    val c = toCanvas(points[i])
                    lineTo(c.x, c.y)
                }
            }

            // Filled underneath, fading out: the area is what makes a profile read
            // as ground at a glance rather than as an arbitrary line chart.
            val area = Path().apply {
                addPath(line)
                lineTo(pad + usableW, size.height - pad)
                lineTo(pad, size.height - pad)
                close()
            }
            drawPath(
                area,
                brush = Brush.verticalGradient(
                    listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.04f)),
                ),
            )
            drawPath(line, color = color, style = Stroke(width = 2.5f, cap = StrokeCap.Round))
        }
    }
}
