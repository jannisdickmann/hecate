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
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hecate.phone.R
import de.hecate.phone.db.HcWriteState
import de.hecate.phone.db.RouteSummary
import de.hecate.phone.db.SessionSummary

/**
 * §5.4: Recordings list → recording detail; Routes list → route detail; plus a
 * Settings panel for everything that is not history.
 *
 * Five screens, one state enum, no navigation library (§4). The bottom bar
 * switches between the three panels; the system back button walks the history
 * the view model keeps — see [HecateViewModel.back], and Appendix A.13 for why
 * that stopped being "leave the detail screen".
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
    val canGoBack by vm.canGoBack.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(status) {
        status?.let {
            snackbar.showSnackbar(it)
            vm.clearStatus()
        }
    }

    // Enabled for every screen the user navigated to, not only the detail ones:
    // switching panels is navigation too, and back used to fall straight through
    // it to the launcher.
    BackHandler(enabled = canGoBack) { vm.back() }

    val inDetail = screen is Screen.RouteDetail || screen is Screen.SessionDetail

    // exitUntilCollapsed is the behaviour a two-row bar is built for: the tall
    // title shrinks into the short one and stays, rather than the whole bar
    // leaving the screen. Its state is shared by all screens, so it is reset on
    // every screen change — otherwise a list left scrolled down, followed by a
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
                    // No actions up here any more: everything that used to hide
                    // behind an overflow button now has a name and a home in
                    // Settings.
                    MediumTopAppBar(
                        title = { Text(titleOf(screen)) },
                        scrollBehavior = scrollBehavior,
                    )
                }
            },
            bottomBar = {
                if (!inDetail) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = screen is Screen.Recordings,
                            onClick = { vm.show(Screen.Recordings) },
                            icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                            label = { Text("Aufzeichnungen") },
                        )
                        NavigationBarItem(
                            selected = screen is Screen.Routes,
                            onClick = { vm.show(Screen.Routes) },
                            icon = { Icon(Icons.Filled.Place, contentDescription = null) },
                            label = { Text("Routen") },
                        )
                        NavigationBarItem(
                            selected = screen is Screen.Settings,
                            onClick = { vm.show(Screen.Settings) },
                            icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                            label = { Text("Einstellungen") },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (screen) {
                    is Screen.Recordings -> RecordingsScreen(vm)
                    is Screen.Routes -> RoutesScreen(vm)
                    is Screen.Settings -> SettingsScreen(
                        vm, onPickTcx, onExportArchive, onRestoreArchive, onRequestHealthConnect,
                    )
                    is Screen.SessionDetail -> RecordingDetailScreen(vm)
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

private fun titleOf(screen: Screen): String = when (screen) {
    is Screen.Recordings -> "Aufzeichnungen"
    is Screen.Routes -> "Routen"
    is Screen.Settings -> "Einstellungen"
    is Screen.SessionDetail -> "Aufzeichnung"
    is Screen.RouteDetail -> "Route"
}

// ----------------------------------------------------------------- recordings

@Composable
private fun RecordingsScreen(vm: HecateViewModel) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val standouts by vm.standouts.collectAsStateWithLifecycle()
    if (sessions.isEmpty()) {
        Empty(
            "Noch keine Aufzeichnungen.\n\nWas die Uhr aufnimmt, erscheint hier automatisch. " +
                "Unter „Einstellungen“ lassen sich TCX-Dateien aus Google Health einlesen, " +
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
            RecordingRow(s, standouts[s.id] ?: Standout()) { vm.show(Screen.SessionDetail(s.id)) }
        }
    }
}

@Composable
private fun RecordingRow(s: SessionSummary, standout: Standout, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp, 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ActivityIcon(s.exerciseType, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(
                    Format.dateTime(s.startedAtUtc, s.tzOffsetMinutes),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(Format.distance(s.totalDistanceM), style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Speed and heart rate are separate Text elements rather than one
                // built string, because either of them may need to be the one
                // that is emphasised.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Quiet(Format.duration(s.activeDurationMs))
                    Quiet(" · ")
                    Standing(
                        Format.speed(Format.speedFrom(s.totalDistanceM, s.activeDurationMs)) + " km/h",
                        standout.fast,
                        MaterialTheme.colorScheme.primary,
                    )
                    s.avgHr?.let {
                        Quiet(" · ")
                        Standing("${Format.hr(it)} bpm", standout.highHr, MaterialTheme.colorScheme.tertiary)
                    }
                }
                // A matched route is the whole point of the app, so it gets a
                // filled pill rather than another line of grey text.
                s.routeName?.let { RoutePill(it) }
                    ?: Quiet(if (s.source == "IMPORT_TCX") "importiert" else "keine Route")
            }
        }
    }
}

/**
 * One figure in a recording row, emphasised when it is in the upper quartile for
 * its route (US-8).
 *
 * Emphasis is colour *and* weight, never colour alone: the two marks sit side by
 * side in the same row, and have to be tellable apart by someone who cannot
 * separate the hues. Each figure keeps its unit for the same reason.
 */
