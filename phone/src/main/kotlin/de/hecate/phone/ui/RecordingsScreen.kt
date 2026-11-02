package de.hecate.phone.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hecate.core.model.ExerciseType
import de.hecate.core.weather.WeatherCode
import de.hecate.phone.R
import de.hecate.phone.db.SessionSummary
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.max

@Composable
fun RecordingsScreen(vm: HecateViewModel) {
    val all by vm.sessions.collectAsStateWithLifecycle()
    val sessions by vm.filteredSessions.collectAsStateWithLifecycle()
    val types by vm.activityTypes.collectAsStateWithLifecycle()
    val filter by vm.activityFilter.collectAsStateWithLifecycle()
    val standouts by vm.standouts.collectAsStateWithLifecycle()
    val loaded by vm.sessionsLoaded.collectAsStateWithLifecycle()
    // Nothing rather than the empty-state text while the first query is out.
    if (!loaded) return
    RecordingsContent(
        all, sessions, types, filter, standouts,
        onFilter = { vm.setActivityFilter(it) },
        onOpen = { vm.show(Screen.SessionDetail(it)) },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RecordingsContent(
    all: List<SessionSummary>,
    sessions: List<SessionSummary>,
    types: List<ExerciseType>,
    filter: ExerciseType?,
    standouts: Map<String, Standout>,
    onFilter: (ExerciseType?) -> Unit,
    onOpen: (String) -> Unit,
    nowUtcMs: Long = System.currentTimeMillis(),
) {
    if (all.isEmpty()) {
        Empty("Noch keine Aufzeichnungen", Figure.ROUTE)
        return
    }
    val months = remember(sessions) {
        sessions.groupBy { Format.monthKey(it.startedAtUtc, it.tzOffsetMinutes) }.toList()
    }
    Column(Modifier.fillMaxSize()) {
        // The filter never empties the list: an activity is only offered while
        // it occurs — see [HecateViewModel.activityFilter]. Pinned above the
        // list, because a control that has scrolled away has to be hunted for
        // after exactly the scrolling that makes one want it.
        ActivityFilterRow(types, filter, onFilter)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "week") { WeekCard(sessions, nowUtcMs, Modifier.appear(0)) }
            for ((_, rows) in months) {
                val first = rows.first()
                stickyHeader(key = "m${Format.monthKey(first.startedAtUtc, first.tzOffsetMinutes)}") {
                    MonthHeader(first, rows)
                }
                items(rows, key = { it.id }) { s ->
                    // Against the whole history, not against what is on screen:
                    // narrowing the list does not change which recordings are
                    // fast for their route.
                    RecordingRow(s, standouts[s.id] ?: Standout(), nowUtcMs) { onOpen(s.id) }
                }
            }
        }
    }
}

/**
 * The activity filter above the list: only the activities that occur, and
 * nothing at all when there is one kind of recording to filter — a filter
 * with one option and an "Alle" beside it is two ways of saying the same thing.
 */
@Composable
private fun ActivityFilterRow(types: List<ExerciseType>, selected: ExerciseType?, onSelect: (ExerciseType?) -> Unit) {
    if (types.size < 2) return
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text("Alle") })
        }
        items(types, key = { it.name }) { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(if (selected == type) null else type) },
                label = { Text(type.label) },
                leadingIcon = { ActivityIcon(type, Modifier.size(18.dp)) },
            )
        }
    }
}

/**
 * This week at a glance, and the eleven before it as bars: how much, and
 * whether it is more or less than usual — the one summary a list of single
 * recordings cannot give.
 */
