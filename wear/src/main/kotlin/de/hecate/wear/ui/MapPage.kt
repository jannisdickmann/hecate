package de.hecate.wear.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import de.hecate.core.Tuning
import de.hecate.core.display.HeadingUp
import de.hecate.core.display.RouteBeliefKind
import de.hecate.core.geo.LatLon
import de.hecate.wear.recorder.RecorderUiState

/**
 * The map page, left of the live page: the route being followed and the user's
 * own recent track, centred on where they are and turned so that the way they
 * are going points up.
 *
 * Not a map. There are no streets, no names and no tiles — the watch has none,
 * and on a face this size they would bury the two lines that matter. What it
 * answers is the question the deviation haptic raises and cannot answer: am I
 * still on it, and if not, which way is it. Two lines and an arrow do that at a
 * glance; the scale is fixed per sport ([Tuning.watchMapRadiusM]) so that a
 * gap between them always means the same distance.
 *
 * The edge bars are the live page's own, so progress and ahead/behind read the
 * same here. The one line of text is the pair that belongs with a picture of
 * the route: how far to go and when you will be there — or, with no route to
 * follow, how long and how far so far.
 */
@Composable
internal fun MapPage(state: RecorderUiState) {
    val layout = rememberLiveLayout()
    val trail = state.trail
    val here = trail.lastOrNull()

    // The direction of travel, smoothed, and unwrapped: kept as a running total
    // rather than folded into 0..360 so that the rotation animation below always
    // takes the short way round instead of spinning through south to get from
    // 350° to 10°.
    var smoothed by remember { mutableStateOf<Double?>(null) }
    var unwrapped by remember { mutableDoubleStateOf(0.0) }
    val lookback = Tuning.headingLookbackM(state.exerciseType)
    LaunchedEffect(trail) {
        val raw = HeadingUp.heading(trail, lookback) ?: return@LaunchedEffect
        val prev = smoothed
        val next = HeadingUp.blend(prev, raw, if (prev == null) 1.0 else Tuning.HEADING_SMOOTHING)
        unwrapped += if (prev == null) next - unwrapped else HeadingUp.signedDelta(prev, next)
        smoothed = next
    }
    val rotation by animateFloatAsState(unwrapped.toFloat(), tween(900), label = "heading")

    val routeColor = when {
        state.followedRoute == null -> Color.Transparent
        state.followedRoute.assumed -> RouteTone.Likely.copy(alpha = 0.45f)
        // Translucent, so the user's own line stays readable where it runs
        // on top of the route — which is, after all, where it should be.
        state.belief.kind == RouteBeliefKind.ASSUMED -> RouteTone.Assumed.copy(alpha = 0.6f)
        else -> RouteTone.Confirmed.copy(alpha = 0.6f)
    }
    val trailColor = Color.White.copy(alpha = if (state.positionStale) 0.45f else 0.95f)
    val measurer = rememberTextMeasurer()
    val northStyle = TextStyle(fontSize = 11.sp, color = Color.White.copy(alpha = 0.55f))

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            if (here == null) return@Canvas
            val radiusPx = size.minDimension / 2f
            val pxPerM = (radiusPx / Tuning.watchMapRadiusM(state.exerciseType)).toFloat()
            // Lower than the middle: what is ahead is what there is to see.
            val centre = Offset(size.width / 2f, size.height * CENTRE_FRACTION)

            // The ground is drawn north-up around the position and then turned
            // as a whole, so the projection is a plain one and the turning is
            // the canvas's — which is what lets it be animated without
            // re-projecting a thousand points sixty times a second.
            rotate(-rotation, pivot = centre) {
                state.followedRoute?.let { route ->
                    drawPolyline(route.points, here, pxPerM, centre, routeColor, ROUTE_WIDTH_DP.dp.toPx())
                }
                drawPolyline(trail, here, pxPerM, centre, trailColor, TRAIL_WIDTH_DP.dp.toPx())

                // North, at the rim, turning with the ground: the one fixed
                // reference on a picture that otherwise has none.
                val north = Offset(centre.x, centre.y - radiusPx * 0.86f)
                rotate(rotation, pivot = north) {
                    val n = measurer.measure("N", northStyle)
                    drawText(n, topLeft = Offset(north.x - n.size.width / 2f, north.y - n.size.height / 2f))
                }
            }
            drawPosition(centre, if (state.positionStale) 0.5f else 1f)
        }

        if (here == null) {
            Text(
                "Warte auf GPS…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        EdgeBars(state, layout)

        Text(
            summaryOf(state),
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 26.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

/**
 * The page's one line: distance to go and arrival while a route is being
 * followed, otherwise time and distance so far.
 */
private fun summaryOf(state: RecorderUiState): String {
    val remaining = state.remainingM
    return if (state.belief.hasRoute && remaining != null) {
        val guess = if (state.belief.isProvisional || state.positionStale) "≈ " else ""
        "$guess${Format.distance(remaining)} · ${Format.arrival(state.arrivalAtUtcMs)}"
    } else {
        "${Format.duration(state.activeDurationMs)} · ${Format.distance(state.distanceM)}"
    }
}

/** A polyline, north-up around [here]; points far off the face are skipped. */
private fun DrawScope.drawPolyline(
    points: List<LatLon>,
    here: LatLon,
    pxPerM: Float,
    centre: Offset,
    color: Color,
    widthPx: Float,
) {
    if (points.size < 2 || color.alpha == 0f) return
    val reach = size.maxDimension * 1.5f
    val path = Path()
    var pen = false
    for (p in points) {
        val xy = HeadingUp.project(here, 0.0, pxPerM.toDouble(), centre.x.toDouble(), centre.y.toDouble(), p)
        val x = xy[0].toFloat()
        val y = xy[1].toFloat()
        val near = x > -reach && x < size.width + reach && y > -reach && y < size.height + reach
        if (!near) { pen = false; continue }
        if (pen) path.lineTo(x, y) else { path.moveTo(x, y); pen = true }
    }
    drawPath(path, color, style = Stroke(width = widthPx, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** The user: an arrow pointing up, which is always the way they are going. */
private fun DrawScope.drawPosition(centre: Offset, alpha: Float) {
    val s = 9.dp.toPx()
    val arrow = Path().apply {
        moveTo(centre.x, centre.y - s)
        lineTo(centre.x + s * 0.75f, centre.y + s * 0.8f)
        lineTo(centre.x, centre.y + s * 0.35f)
        lineTo(centre.x - s * 0.75f, centre.y + s * 0.8f)
        close()
    }
    drawPath(arrow, Color.Black, style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
    drawPath(arrow, Color.White.copy(alpha = alpha))
}

/** Where the position sits, as a share of the face's height from the top. */
private const val CENTRE_FRACTION = 0.6f
private const val ROUTE_WIDTH_DP = 6f
private const val TRAIL_WIDTH_DP = 2.5f
