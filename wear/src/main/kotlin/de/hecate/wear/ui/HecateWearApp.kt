package de.hecate.wear.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.pager.HorizontalPager
import androidx.wear.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.material3.AnimatedPage
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.HorizontalPagerScaffold
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.MatchPhase
import de.hecate.wear.AppGraph
import de.hecate.wear.recorder.RecorderPhase
import de.hecate.wear.recorder.RecorderService
import de.hecate.wear.recorder.RecorderUiState

/**
 * The whole watch UI (§5.3): a start screen until something is recording, then
 * two pager pages — Live and Control.
 *
 * **Two pages, not three.** The original Live/Route split meant swiping mid-ride
 * to answer "how am I doing", which is exactly the question you cannot take a
 * hand off the bars to ask. Everything worth knowing while moving is now on one
 * dense page; the page you must swipe to reach holds only pause and stop, where
 * the swipe is the point — it is what stops you from ending a session with a
 * sleeve.
 *
 * No navigation library, no view models: one state flow in, intents out.
 */
@Composable
fun HecateWearApp() {
    val state by AppGraph.recorder.state.collectAsStateWithLifecycle()
    HecateWearTheme {
        // AppScaffold owns the clock: TimeText is drawn above whichever screen is
        // showing, and survives the swap between start screen and pager.
        AppScaffold {
            if (state.isRecording || state.phase == RecorderPhase.PREPARING) {
                RecordingPager(state)
            } else {
                StartScreen(state)
            }
        }
    }
}

// ---------------------------------------------------------------- start screen