@Composable
private fun WeekCard(sessions: List<SessionSummary>, nowUtcMs: Long, modifier: Modifier) {
    val tz = sessions.firstOrNull()?.tzOffsetMinutes ?: 0
    val weeks = remember(sessions, nowUtcMs) { weeklyKm(sessions, nowUtcMs, tz, WEEKS) }
    val thisWeek = remember(sessions, nowUtcMs) {
        val start = weekStart(nowUtcMs, tz)
        sessions.filter { it.startedAtUtc >= start }
    }
    val km = thisWeek.sumOf { it.totalDistanceM } / 1000
    val time = thisWeek.sumOf { it.activeDurationMs }
    val last = weeks.getOrNull(WEEKS - 2) ?: 0.0
    HecateCard(modifier) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Diese Woche", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        String.format(java.util.Locale.GERMANY, "%.1f", km),
                        style = MaterialTheme.typography.displaySmall.tabular,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("km", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
                }
                Quiet(
                    "${thisWeek.size} ${if (thisWeek.size == 1) "Aufzeichnung" else "Aufzeichnungen"} · ${Format.duration(time)} aktiv",
                )
            }
            if (last > 0.0) {
                val diff = km - last
                Pill(
                    (if (diff >= 0) "+" else "−") + String.format(java.util.Locale.GERMANY, "%.1f km", kotlin.math.abs(diff)),
                    container = if (diff >= 0) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                    content = if (diff >= 0) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        val current = MaterialTheme.colorScheme.primary
        val past = MaterialTheme.colorScheme.primary.copy(alpha = 0.28f)
        val empty = MaterialTheme.colorScheme.surfaceContainerHighest
        Canvas(Modifier.fillMaxWidth().height(56.dp)) {
            val top = max(weeks.max(), 1.0)
            val gap = 6.dp.toPx()
            val w = (size.width - gap * (weeks.size - 1)) / weeks.size
            weeks.forEachIndexed { i, v ->
                val h = if (v <= 0) 4.dp.toPx() else max((v / top).toFloat() * size.height, 6.dp.toPx())
                drawRoundRect(
                    if (v <= 0) empty else if (i == weeks.size - 1) current else past,
                    topLeft = Offset(i * (w + gap), size.height - h),
                    size = Size(w, h),
                    cornerRadius = CornerRadius(w / 3),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Quiet("vor ${WEEKS - 1} Wochen", Modifier.weight(1f))
            Quiet("jetzt")
        }
    }
}

private const val WEEKS = 12

/** Monday 00:00 of the week [utcMs] falls in, on the recording's own clock. */
private fun weekStart(utcMs: Long, tzOffsetMinutes: Int): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), java.util.Locale.GERMANY)
    cal.timeInMillis = utcMs + tzOffsetMinutes * 60_000L
    cal.firstDayOfWeek = Calendar.MONDAY
    cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
    while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) cal.add(Calendar.DAY_OF_MONTH, -1)
    return cal.timeInMillis - tzOffsetMinutes * 60_000L
}

/** Kilometres per week for the last [weeks] weeks, oldest first. */
internal fun weeklyKm(sessions: List<SessionSummary>, nowUtcMs: Long, tz: Int, weeks: Int): List<Double> {
    val thisWeek = weekStart(nowUtcMs, tz)
    val week = 7 * 24 * 3_600_000L
    val out = DoubleArray(weeks)
    for (s in sessions) {
        val ago = Math.floorDiv(thisWeek - weekStart(s.startedAtUtc, tz), week).toInt()
        if (ago in 0 until weeks) out[weeks - 1 - ago] += s.totalDistanceM / 1000
    }
    return out.toList()
}

@Composable
private fun MonthHeader(first: SessionSummary, rows: List<SessionSummary>) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 4.dp, end = 4.dp, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            Format.month(first.startedAtUtc, first.tzOffsetMinutes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Quiet(
            "${rows.size} ${if (rows.size == 1) "Aufzeichnung" else "Aufzeichnungen"} · " +
                String.format(java.util.Locale.GERMANY, "%.0f km", rows.sumOf { it.totalDistanceM } / 1000),
        )
    }
}

@Composable
private fun RecordingRow(s: SessionSummary, standout: Standout, nowUtcMs: Long, onClick: () -> Unit) {
    HecateCard(onClick = onClick, padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActivityBadge(s.exerciseType)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    Format.title(s.exerciseType, s.startedAtUtc, s.tzOffsetMinutes),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Quiet(Format.dayAndTime(s.startedAtUtc, s.tzOffsetMinutes, nowUtcMs))
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Format.distance(s.totalDistanceM),
                    style = MaterialTheme.typography.titleMedium.tabular,
                    fontWeight = FontWeight.SemiBold,
                )
                Quiet(Format.duration(s.activeDurationMs))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // A matched route is the whole point of the app, so it gets a
            // filled pill; a temporary one only an outline, so that one promoted
            // walk does not read as an established route.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                when {
                    s.routeName != null -> Pill(Format.routeName(s.routeName), outlined = s.routeTemporary)
                    else -> Quiet(if (s.source.isImport) "importiert" else "keine Route")
                }
            }
            Standing(Format.speed(Format.speedFrom(s.totalDistanceM, s.activeDurationMs)) + " km/h", standout.fast, MaterialTheme.colorScheme.primary)
            s.avgHr?.let {
                Spacer(Modifier.width(10.dp))
                Standing("${Format.hr(it)} bpm", standout.highHr, MaterialTheme.colorScheme.tertiary)
            }
            s.weatherCode?.let { code ->
                Spacer(Modifier.width(10.dp))
                WeatherGlyph(WeatherCode.sky(code), s.weatherIsDay != false, size = 18.dp)
                Spacer(Modifier.width(3.dp))
                Text(Format.temp(s.tempMaxC), style = MaterialTheme.typography.bodySmall.tabular, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * One figure in a recording row, emphasised when it is in the top of the range
 * for its route (US-8). Colour, weight **and** an arrow, never colour alone:
 * the two marks sit side by side and must be tellable apart by someone who
 * cannot separate the hues. See [Standouts.PERCENTILE].
 */
@Composable
private fun Standing(text: String, standout: Boolean, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall.tabular,
            color = if (standout) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (standout) FontWeight.Bold else FontWeight.Normal,
        )
        if (standout) {
            Icon(
                painter = painterResource(R.drawable.ic_arrow_up),
                contentDescription = "über dem Üblichen für diese Route",
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
