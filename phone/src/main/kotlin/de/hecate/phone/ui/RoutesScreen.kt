package de.hecate.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hecate.core.Tuning
import de.hecate.phone.db.RouteSummary
import kotlin.math.max

@Composable
fun RoutesScreen(vm: HecateViewModel) {
    val routes by vm.routes.collectAsStateWithLifecycle()
    val clustering by vm.clustering.collectAsStateWithLifecycle()
    val heatmap by vm.heatmap.collectAsStateWithLifecycle()
    val loaded by vm.routesLoaded.collectAsStateWithLifecycle()
    val trends by vm.routeTrends.collectAsStateWithLifecycle()
    if (!loaded) return
    // Hoisted above the list: the raster is built off the main thread and kept
    // while the screen lives, so scrolling the card out of the list and back
    // does not rebuild it.
    var expanded by remember { mutableStateOf(false) }
    val raster = heatmap?.let { rememberHeatmapRaster(it.grid) }
    // The full-screen picture is a different raster of different ground, read
    // only while it is up — see [HecateViewModel.openHeatmap].
    val full by vm.fullHeatmap.collectAsStateWithLifecycle()

    RoutesContent(
        routes = routes,
        clustering = clustering,
        heatMap = heatmap?.let { rememberHeatMap(it, raster) },
        heatCaption = heatmap?.let { heatmapCaption(it) },
        trends = trends,
        onOpenHeatmap = { expanded = true; vm.openHeatmap() },
        onOpen = { vm.show(Screen.RouteDetail(it)) },
    )

    if (expanded) {
        // A different picture from the card, not an enlargement of it: every
        // recording the radius filter kept, in whatever shape that ground is.
        val fullMap = full?.let { f -> rememberHeatMap(f, rememberHeatmapRaster(f.grid)) }
        MapFullScreen(
            content = fullMap,
            title = "Alle Aufzeichnungen",
            caption = full?.let { heatmapCaption(it) },
            onDismiss = { expanded = false; vm.closeHeatmap() },
        )
    }
}

@Composable
fun RoutesContent(
    routes: List<RouteSummary>,
    clustering: Boolean,
    heatMap: MapContent?,
    heatCaption: String?,
    trends: Map<Long, List<Long>>,
    onOpenHeatmap: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    if (routes.isEmpty() && !clustering) {
        Empty("Noch keine Routen", Figure.ROUTE)
        return
    }
    Column(Modifier.fillMaxSize()) {
        // Here as well as in Settings, because a re-clustering is most visible
        // by its effect on this list: it is about to rearrange itself.
        if (clustering) ClusteringBanner()
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (heatMap != null) {
                item(key = "heatmap") {
                    MapCard(
                        heatMap,
                        onOpen = onOpenHeatmap,
                        modifier = Modifier.appear(0),
                        caption = {
                            Text("Wo du unterwegs warst", style = MaterialTheme.typography.titleSmall)
                            heatCaption?.let { Quiet(it) }
                        },
                    )
                }
            }
            itemsIndexed(routes, key = { _, r -> r.id }) { i, r ->
                RouteRow(r, trends[r.id].orEmpty(), Modifier.appear(i + 1)) { onOpen(r.id) }
            }
        }
    }
}

/** What the heatmap is made of, in one line — under the card and under the
 *  full-screen view. Both ways of not being in the picture are named, because
 *  an undrawn recording looks exactly like one that was never made. */
internal fun heatmapCaption(state: HeatmapState): String = buildString {
    append("${state.grid.sessionsUsed} Aufzeichnungen")
    append(" · ${state.routes.size} Routen")
    if (state.grid.isEmpty) append(" · keine Messpunkte im Ausschnitt")
    val discarded = state.grid.routesDiscarded + state.grid.sessionsDiscarded
    if (discarded > 0) append(" · $discarded außerhalb von ${(Tuning.HEATMAP_MAX_RADIUS_M / 1000).toInt()} km")
    val offPicture = state.grid.sessionsOutsideExtent
    if (offPicture > 0) append(" · $offPicture außerhalb des Ausschnitts")
}

@Composable
private fun RouteRow(r: RouteSummary, trend: List<Long>, modifier: Modifier, onClick: () -> Unit) {
    HecateCard(modifier, onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActivityBadge(r.exerciseType)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Quiet(
                    "${Format.distance(r.lengthM)} · ${r.memberCount} " +
                        (if (r.memberCount == 1) "Aufzeichnung" else "Aufzeichnungen"),
                )
            }
            if (trend.size >= 2) {
                Spacer(Modifier.width(8.dp))
                Sparkline(trend, Modifier.width(76.dp).height(32.dp))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Pill(
                "Median ${durationOrDash(r.medianDurationMs)}",
                container = MaterialTheme.colorScheme.surfaceContainerHighest,
                content = MaterialTheme.colorScheme.onSurface,
            )
            Pill(
                "Beste ${durationOrDash(r.bestDurationMs)}",
                leading = { FigureGlyph(Figure.TROPHY, MaterialTheme.colorScheme.onPrimaryContainer, size = 13.dp) },
            )
            if (r.temporary) Pill("vorläufig", outlined = true)
            // Only worth the pixels when the user has actually said something.
            if (r.priority != Tuning.DEFAULT_ROUTE_PRIORITY) Pill("Prio ${Format.priority(r.priority)}", outlined = true)
        }
    }
}

/**
 * A route's member times, oldest to newest, faster drawn *higher* — up is
 * better on every other chart a person reads — with the latest as a dot.
 */
@Composable
fun Sparkline(durations: List<Long>, modifier: Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val best = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        val lo = durations.min().toFloat()
        val hi = max(durations.max().toFloat(), lo + 1f)
        val pad = 4.dp.toPx()
        fun at(i: Int) = Offset(
            pad + i.toFloat() / (durations.size - 1) * (size.width - 2 * pad),
            pad + (durations[i] - lo) / (hi - lo) * (size.height - 2 * pad),
        )
        val path = Path()
        for (i in durations.indices) {
            val o = at(i)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(path, line.copy(alpha = .7f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val bestIdx = durations.indices.minBy { durations[it] }
        drawCircle(best, radius = 3.dp.toPx(), center = at(bestIdx))
        drawCircle(line, radius = 3.5.dp.toPx(), center = at(durations.size - 1))
    }
}

@Composable
internal fun ClusteringBanner() {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Routen werden neu berechnet…", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}
