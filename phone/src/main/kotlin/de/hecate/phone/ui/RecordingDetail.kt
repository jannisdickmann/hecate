package de.hecate.phone.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hecate.core.geo.LatLon
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.Intensity
import de.hecate.core.profile.Analysis
import de.hecate.core.profile.Timeline
import de.hecate.core.weather.OpenMeteo
import de.hecate.core.weather.Sky
import de.hecate.core.weather.WeatherCode
import de.hecate.core.weather.WeatherReading
import de.hecate.phone.db.HcWriteState
import kotlin.math.abs
import kotlin.math.max

/**
 * The recording screen (US-8): what happened, where, under what sky, and how
 * it compares — built to be read top to bottom and then *used*.
 *
 * **One cursor for the whole screen.** Pressing or swiping across any chart
 * picks a moment; every chart marks it, the weather strip highlights its slot,
 * and a panel slides in from the top with a small map showing *where* that
 * was and every figure measured *then*. The question this answers is the one a
 * chart alone never could: "what was that bump?" — the hill, the light, the
 * rain shower — without leaving the screen.
 *
 * The time charts and the weather are drawn against active time, the altitude
 * profile against distance; the cursor is a moment, and [Analysis.Moment] is
 * what translates it into either axis.
 */
@Composable
fun RecordingDetailScreen(vm: HecateViewModel) {
    val detail by vm.sessionDetail.collectAsStateWithLifecycle()
    val peakHr by vm.peakHr.collectAsStateWithLifecycle()
    val weather by vm.sessionWeather.collectAsStateWithLifecycle()
    val d = detail ?: return
    ReloadAfterClustering(vm)
    RecordingDetailContent(
        d = d,
        peakHr = peakHr,
        weather = weather,
        actions = RecordingActions(
            onSetType = { vm.setExerciseType(d.summary.id, it) },
            onTemporaryRoute = { vm.setTemporaryRoute(d.summary.id, it) },
            onRetryHealthConnect = { vm.retryHealthConnect() },
            onDelete = { vm.deleteSession(d.summary.id) },
            onEnableWeather = { vm.setWeatherEnabled(true) },
            onOpenRoute = { id -> vm.show(Screen.RouteDetail(id)) },
        ),
    )
}

/** Everything the recording screen can ask the app to do. */
class RecordingActions(
    val onSetType: (ExerciseType) -> Unit = {},
    val onTemporaryRoute: (Boolean) -> Unit = {},
    val onRetryHealthConnect: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onEnableWeather: () -> Unit = {},
    val onOpenRoute: (Long) -> Unit = {},
)

/** A weather reading placed on the recording's active clock. */
private data class WeatherPoint(val activeMs: Long, val reading: WeatherReading)

