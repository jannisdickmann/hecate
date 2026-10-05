package de.hecate.phone.ui

import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hecate.core.Tuning
import de.hecate.core.map.KarteIndex
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.Intensity
import de.hecate.core.profile.Elevation
import de.hecate.core.profile.Timeline
import de.hecate.phone.R
import de.hecate.phone.db.HcWriteState
import de.hecate.phone.db.RouteSummary
import de.hecate.phone.db.SessionSummary
import de.hecate.phone.export.GpxExporter

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
    onImportMap: () -> Unit,
    onRequestHealthConnect: () -> Unit,
    onShare: (GpxExporter.Share) -> Unit,
) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val canGoBack by vm.canGoBack.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val stateHolder = rememberSaveableStateHolder()

    LaunchedEffect(status) {
        status?.let {
            snackbar.showSnackbar(it)
            vm.clearStatus()
        }
    }

    // The share sheet needs the activity, so the file is handed up to it; the
    // view model only builds it.
    val share by vm.share.collectAsStateWithLifecycle()
    LaunchedEffect(share) {
        share?.let {
            vm.clearShare()
            onShare(it)
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
                        // Up here rather than among the rows: it acts on the
                        // whole recording or route, and it is the one thing on
                        // these screens that sends something out of the app.
                        actions = {
                            IconButton(onClick = { vm.shareGpx(screen) }) {
                                Icon(Icons.Filled.Share, contentDescription = "Als GPX teilen")
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
                // Each screen keeps its own scroll position across a trip away and
                // back. Screens are swapped by a `when`, so the one being left
                // drops out of composition and everything it remembered goes with
                // it — including where the list was — and a back press out of a
                // recording landed at the top of a year of them.
                //
                // This is the mechanism Navigation-Compose uses for the same
                // problem: a holder that keeps each key's saved state alive while
                // the composable itself is gone. Nothing inside the screens has to
                // know about it, because `LazyColumn`'s own state and
                // `rememberScrollState` are already `rememberSaveable` underneath;
                // they were being saved into a slot that no longer existed.
                stateHolder.SaveableStateProvider(scrollKeyOf(screen)) {
                    when (screen) {
                        is Screen.Recordings -> RecordingsScreen(vm)
                        is Screen.Routes -> RoutesScreen(vm)
                        is Screen.Settings -> SettingsScreen(
                            vm, onPickTcx, onExportArchive, onRestoreArchive, onImportMap,
                            onRequestHealthConnect,
                        )
                        is Screen.SessionDetail -> RecordingDetailScreen(vm)
                        is Screen.RouteDetail -> RouteDetailScreen(vm)
                    }
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

/**
 * What a screen's remembered scroll position is filed under.
 *
 * Per detail screen rather than per kind: two recordings scrolled to different
 * places are two different places, and coming back to one of them should not
 * land where the other was left. A plain string because it ends up in the saved
 * instance state, and what goes in there survives a process death and should be
 * something legible when it does.
 *
 * Entries are never evicted. Each is an item index and a pixel offset — a few
 * bytes — so a session that opens a hundred recordings has spent a few hundred
 * bytes to remember all of them, which is a better trade than losing the one the
 * user is about to come back to.
 */
private fun scrollKeyOf(screen: Screen): String = when (screen) {
    is Screen.Recordings -> "recordings"
    is Screen.Routes -> "routes"
    is Screen.Settings -> "settings"
    is Screen.SessionDetail -> "session:${screen.id}"
    is Screen.RouteDetail -> "route:${screen.id}"
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
    val all by vm.sessions.collectAsStateWithLifecycle()
    val sessions by vm.filteredSessions.collectAsStateWithLifecycle()
    val types by vm.activityTypes.collectAsStateWithLifecycle()
    val filter by vm.activityFilter.collectAsStateWithLifecycle()
    val standouts by vm.standouts.collectAsStateWithLifecycle()
    val loaded by vm.sessionsLoaded.collectAsStateWithLifecycle()

    // Nothing rather than the empty-state text while the first query is out.
    if (!loaded) return
    if (all.isEmpty()) {
        Empty(
            "Noch keine Aufzeichnungen.\n\nWas die Uhr aufnimmt, erscheint hier automatisch. " +
                "Unter „Einstellungen“ lassen sich TCX-Dateien aus Google Health einlesen, " +
                "damit Routen sofort gelernt werden.",
        )
        return
    }
    Column(Modifier.fillMaxSize()) {
        // The filter never empties the list: an activity is only offered while
        // it occurs, and one that stops occurring stops applying — see
        // [HecateViewModel.activityFilter].
        ActivityFilterRow(types, filter) { vm.setActivityFilter(it) }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(sessions, key = { it.id }) { s ->
                // Against the whole history, not against what is on screen: a
                // recording is fast compared to the others on its route, and
                // narrowing the list does not change which those are.
                RecordingRow(s, standouts[s.id] ?: Standout()) { vm.show(Screen.SessionDetail(s.id)) }
            }
        }
    }
}

/**
 * The activity filter above the recordings list.
 *
 * **Only the activities that occur.** A chip for a sport the user has never
 * recorded can do exactly one thing — empty the list — and reads as a promise
 * the app cannot keep. The row therefore comes from the history rather than from
 * the enum, and disappears entirely when there is only one kind of recording to
 * filter, because a filter with one option and an "Alle" beside it is two ways
 * of saying the same thing.
 *
 * Pinned above the list rather than scrolling with it: it is a control, and a
 * control that has scrolled away is a control the user has to hunt for after
 * exactly the action — scrolling through a long history — that makes them want
 * it.
 */
@Composable
private fun ActivityFilterRow(
    types: List<ExerciseType>,
    selected: ExerciseType?,
    onSelect: (ExerciseType?) -> Unit,
) {
    if (types.size < 2) return
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "all") {
            FilterChip(
                selected = selected == null,
                onClick = { onSelect(null) },
                label = { Text("Alle") },
            )
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
                    ?: Quiet(if (s.source.isImport) "importiert" else "keine Route")
            }
        }
    }
}

/**
 * One figure in a recording row, emphasised when it is in the top of the range
 * for its route (US-8).
 *
 * Emphasis is colour, weight **and** an arrow, never colour alone: the two marks
 * sit side by side in the same row, and have to be tellable apart by someone who
 * cannot separate the hues. Each figure keeps its unit for the same reason.
 *
 * The arrow arrived with the threshold moving from the 75th percentile to the
 * 85th. A mark on a quarter of the rows is found by comparing two rows; a mark
 * on one row in seven has to be findable by scanning a screen, and a shade of
 * colour with a slightly heavier weight is not — see [Standouts.PERCENTILE].
 */
@Composable
private fun Standing(text: String, standout: Boolean, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
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

@Composable
private fun Quiet(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/**
 * The name of the route a recording was matched to.
 *
 * Shortened to [Format.MAX_ROUTE_NAME_CHARS]: the pill is as wide as what is in
 * it, and a route carrying a long name pushed the figures beside it off the row.
 */
@Composable
private fun RoutePill(name: String) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            Format.routeName(name),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun RecordingDetailScreen(vm: HecateViewModel) {
    val detail by vm.sessionDetail.collectAsStateWithLifecycle()
    val peakHr by vm.peakHr.collectAsStateWithLifecycle()
    val d = detail ?: return
    var confirmDelete by remember { mutableStateOf(false) }
    var editingType by remember { mutableStateOf(false) }

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
        TrackMapSection(d.geometry, peakHr, Format.dateTime(d.summary.startedAtUtc, d.summary.tzOffsetMinutes))
        ElevationSection(d.elevation, d.elevationStats, peakHr)
        ChartSection("Geschwindigkeit", d.speed, d.speedStats, ChartKind.SPEED, peakHr)
        ChartSection("Herzfrequenz", d.heartRate, d.heartRateStats, ChartKind.HEART_RATE, peakHr)
        IntensityLegend(
            d.geometry.any { it.hrBpm != null } ||
                d.elevation.any { it.hrBpm != null } ||
                d.speed.any { it.hrBpm != null },
        )
        Spacer(Modifier.height(12.dp))

        ActivityTypeRow(d.summary.exerciseType) { editingType = true }
        KeyValue("Dauer (aktiv)", Format.duration(d.summary.activeDurationMs))
        KeyValue("Distanz", Format.distance(d.summary.totalDistanceM))
        KeyValue("Ø Herzfrequenz", "${Format.hr(d.summary.avgHr)} bpm")
        KeyValue(
            "Ø Geschwindigkeit",
            Format.speed(
                Format.speedFrom(d.summary.totalDistanceM, d.summary.activeDurationMs),
            ) + " km/h",
        )
        KeyValue("Quelle", Format.source(d.summary.source))

        KeyValue("Route", d.routeName ?: "keine")

        // What the watch's matcher did, and what the recording logged along the
        // way: worth having when a match looks wrong, and noise the rest of the
        // time — so it is there, one tap away, and not in the way of the
        // recording itself.
        Spacer(Modifier.height(16.dp))
        CollapsibleSection("Technische Details") {
            SectionHeader("Zuordnung")
            KeyValue("Festlegung bei", d.committedAtM?.let { Format.distance(it) } ?: "–")
            // Both "Festlegung" rows describe what the watch's matcher did during
            // the recording, so both are blank when it did nothing: an imported
            // session has no match log, and saying "Spurverlauf" there claimed a
            // decision that was never made — the route came from the clusterer
            // afterwards.
            KeyValue(
                "Festlegung durch",
                when (d.committedByPriority) {
                    null -> "–"
                    true -> "Priorität"
                    false -> "Spurverlauf"
                },
            )
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

    if (editingType) {
        ActivityTypeDialog(
            current = d.summary.exerciseType,
            onPick = { editingType = false; vm.setExerciseType(d.summary.id, it) },
            onDismiss = { editingType = false },
        )
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
    val heatmap by vm.heatmap.collectAsStateWithLifecycle()
    val loaded by vm.routesLoaded.collectAsStateWithLifecycle()
    if (!loaded) return
    if (routes.isEmpty() && !clustering) {
        Empty(
            "Noch keine Routen.\n\nEine Route entsteht, sobald drei ähnliche Aufzeichnungen " +
                "vorliegen. Bis dahin hilft der TCX-Import unter „Einstellungen“.",
        )
        return
    }
    // Hoisted above the list: the raster is built off the main thread and kept
    // while the screen lives, so scrolling the card out of the LazyColumn and
    // back does not rebuild it.
    var expanded by remember { mutableStateOf(false) }
    val raster = heatmap?.let { rememberHeatmapRaster(it.grid) }
    // The full-screen picture is a different raster of different ground, and it
    // is read only while the dialog is up — see [HecateViewModel.openHeatmap].
    val full by vm.fullHeatmap.collectAsStateWithLifecycle()

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
            // Inside the LazyColumn rather than pinned above it: it is the
            // widest thing on the screen and scrolls away like any other row,
            // which is what you want once you are reading the list itself.
            heatmap?.let { h ->
                item(key = "heatmap") {
                    MapCard(rememberHeatMap(h, raster), onOpen = { expanded = true; vm.openHeatmap() }) {
                        Quiet(heatmapCaption(h))
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            items(routes, key = { it.id }) { r ->
                RouteRow(r) { vm.show(Screen.RouteDetail(r.id)) }
            }
        }
    }

    if (expanded) {
        // A different picture from the card, not an enlargement of it: it
        // covers every recording the radius filter kept, in whatever shape that
        // ground is, so its raster is built here and lives only while it is up.
        val fullMap = full?.let { f -> rememberHeatMap(f, rememberHeatmapRaster(f.grid)) }
        MapFullScreen(
            content = fullMap,
            title = "Alle Aufzeichnungen",
            // The full picture's own numbers, and they differ from the card's:
            // it covers ground the card leaves out, so it leaves less out.
            caption = full?.let { heatmapCaption(it) },
            onDismiss = { expanded = false; vm.closeHeatmap() },
        )
    }
}

/**
 * A header that opens and closes what is under it, closed to begin with.
 *
 * Remembered across recomposition and rotation, not across screens: details
 * opened on one recording are not a reason to open them on the next.
 */
@Composable
private fun CollapsibleSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Icon(
            if (open) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
            contentDescription = if (open) "Zuklappen" else "Aufklappen",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    AnimatedVisibility(open) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/**
 * A recording's or a route's track as a map card, opening full screen.
 *
 * The same card and the same full-screen view as the heatmap on the routes
 * list: one kind of map in the app, which opens and moves one way.
 */
@Composable
private fun TrackMapSection(points: List<TrackPoint>, peakHr: Int, title: String) {
    val map = rememberTrackMap(points, peakHr)
    var open by rememberSaveable { mutableStateOf(false) }
    MapCard(map, onOpen = { open = true })
    if (open && map != null) MapFullScreen(map, title = title, caption = null, onDismiss = { open = false })
}

/** What the picture is made of, in one line — under the card and again under the
 *  full-screen view, which has no room for a second one.
 *
 *  Both ways of not being in the picture are named, because they are different
 *  and because neither is visible: too far from home to be believed, and inside
 *  the radius but off the ground this picture covers. An undrawn recording looks
 *  exactly like one that was never made. */
private fun heatmapCaption(state: HeatmapState): String = buildString {
    append("${state.grid.sessionsUsed} Aufzeichnungen")
    append(" · ${state.routes.size} Routen")
    if (state.grid.isEmpty) append(" · keine Messpunkte im Ausschnitt")
    val discarded = state.grid.routesDiscarded + state.grid.sessionsDiscarded
    if (discarded > 0) {
        append(
            " · $discarded außerhalb von " +
                "${(Tuning.HEATMAP_MAX_RADIUS_M / 1000).toInt()} km",
        )
    }
    val offPicture = state.grid.sessionsOutsideExtent
    if (offPicture > 0) append(" · $offPicture außerhalb des Ausschnitts")
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
                    // Only worth the pixels when the user has actually said
                    // something, which on a scale centred at zero is exactly
                    // "not zero".
                    if (r.priority != Tuning.DEFAULT_ROUTE_PRIORITY) {
                        append(" · Prio ${Format.priority(r.priority)}")
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
    val peakHr by vm.peakHr.collectAsStateWithLifecycle()
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
        TrackMapSection(d.geometry, peakHr, d.name)
        ElevationSection(d.elevation, d.elevationStats, peakHr)
        // "Median" in both titles, because that is what a route is: the middle
        // of everything its members did here, not any one ride.
        ChartSection("Geschwindigkeit (Median)", d.speed, d.speedStats, ChartKind.SPEED, peakHr)
        ChartSection("Herzfrequenz (Median)", d.heartRate, d.heartRateStats, ChartKind.HEART_RATE, peakHr)
        IntensityLegend(
            d.geometry.any { it.hrBpm != null } ||
                d.elevation.any { it.hrBpm != null } ||
                d.speed.any { it.hrBpm != null },
        )
        Spacer(Modifier.height(16.dp))

        PriorityPicker(d.priority) { vm.setPriority(d.id, it) }
        Spacer(Modifier.height(16.dp))

        KeyValue("Art", d.exerciseType.label)
        KeyValue("Länge", Format.distance(d.lengthM))
        KeyValue("Aufzeichnungen", d.memberCount.toString())
        KeyValue("Median-Zeit", d.medianDurationMs?.let { Format.duration(it) } ?: "–")
        KeyValue("Beste Zeit", d.bestDurationMs?.let { Format.duration(it) } ?: "–")
        KeyValue("Schlechteste", d.worstDurationMs?.let { Format.duration(it) } ?: "–")

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
    onImportMap: () -> Unit,
    onRequestHealthConnect: () -> Unit,
) {
    val clustering by vm.clustering.collectAsStateWithLifecycle()
    val hc by vm.healthConnect.collectAsStateWithLifecycle()
    val peakHr by vm.peakHr.collectAsStateWithLifecycle()

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
        SectionHeader("Belastung")
        Spacer(Modifier.height(8.dp))
        SettingsCard { PeakHrField(peakHr) { vm.setPeakHr(it) } }

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
        SectionHeader("Karte")
        Spacer(Modifier.height(8.dp))
        SettingsCard { MapCard(vm, onImportMap) }

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

/**
 * What the app knows of the ground under your tracks, and the three things you
 * can do about it (Appendix A.29).
 *
 * There is **no on/off switch**. Deleting the data is the off switch, and a
 * toggle would be a fifth state to test for a feature that was asked for
 * unconditionally.
 *
 * The attribution line is not decoration: OSM data is ODbL, and the obligation
 * is to name the source where the data is presented rather than only in a
 * licence file. It appears twice — here, and under the full-screen heatmap.
 */
@Composable
private fun MapCard(vm: HecateViewModel, onImportMap: () -> Unit) {
    val stats by vm.mapStats.collectAsStateWithLifecycle()
    val prefetch by vm.mapPrefetch.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Text("Kartendaten", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    if (stats.isEmpty) {
        Quiet(
            "Noch keine Kartendaten. Streckenbilder werden auf leerem Grund gezeichnet; " +
                "beim Öffnen einer Strecke lädt die App die Umgebung nach.",
        )
    } else {
        Quiet(
            buildString {
                append(plural(stats.tiles, "Kachel", "Kacheln"))
                append(" · ${Format.fileSize(stats.bytes)}")
                if (stats.regions > 0) append(" · ${plural(stats.regions, "Gebiet", "Gebiete")}")
                stats.newestUtcMs?.let { append(" · zuletzt geladen am ${Format.date(it, 0)}") }
            },
        )
    }
    Spacer(Modifier.height(4.dp))
    Quiet(KarteIndex.ATTRIBUTION)

    Spacer(Modifier.height(12.dp))
    val running = prefetch
    if (running != null) {
        // Waiting and downloading are different things to be told: the job
        // waits for an unmetered network, and once it has one it can still take
        // a while — Overpass answers one polite request at a time.
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                when {
                    // Two different waits. A job interrupted mid-run — by an app
                    // update, say — sits out a backoff even with WLAN right
                    // there, and saying "waiting for WLAN" then is just wrong.
                    !running.running && running.interrupted -> "Unterbrochen, wird fortgesetzt…"
                    !running.running -> "Wartet auf WLAN…"
                    running.blocks == 0 -> "Kartendaten werden geladen…"
                    else -> "Kartendaten werden geladen · " +
                        (if (running.fine) "Details" else "Überblick") +
                        " ${minOf(running.block + 1, running.blocks)}/${running.blocks}"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (!running.running) {
                TextButton(onClick = { vm.prefetchMap(restart = true) }) { Text("Jetzt starten") }
            }
        }
    } else {
        SettingsAction(
            "Karte vorladen",
            "Lädt die Umgebung aller Aufzeichnungen im Hintergrund, nur über WLAN. Danach " +
                "funktioniert die Karte auch ohne Empfang.",
            { vm.prefetchMap() },
        )
    }
    HorizontalDivider(Modifier.padding(vertical = 12.dp))
    SettingsAction(
        "Karte einlesen",
        "Übernimmt eine am Rechner erzeugte Kartendatei, statt sie hier zu laden.",
        onImportMap,
    )
    if (!stats.isEmpty) {
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        SettingsAction(
            "Karte löschen",
            "Entfernt alle Kartendaten. Die Aufzeichnungen bleiben unberührt.",
            { confirmDelete = true },
        )
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Kartendaten löschen?",
            body = "${plural(stats.tiles, "Kachel", "Kacheln")}, ${Format.fileSize(stats.bytes)}. Streckenbilder " +
                "werden danach wieder auf leerem Grund gezeichnet. Aufzeichnungen, Routen und " +
                "Namen bleiben unberührt, und die Karte lässt sich jederzeit neu laden.",
            confirm = "Löschen",
            onConfirm = { confirmDelete = false; vm.deleteMap() },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** "1 Kachel" rather than "1 Kacheln", which is the only thing on this card a
 *  reader would notice. */
private fun plural(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

/**
 * The peak heart rate the intensity bands are measured against.
 *
 * A number the user types, because there is nothing to derive it from: the app
 * has never measured a resting rate, and `220 − age` is out by more than a whole
 * zone for one person in three (see [Tuning.DEFAULT_PEAK_HR_BPM]). Anybody who
 * has finished a hard climb knows their own figure.
 *
 * The draft is local and only committed once it is a plausible rate, which is
 * what makes typing work at all: committing every keystroke would store 1, then
 * 19, then 190 — and since the stored value is clamped into the range, the field
 * would be rewritten to "100" under the user's fingers after the first digit.
 * The effect [LaunchedEffect] exists for is the other direction: a value that
 * changes elsewhere, a restored backup being the only way that happens, still
 * reaches the field.
 *
 * The bands are listed underneath in bpm, because that is the question the
 * setting is actually answering: not "what is my maximum" but "where does
 * intensiv start".
 */
@Composable
private fun PeakHrField(current: Int, onChange: (Int) -> Unit) {
    var draft by remember { mutableStateOf(current.toString()) }
    LaunchedEffect(current) { if (draft.toIntOrNull() != current) draft = current.toString() }

    Text("Maximalpuls", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Quiet(
        "Die Grundlage der Farben auf Streckenzeichnung und Höhenprofil. " +
            "Der eigene Höchstwert, nicht „220 minus Alter“ — der liegt bei jedem Dritten " +
            "um eine ganze Zone daneben.",
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = draft,
        onValueChange = { text ->
            draft = text.filter { it.isDigit() }.take(3)
            draft.toIntOrNull()
                ?.takeIf { it in Tuning.MIN_PEAK_HR_BPM..Tuning.MAX_PEAK_HR_BPM }
                ?.let(onChange)
        },
        label = { Text("bpm") },
        supportingText = {
            Quiet("${Tuning.MIN_PEAK_HR_BPM}–${Tuning.MAX_PEAK_HR_BPM}")
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
    Spacer(Modifier.height(10.dp))
    for (band in Intensity.entries) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .background(HecateIntensityColors.of(band), MaterialTheme.shapes.extraSmall),
            )
            Spacer(Modifier.width(8.dp))
            Quiet("${band.label}: ab ${Format.hr(band.lowerFraction * current)} bpm")
        }
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
 * The altitude profile and the numbers read off it, under the track on both
 * detail screens.
 *
 * No chart when there is nothing to draw: a recording made before the watch
 * asked Health Services for altitude at all, or a route last clustered before
 * the elevation column existed. A chart of a flat line at zero would be a claim
 * about the ground.
 *
 * But not *silence* either, which is what this used to be. A missing section and
 * a genuinely flat ride look identical from the sofa, and the reasonable reading
 * of an absent profile is that the ground was level — which for every recording
 * made before #6 was landed is wrong. One line is enough to say which, and it
 * keeps the rule it used to break: nothing here draws a number about height
 * unless a height was measured.
 */
@Composable
private fun ElevationSection(
    points: List<Elevation.Point>,
    stats: Elevation.Stats?,
    peakHrBpm: Int,
) {
    if (points.size < 2 || stats == null) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Kein Höhenprofil: die Uhr hat keine Höhe geliefert.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Spacer(Modifier.height(4.dp))
    SectionHeader("Höhenprofil")
    // How high up all of this happened — without which "+240 m" could be a
    // dyke or an alp — is on the chart itself now, at its highest and lowest
    // points, rather than in a row of its own underneath.
    Chart(points.asChart(), ChartKind.ELEVATION, peakHrBpm = peakHrBpm)
    // Climb and drop: what the ground cost, and the one pair a rider compares
    // between days.
    KeyValue("Aufstieg", Format.elevationSigned(stats.ascentM))
    KeyValue("Abstieg", Format.elevationSigned(-stats.descentM))
    // Only where it says something the two rows above do not: a loop nets zero
    // and the row would be noise, a one-way climb does not.
    if (kotlin.math.abs(stats.netM) >= Tuning.ELEVATION_THRESHOLD_M) {
        KeyValue("Netto", Format.elevationSigned(stats.netM))
    }
}

/**
 * The icon for an exercise type (US-8).
 *
 * Over the enum, so a type added without a drawable is a compile error. It used
 * to match on the name and fall through to drawing nothing, which was the right
 * answer to the wrong question: a blank icon beats a confidently wrong one, but
 * not noticing until the phone is in your hand beats both.
 */
@Composable
private fun ActivityIcon(
    type: ExerciseType,
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    val res = when (type) {
        ExerciseType.RUNNING -> R.drawable.ic_run
        ExerciseType.WALKING -> R.drawable.ic_walk
        ExerciseType.HIKING -> R.drawable.ic_hike
        ExerciseType.BIKING -> R.drawable.ic_bike
        ExerciseType.SKIING -> R.drawable.ic_ski
    }
    Icon(
        painter = painterResource(res),
        contentDescription = type.label,
        modifier = modifier,
        tint = tint,
    )
}

/**
 * The "Art" row, and the one fact on this screen that can be corrected.
 *
 * The watch records what it was told to record, so the button pressed at the
 * trailhead is the only thing about a workout that cannot be fixed afterwards —
 * and it is the easiest to get wrong, Spaziergang and Wandern being one tap
 * apart. Shaped like the [KeyValue] rows around it rather than lifted into a
 * control of its own: it is read far more often than it is changed, and a row
 * that already says "Art · Wandern" only has to become touchable.
 */
@Composable
private fun ActivityTypeRow(current: ExerciseType, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Art",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(current.label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Filled.Edit,
                contentDescription = "Art ändern",
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Pick the sport a recording should have been filed under.
 *
 * All five are offered rather than only the confusable pairs, because which
 * pairs are confusable is a fact about somebody's morning and not about this
 * app. Choosing applies immediately and closes — there is no second confirm
 * step, since the change is reversible by making it again, which is not true of
 * the delete below it.
 *
 * The note underneath is not a warning. Re-clustering is what the edit is *for*,
 * not a consequence of it, and the only part the user cannot infer is that it
 * travels: the rebuilt bundle goes to the watch, and Health Connect is rewritten
 * to agree.
 */
@Composable
private fun ActivityTypeDialog(
    current: ExerciseType,
    onPick: (ExerciseType) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Art der Aufzeichnung") },
        text = {
            Column {
                ExerciseType.entries.forEach { type ->
                    Row(
                        Modifier.fillMaxWidth().clickable { onPick(type) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = type == current, onClick = { onPick(type) })
                        Spacer(Modifier.width(4.dp))
                        ActivityIcon(type, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Text(type.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Quiet(
                    "Routen werden je Art getrennt gebildet, deshalb werden sie neu berechnet. " +
                        "Das neue Bündel geht an die Uhr, der Eintrag in Health Connect wird " +
                        "überschrieben.",
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
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
 * The manual route ranking, −2..2.
 *
 * Only matters where two routes share the same ground and the matcher therefore
 * refuses to choose between them (US-4). Raise one above the other and the watch
 * will assume that one — with its name, its remaining distance and its ETA —
 * from the start, instead of showing "Suche…" until the paths part. Leaving
 * everything at 0 keeps the cautious behaviour.
 *
 * One row of five rather than two rows of five out of ten. Only the *order* of
 * the numbers has ever mattered — nothing multiplies by a priority — so the old
 * range asked for a precision it could not use, and asking someone to choose
 * between a 6 and a 7 for a route is asking a question with no answer. Five
 * options fit one row at a comfortable touch target.
 *
 * The numbers stand on their own. Each used to carry a phrase above the row —
 * "0 · keine Meinung", "+1 · eher schon" — and five buttons on a centred scale
 * do not need naming twice: the row already reads left to right as less to
 * more, and the caption under it says what the scale is for. Nor did the
 * caption's second sentence, about a tie leaving the watch on "Suche…", earn
 * its line: it described what happens when the user does nothing, which is what
 * they are already looking at.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PriorityPicker(current: Int, onChange: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            "Priorität",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        val values = (Tuning.MIN_ROUTE_PRIORITY..Tuning.MAX_ROUTE_PRIORITY).toList()
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            values.forEachIndexed { index, v ->
                SegmentedButton(
                    selected = v == current,
                    onClick = { onChange(v) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = values.size),
                ) { Text(Format.priority(v)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Quiet("Entscheidet nur zwischen Routen, die sich denselben Weg teilen.")
    }
}

/**
 * A time series under the altitude profile: speed or heart rate against time,
 * coloured by effort, its highest and lowest points labelled on the line.
 *
 * Under the altitude profile rather than above it, because the ground is what
 * explains the speed: the reader has just seen the hill that the slow stretch is
 * on.
 *
 * Labels for the extremes, never an average: both screens already carry an
 * average of their own (distance over active time; the route's median
 * duration), and a second "Ø" computed another way and disagreeing with it by a
 * tenth would be worse than no number at all.
 *
 * Absent rather than flat when there is nothing to draw: a recording made
 * without a strap gets no heart-rate chart at all, because a line at zero would
 * be a claim about a heart.
 */
@Composable
private fun ChartSection(
    title: String,
    points: List<Timeline.Point>,
    stats: Timeline.Stats?,
    kind: ChartKind,
    peakHrBpm: Int,
) {
    if (points.size < 2 || stats == null) return
    Spacer(Modifier.height(4.dp))
    SectionHeader(title)
    Chart(points.asChart(), kind, peakHrBpm = peakHrBpm)
}

/**
 * The four intensity bands and their names, under a chart that is coloured by
 * them.
 *
 * Shown only where there is a heart rate to have coloured anything: on a
 * recording without one the chart is a single plain line, and a legend for four
 * colours that are not on the screen is an invitation to look for them.
 */
@Composable
private fun IntensityLegend(visible: Boolean) {
    if (!visible) return
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (band in Intensity.entries) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(8.dp)
                        .background(HecateIntensityColors.of(band), MaterialTheme.shapes.extraSmall),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    band.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