@Composable
private fun Standing(text: String, standout: Boolean, accent: Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (standout) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        fontWeight = if (standout) FontWeight.Bold else FontWeight.Normal,
    )
}

@Composable
private fun Quiet(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The name of the route a recording was matched to. */
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
private fun RecordingDetailScreen(vm: HecateViewModel) {
    val detail by vm.sessionDetail.collectAsStateWithLifecycle()
    val d = detail ?: return
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActivityIcon(d.summary.exerciseType, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(
                Format.dateTime(d.summary.startedAtUtc, d.summary.tzOffsetMinutes),
                style = MaterialTheme.typography.titleMedium,
            )
        }
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
            MonoCard(
                d.matchEvents.map { e ->
                    "%6.0fs  %-16s %s".format(e.tOffsetMs / 1000.0, e.event.name, e.routeId?.toString() ?: "")
                },
            )
        }

        if (d.sessionEvents.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            SectionHeader("Verlauf der Aufzeichnung")
            Spacer(Modifier.height(4.dp))
            MonoCard(
                d.sessionEvents.map { e ->
                    "%6.0fs  %-18s %s".format(e.tOffsetMs / 1000.0, e.type.name, e.note.orEmpty())
                },
            )
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
            onClick = { confirmDelete = true },
            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Aufzeichnung löschen")
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Aufzeichnung löschen?",
            // Names what actually goes. "Are you sure" is not information.
            body = Format.dateTime(d.summary.startedAtUtc, d.summary.tzOffsetMinutes) + " — " +
                "${Format.distance(d.summary.totalDistanceM)}, ${Format.duration(d.summary.activeDurationMs)}.\n\n" +
                "Messpunkte, Match-Log und Verlauf werden mitgelöscht. Zurückholen lässt sich das " +
                "nur aus einer Sicherung. Der Eintrag in Health Connect bleibt bestehen.",
            confirm = "Löschen",
            onConfirm = { confirmDelete = false; vm.deleteSession(d.summary.id) },
            onDismiss = { confirmDelete = false },
        )
    }
}

// --------------------------------------------------------------------- routes