@Composable
fun RecordingDetailContent(
    d: SessionDetail,
    peakHr: Int,
    weather: WeatherView,
    actions: RecordingActions,
    /** Where the cursor starts; for the screenshot harness. */
    initialCursorMs: Long? = null,
) {
    var cursorMs by rememberSaveable(d.summary.id) { mutableStateOf(initialCursorMs) }
    var confirmDelete by remember { mutableStateOf(false) }
    var editingType by remember { mutableStateOf(false) }
    val s = d.summary
    val title = Format.title(s.exerciseType, s.startedAtUtc, s.tzOffsetMinutes)

    val weatherPoints = remember(weather, d.moments) {
        (weather as? WeatherView.Ready)?.track?.readings?.map { r ->
            WeatherPoint(d.moments.minByOrNull { abs(it.tOffsetMs - r.tOffsetMs) }?.activeMs ?: 0L, r)
        }.orEmpty()
    }
    val moment = cursorMs?.let { Analysis.atTime(d.moments, it) }
    val map = rememberTrackMap(d.geometry, peakHr)

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HecateDesign.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(HecateDesign.Gap),
        ) {
            Spacer(Modifier.height(4.dp))
            Hero(d, title, weather, Modifier.appear(0))
            TrackMap(map, title, moment, Modifier.appear(1))
            StatGrid(statsOf(d), Modifier.appear(2))
            RouteCard(d, actions, Modifier.appear(3))
            WeatherCard(weather, weatherPoints, s.startedAtUtc, s.tzOffsetMinutes, cursorMs, { cursorMs = it }, actions, Modifier.appear(4))
            ChartsCard(d, peakHr, cursorMs, { cursorMs = it }, Modifier.appear(5))
            ZonesCard(d, peakHr, Modifier.appear(6))
            SplitsCard(d, peakHr, Modifier.appear(7))
            DetailsCard(d, actions, onEditType = { editingType = true }, Modifier.appear(8))
            OutlinedButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Aufzeichnung löschen")
            }
            Spacer(Modifier.height(28.dp))
        }

        // Over the content rather than in it: it follows the finger, not the
        // scroll position, and a panel that pushed the charts down while they
        // are being swiped would move the chart out from under the finger.
        AnimatedVisibility(
            visible = moment != null,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            moment?.let { m ->
                CursorPanel(d, m, map, weatherPoints, onClose = { cursorMs = null })
            }
        }
    }

    if (editingType) {
        ActivityTypeDialog(
            current = s.exerciseType,
            onPick = { editingType = false; actions.onSetType(it) },
            onDismiss = { editingType = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Aufzeichnung löschen?",
            // Names what actually goes. "Are you sure" is not information.
            body = "${Format.dateTime(s.startedAtUtc, s.tzOffsetMinutes)} — " +
                "${Format.distance(s.totalDistanceM)}, ${Format.duration(s.activeDurationMs)}",
            confirm = "Löschen",
            onConfirm = { confirmDelete = false; actions.onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}

// ----------------------------------------------------------------------- hero

@Composable
private fun Hero(d: SessionDetail, title: String, weather: WeatherView, modifier: Modifier) {
    val s = d.summary
    Column(modifier.padding(top = 4.dp, bottom = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActivityBadge(s.exerciseType, size = 30.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                s.exerciseType.label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.weight(1f))
            if (weather is WeatherView.Ready) {
                val w = weather.summary
                Pill(
                    "${Format.temp(w.maxTempC)} · ${w.code?.let(WeatherCode::label) ?: ""}".trimEnd(' ', '·'),
                    container = MaterialTheme.colorScheme.surfaceContainerHigh,
                    content = MaterialTheme.colorScheme.onSurface,
                    leading = { w.sky?.let { WeatherGlyph(it, w.isDay, size = 18.dp) } },
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Text(
            Format.dayAndTime(s.startedAtUtc, s.tzOffsetMinutes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TrackMap(map: MapContent?, title: String, moment: Analysis.Moment?, modifier: Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    MapCard(
        map,
        onOpen = { open = true },
        modifier = modifier,
        aspect = 1.3f,
        cursor = moment?.let { LatLon(it.lat, it.lon) },
    )
    if (open && map != null) MapFullScreen(map, title = title, caption = null, onDismiss = { open = false })
}

/** "4,20 km" → "4,20" and "km": the figure large, the unit small. */
private fun splitUnit(text: String): Pair<String, String> {
    val i = text.lastIndexOf(' ')
    return if (i < 0) text to "" else text.substring(0, i) to text.substring(i + 1)
}

private fun statsOf(d: SessionDetail): List<Stat> {
    val s = d.summary
    val (dist, distUnit) = splitUnit(Format.distance(s.totalDistanceM))
    return buildList {
        add(Stat("Distanz", dist, distUnit))
        add(Stat("Dauer", Format.duration(s.activeDurationMs)))
        add(Stat("Ø Tempo", Format.speed(Format.speedFrom(s.totalDistanceM, s.activeDurationMs)), "km/h"))
        add(Stat("Ø Puls", Format.hr(s.avgHr), "bpm"))
        val climb = d.elevationStats?.ascentM
        add(Stat("Aufstieg", climb?.let { Format.elevation(it).removeSuffix(" m") } ?: "–", if (climb != null) "m" else ""))
        add(Stat("Max. Tempo", d.speedStats?.max?.let { Format.speed(it) } ?: "–", "km/h"))
    }
}

// ---------------------------------------------------------------------- route

/**
 * How this recording sits among every other time this route was done — the
 * question the app exists to answer, so it gets its own card near the top.
 */
@Composable
private fun RouteCard(d: SessionDetail, actions: RecordingActions, modifier: Modifier) {
    val s = d.summary
    SectionCard(
        "Route",
        Figure.ROUTE,
        modifier,
        trailing = {
            s.matchedRouteId?.let { id ->
                TextButton(onClick = { actions.onOpenRoute(id) }) { Text("Öffnen") }
            }
        },
    ) {
        when {
            d.routeName == null && d.temporaryRouteSeed -> {
                Text("Vorläufige Route wird angelegt…", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { actions.onTemporaryRoute(false) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Vorläufige Route aufheben")
                }
            }
            d.routeName == null -> {
                Text("Keiner Route zugeordnet", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = { actions.onTemporaryRoute(true) }) { Text("Als vorläufige Route verwenden") }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        d.routeName,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (d.routeTemporary) {
                        Spacer(Modifier.width(8.dp))
                        Pill("vorläufig", outlined = true)
                    }
                }
                Comparison(s.activeDurationMs, d.routeDurationsMs)
                if (d.temporaryRouteSeed) {
                    TextButton(onClick = { actions.onTemporaryRoute(false) }, contentPadding = PaddingValues(0.dp)) {
                        Text("Vorläufige Route aufheben")
                    }
                }
            }
        }
    }
}

/**
 * This recording against the route's others: its place, its distance from the
 * median, and every member as a dot on one line from the best to the slowest,
 * so that "third of twelve" is also a picture of how close third was.
 */
@Composable
private fun Comparison(mine: Long, all: List<Long>) {
    if (all.size < 2) {
        return
    }
    val sorted = all.sorted()
    val rank = sorted.indexOfFirst { it >= mine } + 1
    val median = sorted.let { if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2 }
    val delta = mine - median
    val faster = delta < 0
    val accent = if (faster) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary

    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            if (rank == 1) "Bestzeit" else "Platz $rank",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            color = if (rank == 1) accent else MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "von ${all.size}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        if (rank == 1) {
            Spacer(Modifier.width(8.dp))
            FigureGlyph(Figure.TROPHY, accent, Modifier.padding(bottom = 4.dp), size = 22.dp)
        }
    }
    Text(
        when {
            abs(delta) < 1000 -> "Genau im Median"
            faster -> "${Format.delta(delta)} schneller als der Median"
            else -> "${Format.delta(delta)} langsamer als der Median"
        },
        style = MaterialTheme.typography.bodyMedium,
        color = accent,
        fontWeight = FontWeight.Medium,
    )
    Spacer(Modifier.height(14.dp))
    val dotColor = MaterialTheme.colorScheme.outline
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val medianColor = MaterialTheme.colorScheme.onSurfaceVariant
    val halo = MaterialTheme.colorScheme.surfaceContainerLow
    Canvas(Modifier.fillMaxWidth().height(28.dp)) {
        val lo = sorted.first().toFloat()
        val hi = max(sorted.last().toFloat(), lo + 1f)
        val pad = 10.dp.toPx()
        fun x(v: Long) = pad + (v - lo) / (hi - lo) * (size.width - 2 * pad)
        val cy = size.height / 2
        drawLine(track, Offset(pad, cy), Offset(size.width - pad, cy), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
        drawLine(medianColor, Offset(x(median), cy - 10.dp.toPx()), Offset(x(median), cy + 10.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        for (v in sorted) drawCircle(dotColor, radius = 3.5.dp.toPx(), center = Offset(x(v), cy))
        drawCircle(halo, radius = 9.dp.toPx(), center = Offset(x(mine), cy))
        drawCircle(accent, radius = 7.dp.toPx(), center = Offset(x(mine), cy))
    }
    Row(Modifier.fillMaxWidth()) {
        Quiet("Beste ${Format.duration(sorted.first())}", Modifier.weight(1f))
        Quiet("Median ${Format.duration(median)}", Modifier.weight(1f), textAlign = TextAlign.Center)
        Quiet("Langsamste ${Format.duration(sorted.last())}", Modifier.weight(1f), textAlign = TextAlign.End)
    }
}

// -------------------------------------------------------------------- weather

@Composable
private fun WeatherCard(
    weather: WeatherView,
    points: List<WeatherPoint>,
    startedAtUtc: Long,
    tzOffsetMinutes: Int,
    cursorMs: Long?,
    onCursor: (Long?) -> Unit,
    actions: RecordingActions,
    modifier: Modifier,
) {
    SectionCard("Wetter", Figure.WEATHER, modifier) {
        when (weather) {
            WeatherView.Pending -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WeatherGlyph(Sky.CLOUDY, true, size = 40.dp, modifier = Modifier.clip(CircleShape))
                    Spacer(Modifier.width(14.dp))
                    Text("Wetter wird geholt…", style = MaterialTheme.typography.titleSmall)
                }
            }
            WeatherView.Off -> {
                Text("Wetter ist ausgeschaltet", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = actions.onEnableWeather) { Text("Einschalten") }
            }
            WeatherView.Unavailable -> {
                Quiet("Kein Wetter verfügbar")
            }
            is WeatherView.Ready -> WeatherReady(weather, points, startedAtUtc, tzOffsetMinutes, cursorMs, onCursor)
        }
    }
}

@Composable
private fun WeatherReady(
    weather: WeatherView.Ready,
    points: List<WeatherPoint>,
    startedAtUtc: Long,
    tzOffsetMinutes: Int,
    cursorMs: Long?,
    onCursor: (Long?) -> Unit,
) {
    val w = weather.summary
    Row(verticalAlignment = Alignment.CenterVertically) {
        w.sky?.let { WeatherGlyph(it, w.isDay, size = 56.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val label = w.code?.let(WeatherCode::label) ?: "–"
            Text(
                if (w.wetAtTimes && w.sky?.wet != true) "$label, zeitweise Niederschlag" else label,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "${Format.tempRange(w.minTempC, w.maxTempC)} · ${Format.precip(w.precipMm)} Niederschlag",
                style = MaterialTheme.typography.bodyMedium.tabular,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(14.dp))

    // The strip: one slot per ten minutes, the one under the cursor lit.
    val nearest = cursorMs?.let { c -> points.indices.minByOrNull { abs(points[it].activeMs - c) } }
    val listState = rememberLazyListState()
    LaunchedEffect(nearest) { nearest?.let { listState.animateScrollToItem((it - 2).coerceAtLeast(0)) } }
    val maxRate = max(points.maxOfOrNull { it.reading.precipMmH ?: 0.0 } ?: 0.0, 2.0)
    LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(points) { i, p ->
            WeatherSlot(
                p,
                Format.timeOfDay(startedAtUtc + p.reading.tOffsetMs, tzOffsetMinutes),
                maxRate,
                selected = i == nearest,
                onClick = { onCursor(p.activeMs) },
            )
        }
    }
    Spacer(Modifier.height(10.dp))
    WeatherChart(points, cursorMs, onScrub = { onCursor(it) })
    Spacer(Modifier.height(8.dp))
    Quiet(OpenMeteo.ATTRIBUTION)
}

@Composable
private fun WeatherSlot(p: WeatherPoint, time: String, maxRate: Double, selected: Boolean, onClick: () -> Unit) {
    val r = p.reading
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer
    Column(
        Modifier
            .width(58.dp)
            .clip(HecateDesign.InnerShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(time, style = MaterialTheme.typography.labelSmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        r.sky?.let { WeatherGlyph(it, r.isDay != false, size = 30.dp) } ?: Spacer(Modifier.size(30.dp))
        Spacer(Modifier.height(4.dp))
        Text(Format.temp(r.tempC), style = MaterialTheme.typography.titleSmall.tabular, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        // Precipitation as a little gauge: empty is dry, full is a downpour.
        val rate = r.precipMmH ?: 0.0
        Box(
            Modifier.width(8.dp).height(22.dp).clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.BottomCenter,
        ) {
            if (rate >= 0.05) {
                Box(
                    Modifier.fillMaxWidth().fillMaxHeight((rate / maxRate).toFloat().coerceIn(0.12f, 1f))
                        .background(WeatherColors.Rain),
                )
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            if (rate >= 0.05) Format.precip(rate).removeSuffix(" mm") else "–",
            style = MaterialTheme.typography.labelSmall.tabular,
            color = if (rate >= 0.05) WeatherColors.Rain else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Temperature as a line and precipitation as bars, against active time like
 * the speed and heart-rate charts below — so a shower and the slow kilometre
 * it caused line up when the cursor is on them.
 */
@Composable
private fun WeatherChart(points: List<WeatherPoint>, cursorMs: Long?, onScrub: (Long) -> Unit) {
    if (points.size < 2) return
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val cursorColor = MaterialTheme.colorScheme.onSurface
    val halo = MaterialTheme.colorScheme.surfaceContainerLow
    val first = points.first().activeMs
    val span = max(points.last().activeMs - first, 1L)
    val pad = 10f
    fun msAt(px: Float, width: Float) = first + (((px - pad) / (width - 2 * pad)).coerceIn(0f, 1f) * span).toLong()

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(84.dp)
            .pointerInput(points) { detectTapGestures(onPress = { onScrub(msAt(it.x, size.width.toFloat())) }) }
            .pointerInput(points) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume(); onScrub(msAt(change.position.x, size.width.toFloat()))
                }
            },
    ) {
        val temps = points.mapNotNull { it.reading.tempC }
        if (temps.isEmpty()) return@Canvas
        val tLo = temps.min()
        val tHi = max(temps.max(), tLo + 4.0)
        val top = 16.dp.toPx()
        val bottom = size.height - 14.dp.toPx()
        val w = size.width - 2 * pad
        fun x(ms: Long) = pad + (ms - first).toFloat() / span * w
        fun y(t: Double) = bottom - ((t - tLo) / (tHi - tLo)).toFloat() * (bottom - top)

        // Bars first, under the line, scaled so that 4 mm/h fills the chart.
        val maxRate = max(points.maxOf { it.reading.precipMmH ?: 0.0 }, 4.0)
        val barW = (w / points.size * 0.4f).coerceIn(3f, 8.dp.toPx())
        drawLine(labelColor.copy(alpha = .25f), Offset(pad, size.height - .5f), Offset(size.width - pad, size.height - .5f), strokeWidth = 1f)
        for (p in points) {
            val rate = p.reading.precipMmH ?: continue
            if (rate < 0.05) continue
            val h = (rate / maxRate).toFloat() * (size.height - top)
            drawRoundRect(
                WeatherColors.Rain.copy(alpha = 0.55f),
                topLeft = Offset(x(p.activeMs) - barW / 2, size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(2.dp.toPx()),
            )
        }

        val line = Path()
        val area = Path()
        var started = false
        for (p in points) {
            val t = p.reading.tempC ?: continue
            val o = Offset(x(p.activeMs), y(t))
            if (!started) { line.moveTo(o.x, o.y); area.moveTo(o.x, bottom); area.lineTo(o.x, o.y); started = true }
            else { line.lineTo(o.x, o.y); area.lineTo(o.x, o.y) }
        }
        val lastX = x(points.last { it.reading.tempC != null }.activeMs)
        area.lineTo(lastX, bottom)
        area.close()
        drawPath(area, Brush.verticalGradient(listOf(WeatherColors.Temperature.copy(alpha = .28f), Color.Transparent), startY = top, endY = bottom))
        drawPath(line, WeatherColors.Temperature, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

        val hi = points.filter { it.reading.tempC != null }.maxBy { it.reading.tempC!! }
        val text = measurer.measure(Format.temp(hi.reading.tempC), labelStyle)
        val hx = (x(hi.activeMs) - text.size.width / 2).coerceIn(0f, size.width - text.size.width)
        drawText(text, color = labelColor, topLeft = Offset(hx, y(hi.reading.tempC!!) - text.size.height - 2.dp.toPx()))

        cursorMs?.let { c ->
            val cx = x(c.coerceIn(first, first + span))
            drawLine(cursorColor.copy(alpha = .35f), Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1.5.dp.toPx())
            val near = points.filter { it.reading.tempC != null }.minByOrNull { abs(it.activeMs - c) }
            near?.reading?.tempC?.let {
                drawCircle(halo, radius = 7.dp.toPx(), center = Offset(cx, y(it)))
                drawCircle(WeatherColors.Temperature, radius = 5.dp.toPx(), center = Offset(cx, y(it)))
            }
        }
    }
}

// --------------------------------------------------------------------- charts

@Composable
private fun ChartsCard(d: SessionDetail, peakHr: Int, cursorMs: Long?, onCursor: (Long?) -> Unit, modifier: Modifier) {
    val hasAny = d.elevation.size >= 2 || d.speed.size >= 2 || d.heartRate.size >= 2
    if (!hasAny) return
    val moment = cursorMs?.let { Analysis.atTime(d.moments, it) }
    SectionCard(
        "Verlauf",
        Figure.CHART,
        modifier,
    ) {
        if (d.elevation.size >= 2 && d.elevationStats != null) {
            ChartTitle("Höhe", "${Format.elevation(d.elevation.minOf { it.altM })} – ${Format.elevation(d.elevation.maxOf { it.altM })}")
            Chart(
                d.elevation.asChart(),
                ChartKind.ELEVATION,
                peakHrBpm = peakHr,
                cursorX = moment?.distanceM,
                onScrub = { dist -> Analysis.atDistance(d.moments, dist)?.let { onCursor(it.activeMs) } },
            )
        } else {
            Quiet("Kein Höhenprofil")
        }
        if (d.speed.size >= 2 && d.speedStats != null) {
            Spacer(Modifier.height(10.dp))
            ChartTitle("Tempo", "${Format.speed(d.speedStats.min)} – ${Format.speedLabel(d.speedStats.max)}")
            Chart(d.speed.asChart(), ChartKind.SPEED, peakHrBpm = peakHr, cursorX = cursorMs?.toDouble(), onScrub = { onCursor(it.toLong()) })
        }
        if (d.heartRate.size >= 2 && d.heartRateStats != null) {
            Spacer(Modifier.height(10.dp))
            ChartTitle("Puls", "${Format.hr(d.heartRateStats.min)} – ${Format.hr(d.heartRateStats.max)} bpm")
            Chart(d.heartRate.asChart(), ChartKind.HEART_RATE, peakHrBpm = peakHr, cursorX = cursorMs?.toDouble(), onScrub = { onCursor(it.toLong()) })
        }
        IntensityLegend(d.geometry, d.elevation, d.speed)
    }
}

@Composable
private fun ChartTitle(title: String, range: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(range, style = MaterialTheme.typography.labelMedium.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Everything measured at the cursor, and where it was. Slides in over the top
 * of the screen while a moment is picked; the cross puts it away.
 */
@Composable
private fun CursorPanel(
    d: SessionDetail,
    m: Analysis.Moment,
    map: MapContent?,
    weather: List<WeatherPoint>,
    onClose: () -> Unit,
) {
    val speed = d.speed.minByOrNull { abs(it.tMs - m.activeMs) }?.takeIf { abs(it.tMs - m.activeMs) < 60_000 }
    val w = weather.minByOrNull { abs(it.activeMs - m.activeMs) }?.reading
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        shape = HecateDesign.CardShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (map != null) {
                Box(Modifier.size(104.dp).clip(HecateDesign.InnerShape)) {
                    HecateMap(map, Modifier.fillMaxSize(), cursor = LatLon(m.lat, m.lon))
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        Format.duration(m.activeMs),
                        style = MaterialTheme.typography.titleLarge.tabular,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        Format.distance(m.distanceM),
                        style = MaterialTheme.typography.titleSmall.tabular,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Cursor entfernen", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Reading(Figure.SPEED, speed?.let { Format.speedLabel(it.value) } ?: "–", Modifier.weight(1f))
                    Reading(Figure.HEART, m.hrBpm?.let { "${Format.hr(it)} bpm" } ?: "–", Modifier.weight(1f))
                }
                Row {
                    Reading(Figure.MOUNTAIN, m.altM?.let { Format.elevation(it) } ?: "–", Modifier.weight(1f))
                    Row(Modifier.weight(1f).padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (w?.sky != null) {
                            WeatherGlyph(w.sky!!, w.isDay != false, size = 16.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                Format.temp(w.tempC) + (Format.precipRate(w.precipMmH).takeIf { it.isNotEmpty() }?.let { " · $it" } ?: ""),
                                style = MaterialTheme.typography.bodyMedium.tabular,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Reading(figure: Figure, text: String, modifier: Modifier) {
    Row(modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        FigureGlyph(figure, MaterialTheme.colorScheme.primary, size = 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium.tabular)
    }
}

// ---------------------------------------------------------------------- zones

/**
 * Time in each effort band, as one bar and four rows: how the hour was spent,
 * which the coloured line says only to someone who adds up colours by eye.
 */
@Composable
private fun ZonesCard(d: SessionDetail, peakHr: Int, modifier: Modifier) {
    val zones = remember(d.charted, peakHr) { Analysis.timeInZones(d.charted, peakHr) }
    if (zones.isEmpty()) return
    val total = zones.values.sum().coerceAtLeast(1)
    SectionCard("Belastung", Figure.ZONES, modifier, trailing = { Quiet("Max. $peakHr bpm") }) {
        Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp))) {
            for (band in Intensity.entries) {
                val f = zones.getValue(band).toFloat() / total
                if (f > 0f) Box(Modifier.weight(f).fillMaxHeight().background(HecateIntensityColors.of(band)))
            }
        }
        Spacer(Modifier.height(12.dp))
        for (band in Intensity.entries.reversed()) {
            val ms = zones.getValue(band)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(HecateIntensityColors.of(band), CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(band.label, style = MaterialTheme.typography.bodyMedium)
                    Quiet(
                        if (band == Intensity.EASY) "unter ${Format.hr(Intensity.MODERATE.lowerFraction * peakHr)} bpm"
                        else "ab ${Format.hr(band.lowerFraction * peakHr)} bpm",
                    )
                }
                Text(
                    Format.duration(ms),
                    style = MaterialTheme.typography.bodyMedium.tabular,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "${(ms * 100 / total)} %",
                    style = MaterialTheme.typography.bodySmall.tabular,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(48.dp),
                )
            }
        }
    }
}

// --------------------------------------------------------------------- splits

/**
 * Kilometre by kilometre (five on a long ride): time, speed as a bar against
 * the fastest, effort as the bar's colour, the net climb. The fastest gets a
 * trophy, because finding it is why anybody reads a splits table.
 */
@Composable
private fun SplitsCard(d: SessionDetail, peakHr: Int, modifier: Modifier) {
    val splits = d.splits
    if (splits.size < 2) return
    val full = splits.filterNot { it.partial }
    val fastest = full.minByOrNull { it.durationMs.toDouble() / it.distanceM }
    val maxKmh = splits.maxOf { it.kmh ?: 0.0 }.coerceAtLeast(1.0)
    val minKmh = splits.minOf { it.kmh ?: maxKmh }
    val lengthKm = (splits.first().toM - splits.first().fromM) / 1000
    SectionCard("Abschnitte", Figure.SPLITS, modifier, trailing = { Quiet("je ${Format.splitKm(lengthKm * 1000)}") }) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            SplitHeader("km", Modifier.width(40.dp))
            SplitHeader("Zeit", Modifier.width(58.dp))
            SplitHeader("km/h", Modifier.weight(1f))
            SplitHeader("Puls", Modifier.width(44.dp), TextAlign.End)
            SplitHeader("Höhe", Modifier.width(52.dp), TextAlign.End)
        }
        for (split in splits) {
            val kmh = split.kmh ?: 0.0
            val band = Intensity.of(split.avgHrBpm, peakHr)
            val color = band?.let(HecateIntensityColors::of) ?: MaterialTheme.colorScheme.primary
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (split.partial) Format.splitKm(split.toM).removeSuffix(" km") else Format.splitKm(split.toM).removeSuffix(" km"),
                    style = MaterialTheme.typography.bodyMedium.tabular,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp),
                )
                Text(
                    Format.duration(split.durationMs),
                    style = MaterialTheme.typography.bodyMedium.tabular,
                    fontWeight = if (split == fastest) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.width(58.dp),
                )
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    // Bars start at a floor below the slowest split rather than
                    // at zero, so that 11.2 and 11.9 km/h look different.
                    val floor = minKmh * 0.8
                    val f = ((kmh - floor) / (maxKmh - floor)).toFloat().coerceIn(0.08f, 1f)
                    Box(
                        Modifier.weight(f).height(16.dp).clip(RoundedCornerShape(8.dp))
                            .background(Brush.horizontalGradient(listOf(color.copy(alpha = .55f), color))),
                    )
                    if (f < 1f) Spacer(Modifier.weight(1f - f))
                    Spacer(Modifier.width(6.dp))
                    Text(Format.speed(kmh), style = MaterialTheme.typography.labelMedium.tabular, modifier = Modifier.width(34.dp))
                    if (split == fastest) FigureGlyph(Figure.TROPHY, MaterialTheme.colorScheme.primary, size = 14.dp)
                    else Spacer(Modifier.width(14.dp))
                }
                Text(
                    Format.hr(split.avgHrBpm),
                    style = MaterialTheme.typography.bodyMedium.tabular,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(44.dp),
                )
                Text(
                    split.netAltM?.let { Format.elevationSigned(it) } ?: "–",
                    style = MaterialTheme.typography.bodySmall.tabular,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(52.dp),
                )
            }
        }
    }
}

@Composable
private fun SplitHeader(text: String, modifier: Modifier, align: TextAlign = TextAlign.Start) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier, textAlign = align)
}

// -------------------------------------------------------------------- details

@Composable
private fun DetailsCard(d: SessionDetail, actions: RecordingActions, onEditType: () -> Unit, modifier: Modifier) {
    val s = d.summary
    SectionCard("Details", Figure.CALENDAR, modifier) {
        // The one fact on this screen that can be corrected: the button
        // pressed at the trailhead.
        Row(
            Modifier.fillMaxWidth().clip(HecateDesign.InnerShape).clickable(onClick = onEditType).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Art", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            ActivityIcon(s.exerciseType, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.width(6.dp))
            Text(s.exerciseType.label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Filled.Edit, contentDescription = "Art ändern", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        }
        KeyValue("Beginn", Format.dateTime(s.startedAtUtc, s.tzOffsetMinutes))
        KeyValue("Quelle", Format.source(s.source))
        KeyValue("Health Connect", Format.hcState(d.hcState))
        d.hcError?.let { Quiet(it) }
        if (d.hcState == HcWriteState.FAILED || d.hcState == HcWriteState.PENDING) {
            TextButton(onClick = actions.onRetryHealthConnect, contentPadding = PaddingValues(0.dp)) { Text("Erneut schreiben") }
        }
        Spacer(Modifier.height(4.dp))
        // What the watch's matcher did, and what the recording logged along
        // the way: worth having when a match looks wrong, and noise the rest of
        // the time — so it is one tap away, and not in the way.
        CollapsibleSection("Technische Details") {
            KeyValue("Festlegung bei", d.committedAtM?.let { Format.distance(it) } ?: "–")
            // Blank for an import: it has no match log, and the route came
            // from the clusterer afterwards rather than from a decision.
            KeyValue(
                "Festlegung durch",
                when (d.committedByPriority) {
                    null -> "–"
                    true -> "Priorität"
                    false -> "Spurverlauf"
                },
            )
            KeyValue("Deckung", Format.coverage(d.coverage))
            KeyValue("Match-Log", "${d.matchLogSize} Einträge")
            if (d.matchEvents.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                MonoCard(
                    d.matchEvents.map { e ->
                        "%6.0fs  %-16s %s".format(e.tOffsetMs / 1000.0, e.event.name, e.routeId?.toString() ?: "")
                    },
                )
            }
            if (d.sessionEvents.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                SectionHeader("Verlauf der Aufzeichnung")
                Spacer(Modifier.height(4.dp))
                MonoCard(
                    d.sessionEvents.map { e ->
                        "%6.0fs  %-18s %s".format(e.tOffsetMs / 1000.0, e.type.name, e.note.orEmpty())
                    },
                )
            }
        }
    }
}

/**
 * The four intensity bands and their names, under charts that are coloured by
 * them. Only where there is a heart rate to have coloured anything: a legend
 * for four colours that are not on the screen is an invitation to look for
 * them.
 */
@Composable
internal fun IntensityLegend(
    geometry: List<TrackPoint>,
    elevation: List<de.hecate.core.profile.Elevation.Point>,
    speed: List<Timeline.Point>,
) {
    val visible = geometry.any { it.hrBpm != null } ||
        elevation.any { it.hrBpm != null } ||
        speed.any { it.hrBpm != null }
    if (!visible) return
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (band in Intensity.entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(HecateIntensityColors.of(band), CircleShape))
                Spacer(Modifier.width(5.dp))
                Text(band.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