@Composable
private fun StartScreen(state: RecorderUiState) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    ScreenScaffold(listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item { ListHeader { Text("Hecate") } }
            items(ExerciseType.entries.size) { i ->
                val type = ExerciseType.entries[i]
                Button(
                    onClick = { RecorderService.start(context, type) },
                    label = { Text(labelFor(type)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    buildString {
                        append(if (state.bundleVersion > 0) "Routen v${state.bundleVersion}" else "Keine Routen")
                        if (state.pendingUploads > 0) append(" · ${state.pendingUploads} offen")
                    },
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            state.message?.let { message ->
                item {
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = HecateStatus.Behind,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

private fun labelFor(type: ExerciseType): String = when (type) {
    ExerciseType.RUNNING -> "Laufen"
    ExerciseType.WALKING -> "Gehen"
    ExerciseType.BIKING -> "Radfahren"
}

// --------------------------------------------------------------------- pager

@Composable
private fun RecordingPager(state: RecorderUiState) {
    val pagerState = rememberPagerState(pageCount = { 2 })
    // HorizontalPagerScaffold supplies the curved page indicator, which fades
    // itself out once you stop paging — the hand-rolled dots it replaces were
    // always on, eating pixels the numbers now use.
    HorizontalPagerScaffold(pagerState = pagerState) {
        HorizontalPager(state = pagerState) { page ->
            AnimatedPage(pageIndex = page, pagerState = pagerState) {
                when (page) {
                    0 -> LivePage(state)
                    else -> ControlPage(state)
                }
            }
        }
    }
}

// ----------------------------------------------------------------- live page

/**
 * Everything you need while moving, on one screen (US-4, US-5, US-6).
 *
 * Laid out as a headline plus a two-column grid rather than a stack of single
 * values: paired numbers halve the vertical space, and the pairs are the ones
 * you read together anyway — how far against how fast, effort against standing.
 *
 * The whole thing has a height budget, and it is the small watch that sets it.
 * A 41 mm face is 204 dp tall and the clock takes the first 26 of them, so the
 * six rows below have to live inside about 175 dp. That is why units are set
 * inline at 12 sp rather than on a line of their own — a second line per row
 * costs 16 dp and buys nothing a suffix does not — and why the secondary pair
 * is smaller than the primary one. It fits without scrolling on both watch
 * sizes; the scroll exists only so a face smaller than either clips nothing.
 */
@Composable
private fun LivePage(state: RecorderUiState) {
    // The duration shown is always the one Health Services reports. Interpolating
    // between updates with a local clock would make the watch disagree with the
    // recording, and in ambient mode it would invent seconds that were never
    // sampled — §1.3 is explicit that ambient staleness is expected behaviour,
    // not something to paper over.
    val shownDuration = state.activeDurationMs
    val scrollState = rememberScrollState()

    ScreenScaffold(scrollState) { contentPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(contentPadding)
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            RouteHeadline(state)

            Text(
                Format.duration(shownDuration),
                style = MaterialTheme.typography.numeralSmall,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
            )

            Spacer(Modifier.height(2.dp))
            // The primary pair: what the ride has covered, and how fast it is
            // going right now.
            Row(Modifier.fillMaxWidth()) {
                Metric(
                    Format.distanceValue(state.distanceM),
                    Format.distanceUnit(state.distanceM),
                    MaterialTheme.typography.numeralSmall,
                )
                Metric(
                    Format.speedValue(state.speedKmh ?: Format.speedFrom(state.distanceM, shownDuration)),
                    "km/h",
                    MaterialTheme.typography.numeralSmall,
                )
            }

            Spacer(Modifier.height(2.dp))
            // The secondary pair, set smaller: effort, and how today compares.
            // The delta earns its place by colour rather than by size — red or
            // green is legible at a glance in a way another 30 sp number is not.
            Row(Modifier.fillMaxWidth()) {
                Metric(
                    Format.heartRate(state.heartRateBpm),
                    "bpm",
                    MaterialTheme.typography.labelMedium,
                )
                // US-5: ahead/behind is shown as soon as anything plausible
                // exists, suppressed only when the null hypothesis dominates.
                val delta = state.deltaVsMedianS.takeIf { state.matchPhase != MatchPhase.UNKNOWN }
                Metric(
                    Format.delta(delta),
                    "Ø",
                    MaterialTheme.typography.labelMedium,
                    color = when {
                        delta == null -> MaterialTheme.colorScheme.onSurfaceVariant
                        delta < 0 -> HecateStatus.Ahead
                        else -> HecateStatus.Behind
                    },
                )
            }

            // US-6: meaningless without a route, so absent until committed.
            if (state.matchPhase == MatchPhase.COMMITTED) {
                Spacer(Modifier.height(3.dp))
                Text(
                    "noch ${Format.distance(state.remainingM ?: 0.0)} · " +
                        "an ${Format.arrival(System.currentTimeMillis(), state.etaSecondsFromNow)}",
                    style = MaterialTheme.typography.bodyExtraSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }

            StatusLine(state)
        }
    }
}

/**
 * The route name, or an honest account of why there isn't one.
 *
 * Green: worked out from today's track. Blue: assumed, because you ranked this
 * route above the others it is currently indistinguishable from. Both are a name
 * on the screen; only one of them is a finding.
 */
@Composable
private fun RouteHeadline(state: RecorderUiState) {
    val (label, colour) = when {
        state.matcherDisabled -> "Matcher aus" to MaterialTheme.colorScheme.onSurfaceVariant
        state.matchPhase == MatchPhase.COMMITTED && state.routeByPriority ->
            (state.routeName ?: "Route") to HecateStatus.Assumed
        state.matchPhase == MatchPhase.COMMITTED ->
            (state.routeName ?: "Route") to HecateStatus.Matched
        state.matchPhase == MatchPhase.UNKNOWN -> "Unbekannt" to MaterialTheme.colorScheme.onSurfaceVariant
        else -> "Suche…" to HecateStatus.Pending
    }
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = colour,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
}

/**
 * One line for whatever is currently wrong, and nothing at all when nothing is.
 *
 * Pause, GPS and off-route never coincide in practice and are ranked here by how
 * much they change what you should do next.
 */
@Composable
private fun StatusLine(state: RecorderUiState) {
    val (label, colour) = when {
        state.phase == RecorderPhase.PAUSED ->
            (if (state.autoPaused) "Auto-Pause" else "Pausiert") to HecateStatus.Pending
        state.phase == RecorderPhase.PREPARING -> "GPS…" to HecateStatus.Pending
        state.offRoute -> "Abweichung" to HecateStatus.Behind
        else -> return
    }
    Spacer(Modifier.height(3.dp))
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = colour,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    )
}

/**
 * One cell of the metric grid: the number, with its unit set small beside it.
 *
 * Inline rather than stacked because vertical space is the scarce one here (see
 * [LivePage]); [valueStyle] is what ranks the cell, so the same helper serves
 * both the primary and the secondary pair.
 */
@Composable
private fun RowScope.Metric(
    value: String,
    unit: String,
    valueStyle: TextStyle,
    color: Color = MaterialTheme.colorScheme.onBackground,
) {
    val unitColor = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            append(value)
            withStyle(SpanStyle(fontSize = 12.sp, color = unitColor)) { append(" $unit") }
        },
        style = valueStyle,
        color = color,
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.weight(1f),
    )
}

// -------------------------------------------------------------- control page

/** Page 2: pause, resume, stop — the things a sleeve must not do by accident. */
@Composable
private fun ControlPage(state: RecorderUiState) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        listState,
        edgeButton = {
            // Stop hugs the bottom bezel, where the thumb lands and where nothing
            // else can be hit on the way. Error colours because it is the one
            // action here that cannot be undone.
            EdgeButton(
                onClick = { RecorderService.command(context, RecorderService.ACTION_STOP) },
                buttonSize = EdgeButtonSize.Medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) { Text("Beenden") }
        },
    ) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item {
                val paused = state.phase == RecorderPhase.PAUSED
                Button(
                    onClick = {
                        RecorderService.command(
                            context,
                            if (paused) RecorderService.ACTION_RESUME else RecorderService.ACTION_PAUSE,
                        )
                    },
                    label = { Text(if (paused) "Weiter" else "Pause") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.gpsReady) "GPS ok" else "GPS sucht",
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = if (state.gpsReady) MaterialTheme.colorScheme.onSurfaceVariant
                        else HecateStatus.Pending,
                    )
                }
            }
        }
    }
}