@Composable
private fun RoutesScreen(vm: HecateViewModel) {
    val routes by vm.routes.collectAsStateWithLifecycle()
    val clustering by vm.clustering.collectAsStateWithLifecycle()
    if (routes.isEmpty() && !clustering) {
        Empty(
            "Noch keine Routen.\n\nEine Route entsteht, sobald drei ähnliche Aufzeichnungen " +
                "vorliegen. Bis dahin hilft der TCX-Import unter „Einstellungen“.",
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        // Shown here as well as in Settings, because a re-clustering is most
        // visible by its effect on this list: it is about to rearrange itself,
        // and possibly to rename things.
        if (clustering) ClusteringBanner()
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
}

@Composable
private fun RouteRow(r: RouteSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp, 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ActivityIcon(r.exerciseType, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text(r.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(4.dp))
            Quiet("${Format.distance(r.lengthM)} · ${r.memberCount} Aufzeichnungen")
            Quiet(
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
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ActivityIcon(d.exerciseType, Modifier.size(22.dp), MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
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

// ------------------------------------------------------------------- settings

/**
 * Everything that is not history.
 *
 * These used to live behind a `⋮` on the recordings screen — where they were
 * neither discoverable nor obviously related to one another — with re-clustering
 * behind a second one on the routes screen. A panel costs one slot in the bottom
 * bar and lets each action carry a sentence saying what it does, which is what
 * "Health Connect einrichten" most needed and never had.
 */
@Composable
private fun SettingsScreen(
    vm: HecateViewModel,
    onPickTcx: () -> Unit,
    onExportArchive: () -> Unit,
    onRestoreArchive: () -> Unit,
    onRequestHealthConnect: () -> Unit,
) {
    val clustering by vm.clustering.collectAsStateWithLifecycle()
    val hc by vm.healthConnect.collectAsStateWithLifecycle()

    // Both the grant and its removal can happen in Health Connect's own settings,
    // so the switch is re-read every time this panel appears rather than trusted
    // from the last time it was looked at.
    LaunchedEffect(Unit) { vm.refreshHealthConnect() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SectionHeader("Routen")
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            Text("Routen neu berechnen", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Quiet(
                "Gruppiert alle Aufzeichnungen neu zu Routen. Nötig nach dem Einlesen einer " +
                    "Sicherung oder nach vielen Importen; sonst läuft es nach jeder neuen " +
                    "Aufzeichnung von selbst.",
            )
            Spacer(Modifier.height(12.dp))
            if (clustering) {
                // The button is gone while the work runs rather than merely
                // disabled: there is nothing useful to press, and the job is
                // enqueued with REPLACE, so a second tap would restart it from
                // the beginning rather than queue anything.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Berechnung läuft…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                Button(onClick = { vm.recluster() }) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Neu berechnen")
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader("Health Connect")
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Nach Health Connect schreiben", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(4.dp))
                    Quiet(
                        when (hc) {
                            HealthConnectState.CONNECTED ->
                                "Abgeschlossene Aufzeichnungen erscheinen in Google Health. " +
                                    "Ausschalten entzieht die Berechtigung; bereits geschriebene " +
                                    "Einträge bleiben dort bestehen."
                            HealthConnectState.DISCONNECTED ->
                                "Aus. Einschalten fragt die Berechtigung ab — das muss Android " +
                                    "selbst tun, die App kann sie sich nicht geben."
                            HealthConnectState.UPDATE_REQUIRED ->
                                "Health Connect ist zu alt und muss erst aktualisiert werden."
                            HealthConnectState.UNAVAILABLE ->
                                "Health Connect ist auf diesem Gerät nicht verfügbar."
                        },
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = hc == HealthConnectState.CONNECTED,
                    enabled = hc == HealthConnectState.CONNECTED || hc == HealthConnectState.DISCONNECTED,
                    onCheckedChange = { on ->
                        if (on) onRequestHealthConnect() else vm.disconnectHealthConnect()
                    },
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionHeader("Daten")
        Spacer(Modifier.height(8.dp))
        SettingsCard {
            SettingsAction(
                "TCX importieren",
                "Liest Trainings aus einem Google-Health-Export ein, damit Routen sofort gelernt " +
                    "werden statt erst nach drei neuen Läufen.",
                onPickTcx,
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SettingsAction(
                "Sicherung exportieren",
                "Schreibt die gesamte Datenbank in eine Datei. Namen und Prioritäten sind das " +
                    "Einzige darin, was sonst nirgends steht.",
                onExportArchive,
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            SettingsAction(
                "Sicherung einlesen",
                "Führt eine Sicherung mit dem zusammen, was schon da ist. Vorhandenes bleibt " +
                    "unangetastet. Danach lohnt sich „Routen neu berechnen“.",
                onRestoreArchive,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

/** A named action with a sentence saying what it does, the whole row tappable. */
@Composable
private fun SettingsAction(title: String, body: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Quiet(body)
    }
}

@Composable
private fun ClusteringBanner() {
    Surface(
        Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text("Routen werden neu berechnet…", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ----------------------------------------------------------------------- bits

/**
 * The icon for an exercise type (US-8), or nothing at all for a type this build
 * does not know — a blank is better than confidently drawing the wrong activity.
 */
@Composable
private fun ActivityIcon(
    type: String,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val res = when (type) {
        "RUNNING" -> R.drawable.ic_run
        "WALKING" -> R.drawable.ic_walk
        "BIKING" -> R.drawable.ic_bike
        else -> return
    }
    Icon(
        painter = painterResource(res),
        contentDescription = Format.exerciseType(type),
        modifier = modifier,
        tint = tint,
    )
}

/**
 * The one shape every confirmation takes.
 *
 * [body] names what is about to happen to what, rather than asking whether the
 * user is sure: by the time this is on screen they have already said so, and the
 * only useful thing left to tell them is what they will not be able to undo.
 */
@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body, style = MaterialTheme.typography.bodyMedium) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

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
        Quiet(
            "Entscheidet nur zwischen Routen, die sich denselben Weg teilen. " +
                "Bei Gleichstand bleibt die Anzeige auf „Suche…“.",
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

/** A block of fixed-width diagnostic lines — the match log, the recording history. */
@Composable
private fun MonoCard(lines: List<String>) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(10.dp)) {
            for (line in lines) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        }
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
