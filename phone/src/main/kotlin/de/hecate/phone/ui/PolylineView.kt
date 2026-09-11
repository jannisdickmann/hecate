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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import kotlin.math.max
import kotlin.math.min

/**
 * §4: no maps SDK. A plain polyline on a Compose `Canvas` — no API key, no
 * billing, no tile cache, no library — which is all US-8 asks for: "a plain
 * polyline drawing of the route geometry".
 *
 * Latitude and longitude are projected locally to metres before scaling so the
 * drawing has the right aspect ratio rather than being stretched by the cosine
 * of the latitude.
 */
@Composable
fun PolylineView(
    points: List<LatLon>,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    color: Color = Color(0xFF4DB6AC),
    startColor: Color = Color(0xFF81C784),
    endColor: Color = Color(0xFFE57373),
) {
    Box(modifier.fillMaxWidth().height(height)) {
        if (points.size < 2) return@Box
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val origin = points.first()
            val local = points.map { Geo.toLocal(origin, it) }

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

            val path = Path().apply {
                val first = toCanvas(local[0])
                moveTo(first.x, first.y)
                for (i in 1 until local.size) {
                    val c = toCanvas(local[i])
                    lineTo(c.x, c.y)
                }
            }
            drawPath(path, color = color, style = Stroke(width = 3f, cap = StrokeCap.Round))
            drawCircle(startColor, radius = 6f, center = toCanvas(local.first()))
            drawCircle(endColor, radius = 6f, center = toCanvas(local.last()))
        }
    }
}
