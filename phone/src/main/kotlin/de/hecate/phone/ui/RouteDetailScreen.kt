package de.hecate.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hecate.core.weather.WeatherCode
import de.hecate.phone.db.SessionSummary
import kotlin.math.abs
import kotlin.math.max

@Composable
fun RouteDetailScreen(vm: HecateViewModel) {
    val detail by vm.routeDetail.collectAsStateWithLifecycle()
    val peakHr by vm.peakHr.collectAsStateWithLifecycle()
    val d = detail ?: return
    ReloadAfterClustering(vm)
    RouteDetailContent(
        d, peakHr,
        RouteActions(
            onRename = { vm.rename(d.id, it) },
            onPriority = { vm.setPriority(d.id, it) },
            onDropTemporary = { seed -> vm.setTemporaryRoute(seed, false); vm.back() },
            onOpenSession = { vm.show(Screen.SessionDetail(it)) },
        ),
    )
}

class RouteActions(
    val onRename: (String) -> Unit = {},
    val onPriority: (Int) -> Unit = {},
    val onDropTemporary: (String) -> Unit = {},
    val onOpenSession: (String) -> Unit = {},
)

@Composable
fun RouteDetailContent(d: RouteDetail, peakHr: Int, actions: RouteActions) {
    var editing by remember { mutableStateOf(false) }
    var draft by remember(d.id) { mutableStateOf(d.name) }
    var mapOpen by rememberSaveable { mutableStateOf(false) }
    val map = rememberTrackMap(d.geometry, peakHr)
    val median = d.medianDurationMs

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = HecateDesign.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(HecateDesign.Gap),
    ) {
        Spacer(Modifier.height(4.dp))
        Column(Modifier.appear(0)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActivityBadge(d.exerciseType, size = 30.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    d.exerciseType.label + if (d.temporary) " · vorläufige Route" else " · Route",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(10.dp))
            if (editing) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { actions.onRename(draft); editing = false }) {
                        Icon(Icons.Filled.Check, contentDescription = "Name übernehmen")
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        d.name,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { editing = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Umbenennen", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
        MapCard(map, onOpen = { mapOpen = true }, modifier = Modifier.appear(1), aspect = 1.3f)
        StatGrid(
            listOf(
                Format.distance(d.lengthM).let { val i = it.lastIndexOf(' '); Stat("Länge", it.substring(0, i), it.substring(i + 1)) },
                Stat("Aufzeichnungen", d.memberCount.toString()),
                Stat("Median", durationOrDash(median)),
                Stat("Beste", durationOrDash(d.bestDurationMs), accent = MaterialTheme.colorScheme.primary),
                Stat("Langsamste", durationOrDash(d.worstDurationMs)),
                Stat("Streuung", if (d.bestDurationMs != null && d.worstDurationMs != null) Format.delta(d.worstDurationMs - d.bestDurationMs) else "–"),
            ),
            Modifier.appear(2),
        )
        if (d.sessions.size >= 2) TrendCard(d.sessions, median, Modifier.appear(3))
        RouteChartsCard(d, peakHr, Modifier.appear(4))
        SectionCard("Priorität", Figure.SPEED, Modifier.appear(5)) {
            PriorityPicker(d.priority, actions.onPriority)
            if (d.temporary) {
                Spacer(Modifier.height(12.dp))
                Quiet("Vorläufige Route")
                d.seedSessionId?.let { seed ->
                    TextButton(onClick = { actions.onDropTemporary(seed) }) { Text("Vorläufige Route aufheben") }
                }
            }
        }
        SessionsCard(d.sessions, median, d.bestDurationMs, actions, Modifier.appear(6))
        Spacer(Modifier.height(24.dp))
    }
    if (mapOpen && map != null) MapFullScreen(map, title = d.name, caption = null, onDismiss = { mapOpen = false })
}

/**
 * Every time this route was done, in order: is it getting faster? Faster is
 * drawn higher, the median is a dashed line, the best a trophy-coloured dot.
 */
@Composable
private fun TrendCard(sessions: List<SessionSummary>, median: Long?, modifier: Modifier) {
    val ordered = remember(sessions) { sessions.sortedBy { it.startedAtUtc } }
    val line = MaterialTheme.colorScheme.primary
    val best = MaterialTheme.colorScheme.tertiary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val halo = MaterialTheme.colorScheme.surfaceContainerLow
    val measurer = rememberTextMeasurer()
    val label = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val first = ordered.first()
    val last = ordered.last()
    val recent = ordered.takeLast(3).map { it.activeDurationMs }.average()
    val early = ordered.take(3).map { it.activeDurationMs }.average()
    SectionCard(
        "Entwicklung",
        Figure.CHART,
        modifier,
        trailing = {
            val diff = (recent - early).toLong()
            if (ordered.size >= 4 && abs(diff) >= 1000) {
                Pill(
                    (if (diff < 0) "${Format.delta(diff)} schneller" else "${Format.delta(diff)} langsamer"),
                    container = if (diff < 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    content = if (diff < 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val lo = ordered.minOf { it.activeDurationMs }.toFloat()
            val hi = max(ordered.maxOf { it.activeDurationMs }.toFloat(), lo + 1f)
            val t0 = first.startedAtUtc.toFloat()
            val t1 = max(last.startedAtUtc.toFloat(), t0 + 1f)
            val padX = 12.dp.toPx()
            val padY = 18.dp.toPx()
            fun at(s: SessionSummary) = Offset(
                padX + (s.startedAtUtc - t0) / (t1 - t0) * (size.width - 2 * padX),
                padY + (s.activeDurationMs - lo) / (hi - lo) * (size.height - 2 * padY),
            )
            median?.let { m ->
                val y = padY + (m - lo) / (hi - lo) * (size.height - 2 * padY)
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)))
                val t = measurer.measure("Median", label)
                drawText(t, color = labelColor, topLeft = Offset(size.width - t.size.width, y - t.size.height - 2.dp.toPx()))
            }
            val path = androidx.compose.ui.graphics.Path()
            ordered.forEachIndexed { i, s -> val o = at(s); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, line.copy(alpha = .45f), style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            val bestS = ordered.minBy { it.activeDurationMs }
            for (s in ordered) {
                val o = at(s)
                drawCircle(halo, radius = 6.dp.toPx(), center = o)
                drawCircle(if (s == bestS) best else line, radius = if (s == bestS || s == last) 5.dp.toPx() else 3.5.dp.toPx(), center = o)
            }
            val bt = measurer.measure(Format.duration(bestS.activeDurationMs), label)
            val bo = at(bestS)
            drawText(bt, color = best, topLeft = Offset((bo.x - bt.size.width / 2).coerceIn(0f, size.width - bt.size.width), (bo.y - bt.size.height - 6.dp.toPx()).coerceAtLeast(0f)))
        }
        Row(Modifier.fillMaxWidth()) {
            Quiet(Format.date(first.startedAtUtc, first.tzOffsetMinutes), Modifier.weight(1f))
            Quiet("schneller ↑", Modifier.weight(1f), textAlign = TextAlign.Center)
            Quiet(Format.date(last.startedAtUtc, last.tzOffsetMinutes), Modifier.weight(1f), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun RouteChartsCard(d: RouteDetail, peakHr: Int, modifier: Modifier) {
    if (d.elevation.size < 2 && d.speed.size < 2 && d.heartRate.size < 2) return
    // "Median" in the title, because that is what a route is: the middle of
    // everything its members did here, not any one ride.
    SectionCard("Typischer Verlauf", Figure.MOUNTAIN, modifier, trailing = { Quiet("Median") }) {
        if (d.elevation.size >= 2) {
            RouteChartTitle("Höhe", d.elevationStats?.let { "+${it.ascentM.toInt()} m / −${it.descentM.toInt()} m" })
            Chart(d.elevation.asChart(), ChartKind.ELEVATION, peakHrBpm = peakHr)
        }
        if (d.speed.size >= 2) {
            Spacer(Modifier.height(10.dp))
            RouteChartTitle("Tempo", d.speedStats?.let { "${Format.speed(it.min)} – ${Format.speedLabel(it.max)}" })
            Chart(d.speed.asChart(), ChartKind.SPEED, peakHrBpm = peakHr)
        }
        if (d.heartRate.size >= 2) {
            Spacer(Modifier.height(10.dp))
            RouteChartTitle("Puls", d.heartRateStats?.let { "${Format.hr(it.min)} – ${Format.hr(it.max)} bpm" })
            Chart(d.heartRate.asChart(), ChartKind.HEART_RATE, peakHrBpm = peakHr)
        }
        IntensityLegend(d.geometry, d.elevation, d.speed)
    }
}

@Composable
private fun RouteChartTitle(title: String, range: String?) {
    Row(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        range?.let { Text(it, style = MaterialTheme.typography.labelMedium.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The route's recordings, newest first, each against the median. */
@Composable
private fun SessionsCard(sessions: List<SessionSummary>, median: Long?, best: Long?, actions: RouteActions, modifier: Modifier) {
    SectionCard("Aufzeichnungen", Figure.CALENDAR, modifier, trailing = { Quiet("${sessions.size}") }) {
        sessions.forEachIndexed { i, s ->
            if (i > 0) Spacer(Modifier.height(2.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(HecateDesign.InnerShape)
                    .clickable { actions.onOpenSession(s.id) }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(Format.date(s.startedAtUtc, s.tzOffsetMinutes), style = MaterialTheme.typography.bodyMedium.tabular)
                    Quiet(Format.timeOfDay(s.startedAtUtc, s.tzOffsetMinutes) + (s.avgHr?.let { " · ${Format.hr(it)} bpm" } ?: ""))
                }
                s.weatherCode?.let { code ->
                    WeatherGlyph(WeatherCode.sky(code), s.weatherIsDay != false, size = 20.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(Format.temp(s.tempMaxC), style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(30.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End, modifier = Modifier.width(76.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (s.activeDurationMs == best) {
                            FigureGlyph(Figure.TROPHY, MaterialTheme.colorScheme.tertiary, size = 14.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(Format.duration(s.activeDurationMs), style = MaterialTheme.typography.titleSmall.tabular, fontWeight = FontWeight.SemiBold)
                    }
                    median?.let { m ->
                        val delta = s.activeDurationMs - m
                        Text(
                            if (abs(delta) < 1000) "±0:00" else (if (delta < 0) "−" else "+") + Format.delta(delta),
                            style = MaterialTheme.typography.labelMedium.tabular,
                            color = if (delta < 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
