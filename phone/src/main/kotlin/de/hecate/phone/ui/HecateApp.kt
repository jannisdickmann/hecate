package de.hecate.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
                    // No title: the screen's own hero says what it is, in
                    // larger type than a bar can, and a second one above it
                    // would only repeat it.
                    TopAppBar(
                        title = {},
                        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
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
                        for ((panel, icon) in panels) {
                            NavigationBarItem(
                                selected = screen == panel,
                                onClick = { vm.show(panel) },
                                icon = { Icon(icon, contentDescription = null) },
                                label = { Text(titleOf(panel)) },
                            )
                        }
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
                //
                // Screens cross-fade, and a detail rises a little into place:
                // enough to say "this came from that", too quick to wait for.
                AnimatedContent(
                    targetState = screen,
                    transitionSpec = {
                        val deeper = targetState is Screen.SessionDetail || targetState is Screen.RouteDetail
                        (fadeIn(tween(220)) + slideInVertically(tween(260)) { if (deeper) it / 24 else 0 }) togetherWith
                            fadeOut(tween(120))
                    },
                    label = "screen",
                ) { shown ->
                    stateHolder.SaveableStateProvider(scrollKeyOf(shown)) {
                        when (shown) {
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

/** The three panels the bottom bar switches between, labelled by [titleOf]. */
private val panels: List<Pair<Screen, ImageVector>> = listOf(
    Screen.Recordings to Icons.AutoMirrored.Filled.List,
    Screen.Routes to Icons.Filled.Place,
    Screen.Settings to Icons.Filled.Settings,
)

private fun titleOf(screen: Screen): String = when (screen) {
    is Screen.Recordings -> "Aufzeichnungen"
    is Screen.Routes -> "Routen"
    is Screen.Settings -> "Einstellungen"
    is Screen.SessionDetail -> "Aufzeichnung"
    is Screen.RouteDetail -> "Route"
}

/** A duration, or a dash where there is none to show. */
internal fun durationOrDash(millis: Long?): String = millis?.let { Format.duration(it) } ?: "–"

/** A recording's average speed over its active time, unit and all. */
internal fun averageSpeed(s: SessionSummary): String =
    Format.speed(Format.speedFrom(s.totalDistanceM, s.activeDurationMs)) + " km/h"
