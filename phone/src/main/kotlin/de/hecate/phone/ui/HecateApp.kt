package de.hecate.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hecate.phone.db.HcWriteState
import de.hecate.phone.db.RouteSummary
import de.hecate.phone.db.SessionSummary

/**
 * §5.4: Sessions list → Session detail; Routes list → Route detail.
 *
 * Four screens, one state enum, no navigation library (§4). The bottom bar
 * switches between the two lists; the system back button leaves a detail.
 *
 * Every colour below is a Material 3 scheme role, never a literal — see
 * [HecateTheme]. The two exceptions are the start and end markers on the route
 * drawing, which mean something and therefore cannot move with the wallpaper.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HecateApp(
    vm: HecateViewModel,
    onPickTcx: () -> Unit,
    onExportArchive: () -> Unit,
    onRestoreArchive: () -> Unit,
    onRequestHealthConnect: () -> Unit,
) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(status) {
        status?.let {
            snackbar.showSnackbar(it)
            vm.clearStatus()
        }
    }

    val inDetail = screen is Screen.RouteDetail || screen is Screen.SessionDetail
    BackHandler(enabled = inDetail) { vm.back() }

    // exitUntilCollapsed is the behaviour a two-row bar is built for: the tall
    // title shrinks into the short one and stays, rather than the whole bar
    // leaving the screen. Its state is shared by all four screens, so it is reset
    // on every screen change — otherwise a list left scrolled down, followed by a
    // detour into a detail, comes back to a collapsed bar above a list that is
    // already at the top, and the title is simply missing.
    val topBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(topBarState)
    LaunchedEffect(screen) {
        topBarState.heightOffset = 0f
        topBarState.contentOffset = 0f
    }

    HecateTheme {
        Scaffold(
            modifier = if (inDetail) Modifier
            else Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                if (inDetail) {
                    TopAppBar(
                        title = { Text(titleOf(screen)) },
                        navigationIcon = {
                            IconButton(onClick = { vm.back() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                            }
                        },
                    )
                } else {
                    // The two-row bar: a large title that shrinks into a small
                    // one as the list scrolls under it.
                    MediumTopAppBar(
                        title = { Text(titleOf(screen)) },
                        actions = { ListActions(screen, vm, onPickTcx, onExportArchive, onRestoreArchive, onRequestHealthConnect) },
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
            bottomBar = {
                if (!inDetail) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = screen is Screen.Sessions,
                            onClick = { vm.show(Screen.Sessions) },
                            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                            label = { Text("Sitzungen") },
                        )
                        NavigationBarItem(
                            selected = screen is Screen.Routes,
                            onClick = { vm.show(Screen.Routes) },
                            icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                            label = { Text("Routen") },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (screen) {
                    is Screen.Sessions -> SessionsScreen(vm)
                    is Screen.Routes -> RoutesScreen(vm)
                    is Screen.SessionDetail -> SessionDetailScreen(vm)
                    is Screen.RouteDetail -> RouteDetailScreen(vm)
                }
                if (busy) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            }
        }
    }
}

/** The top-bar actions, which differ per list screen. */
@Composable
private fun ListActions(
    screen: Screen,
    vm: HecateViewModel,
    onPickTcx: () -> Unit,
    onExportArchive: () -> Unit,
    onRestoreArchive: () -> Unit,
    onRequestHealthConnect: () -> Unit,
) {
    when (screen) {
        // Four actions is past what fits across a phone top bar, so they live
        // behind one button.
        is Screen.Sessions -> {
            var open by remember { mutableStateOf(false) }
            IconButton(onClick = { open = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Weitere Aktionen")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text("TCX importieren") },
                    onClick = { open = false; onPickTcx() },
                )
                DropdownMenuItem(
                    text = { Text("Sicherung exportieren") },
                    onClick = { open = false; onExportArchive() },
                )
                DropdownMenuItem(
                    text = { Text("Sicherung einlesen") },
                    onClick = { open = false; onRestoreArchive() },
                )
                DropdownMenuItem(
                    text = { Text("Health Connect einrichten") },
                    onClick = { open = false; onRequestHealthConnect() },
                )
            }
        }
        is Screen.Routes -> IconButton(onClick = { vm.recluster() }) {
            Icon(Icons.Filled.Refresh, contentDescription = "Neu gruppieren")
        }
        else -> Unit
    }
}

private fun titleOf(screen: Screen): String = when (screen) {
    is Screen.Sessions -> "Sitzungen"
    is Screen.Routes -> "Routen"
    is Screen.SessionDetail -> "Sitzung"
    is Screen.RouteDetail -> "Route"
}

// ------------------------------------------------------------------- sessions

@Composable
private fun SessionsScreen(vm: HecateViewModel) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    if (sessions.isEmpty()) {
        Empty(
            "Noch keine Sitzungen.\n\nAufzeichnungen der Uhr erscheinen hier automatisch. " +
                "Über „Import“ lassen sich TCX-Dateien aus Google Health einlesen, " +
                "damit Routen sofort gelernt werden.",
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(sessions, key = { it.id }) { s ->
            SessionRow(s) { vm.show(Screen.SessionDetail(s.id)) }
        }
    }
}

@Composable
private fun SessionRow(s: SessionSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp, 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    Format.dateTime(s.startedAtUtc, s.tzOffsetMinutes),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(Format.distance(s.totalDistanceM), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    buildString {
                        append(Format.exerciseType(s.exerciseType))
                        append(" · ")
                        append(Format.duration(s.activeDurationMs))
                        s.avgHr?.let { append(" · ${Format.hr(it)} bpm") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // A matched route is the whole point of the app, so it gets a
                // filled pill rather than another line of grey text.
                s.routeName?.let { RoutePill(it) }
                    ?: Text(
                        if (s.source == "IMPORT_TCX") "importiert" else "keine Route",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
    }
}

/** The name of the route a session was matched to. */
@Composable
private fun RoutePill(name: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SessionDetailScreen(vm: HecateViewModel) {
    val detail by vm.sessionDetail.collectAsStateWithLifecycle()
    val d = detail ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(
            Format.dateTime(d.summary.startedAtUtc, d.summary.tzOffsetMinutes),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(12.dp))
        PolylineView(d.geometry)
        Spacer(Modifier.height(12.dp))

        KeyValue("Art", Format.exerciseType(d.summary.exerciseType))
        KeyValue("Dauer (aktiv)", Format.duration(d.summary.activeDurationMs))
        KeyValue("Distanz", Format.distance(d.summary.totalDistanceM))
        KeyValue("Ø Herzfrequenz", "${Format.hr(d.summary.avgHr)} bpm")
        KeyValue(
            "Ø Geschwindigkeit",
            Format.speed(
                Format.speedFrom(d.summary.totalDistanceM, d.summary.activeDurationMs),
            ) + " km/h",
        )
        KeyValue("Quelle", if (d.summary.source == "IMPORT_TCX") "TCX-Import" else "Uhr")

        Spacer(Modifier.height(16.dp))
        SectionHeader("Zuordnung")
        KeyValue("Route", d.routeName ?: "keine")
        KeyValue("Festlegung bei", d.committedAtM?.let { Format.distance(it) } ?: "–")
        KeyValue("Festlegung durch", if (d.committedByPriority) "Priorität" else "Spurverlauf")
        KeyValue("Deckung", d.coverage?.let { String.format(java.util.Locale.GERMANY, "%.2f", it) } ?: "–")
        KeyValue("Match-Log", "${d.matchLogSize} Einträge")

        if (d.matchEvents.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(10.dp)) {
                    for (e in d.matchEvents) {
                        Text(
                            "%6.0fs  %-16s %s".format(
                                e.tOffsetMs / 1000.0, e.event.name, e.routeId?.toString() ?: "",
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }

        if (d.sessionEvents.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionHeader("Verlauf der Aufzeichnung")
            Spacer(Modifier.height(4.dp))
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(Modifier.padding(10.dp)) {
                    for (e in d.sessionEvents) {
                        Text(
                            "%6.0fs  %-18s %s".format(
                                e.tOffsetMs / 1000.0, e.type.name, e.note.orEmpty(),
                            ),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("Health Connect")
        KeyValue("Status", Format.hcState(d.hcState))
        d.hcError?.let { KeyValue("Fehler", it) }
        if (d.hcState == HcWriteState.FAILED || d.hcState == HcWriteState.PENDING) {
            TextButton(onClick = { vm.retryHealthConnect() }) { Text("Erneut schreiben") }
        }

        Spacer(Modifier.height(20.dp))
        TextButton(
            onClick = { vm.deleteSession(d.summary.id) },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Sitzung löschen")
        }
        Spacer(Modifier.height(24.dp))
    }
}

// --------------------------------------------------------------------- routes

@Composable
private fun RoutesScreen(vm: HecateViewModel) {
    val routes by vm.routes.collectAsStateWithLifecycle()
    if (routes.isEmpty()) {
        Empty(
            "Noch keine Routen.\n\nEine Route entsteht, sobald drei ähnliche Aufzeichnungen " +
                "vorliegen. Bis dahin hilft der TCX-Import auf der Sitzungsseite.",
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(routes, key = { it.id }) { r ->
            RouteRow(r) { vm.show(Screen.RouteDetail(r.id)) }
        }
    }
}

@Composable
private fun RouteRow(r: RouteSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp, 12.dp)) {
            Text(r.name, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "${Format.exerciseType(r.exerciseType)} · ${Format.distance(r.lengthM)} · ${r.memberCount} Aufzeichnungen",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                buildString {
                    append("Median ")
                    append(r.medianDurationMs?.let { Format.duration(it) } ?: "–")
                    append(" · Beste ")
                    append(r.bestDurationMs?.let { Format.duration(it) } ?: "–")
                    // Only worth the pixels when the user has actually said something.
                    if (r.priority != de.hecate.core.Tuning.DEFAULT_ROUTE_PRIORITY) {
                        append(" · Prio ${r.priority}")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RouteDetailScreen(vm: HecateViewModel) {
    val detail by vm.routeDetail.collectAsStateWithLifecycle()
    val d = detail ?: return
    var editing by remember { mutableStateOf(false) }
    var draft by remember(d.id) { mutableStateOf(d.name) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (editing) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { vm.rename(d.id, draft); editing = false }) {
                    Icon(Icons.Filled.Check, contentDescription = "Name übernehmen")
                }
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(d.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { editing = true }) {
                    Icon(Icons.Filled.Edit, contentDescription = "Umbenennen")
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        PolylineView(d.geometry)
        Spacer(Modifier.height(16.dp))

        PriorityPicker(d.priority) { vm.setPriority(d.id, it) }
        Spacer(Modifier.height(16.dp))

        KeyValue("Art", Format.exerciseType(d.exerciseType))
        KeyValue("Länge", Format.distance(d.lengthM))
        KeyValue("Aufzeichnungen", d.memberCount.toString())
        KeyValue("Median-Zeit", d.medianDurationMs?.let { Format.duration(it) } ?: "–")
        KeyValue("Beste Zeit", d.bestDurationMs?.let { Format.duration(it) } ?: "–")
        KeyValue("Schlechteste", d.worstDurationMs?.let { Format.duration(it) } ?: "–")

        if (d.splits.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            // The splits are stored as the seconds each kilometre took, which is
            // the natural measurement; km/h is what gets shown. "Beste" is still
            // the fastest kilometre — the smallest time is the largest speed.
            SectionHeader("Kilometer-Splits (km/h)")
            Spacer(Modifier.height(4.dp))
            MonoRow("km", "Median", "Beste", "Schlecht.", header = true)
            for (s in d.splits) {
                MonoRow(
                    s.km.toString(),
                    Format.speed(Format.speedOverKm(s.medianS)),
                    Format.speed(Format.speedOverKm(s.bestS)),
                    Format.speed(Format.speedOverKm(s.worstS)),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionHeader("Aufzeichnungen")
        Spacer(Modifier.height(4.dp))
        MonoRow("Datum", "Zeit", "Dauer", "Ø HF", header = true)
        for (s in d.sessions) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.show(Screen.SessionDetail(s.id)) },
            ) {
                MonoRow(
                    Format.date(s.startedAtUtc, s.tzOffsetMinutes),
                    Format.timeOfDay(s.startedAtUtc, s.tzOffsetMinutes),
                    Format.duration(s.activeDurationMs),
                    Format.hr(s.avgHr),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------- bits

/**
 * The manual route ranking, 1..10.
 *
 * Only matters where two routes share the same ground and the matcher therefore
 * refuses to choose between them (US-4). Raise one above the other and the watch
 * will assume that one — with its name, its remaining distance and its ETA —
 * from the start, instead of showing "Suche…" until the paths part. Leaving
 * everything at 5 keeps the cautious behaviour.
 *
 * Two segmented rows of five rather than one of ten: ten segments across a phone
 * squeezes each below the 48dp touch target, and the break at five gives the
 * scale a middle you can aim for without counting.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriorityPicker(current: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                "Priorität",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("$current", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(6.dp))
        for (row in 0..1) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                for (i in 0..4) {
                    val v = row * 5 + i + 1
                    SegmentedButton(
                        selected = v == current,
                        onClick = { onChange(v) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 5),
                    ) { Text("$v") }
                }
            }
            if (row == 0) Spacer(Modifier.height(4.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Entscheidet nur zwischen Routen, die sich denselben Weg teilen. " +
                "Bei Gleichstand bleibt die Anzeige auf „Suche…“.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            key,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MonoRow(a: String, b: String, c: String, d: String, header: Boolean = false) {
    val style = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
    )
    val color =
        if (header) MaterialTheme.colorScheme.onSurfaceVariant
        else MaterialTheme.colorScheme.onSurface
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(a, style = style, color = color, modifier = Modifier.weight(1.3f))
        Text(b, style = style, color = color, modifier = Modifier.weight(1f))
        Text(c, style = style, color = color, modifier = Modifier.weight(1f))
        Text(d, style = style, color = color, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
