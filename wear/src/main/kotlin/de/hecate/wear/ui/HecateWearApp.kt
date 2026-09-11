package de.hecate.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.MatchPhase
import de.hecate.wear.AppGraph
import de.hecate.wear.recorder.RecorderPhase
import de.hecate.wear.recorder.RecorderService
import de.hecate.wear.recorder.RecorderUiState

/**
 * The whole watch UI (§5.3): a start screen until something is recording, then
 * three pager pages — Live, Route, Control.
 *
 * No navigation library, no view models: one state flow in, intents out.
 */
@Composable
fun HecateWearApp() {
    val state by AppGraph.recorder.state.collectAsStateWithLifecycle()
    MaterialTheme {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (state.isRecording || state.phase == RecorderPhase.PREPARING) {
                RecordingPager(state)
            } else {
                StartScreen(state)
            }
        }
    }
}

@Composable
private fun StartScreen(state: RecorderUiState) {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Hecate", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(Modifier.height(8.dp))
        for (type in ExerciseType.entries) {
            Chip(
                onClick = { RecorderService.start(context, type) },
                colors = ChipDefaults.primaryChipColors(),
                label = { Text(labelFor(type)) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            buildString {
                append(if (state.bundleVersion > 0) "Routen v${state.bundleVersion}" else "Keine Routen")
                if (state.pendingUploads > 0) append(" · ${state.pendingUploads} offen")
            },
            fontSize = 11.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center,
        )
        state.message?.let {
            Text(it, fontSize = 11.sp, color = Color(0xFFE57373), textAlign = TextAlign.Center)
        }
    }
}

private fun labelFor(type: ExerciseType): String = when (type) {
    ExerciseType.RUNNING -> "Laufen"
    ExerciseType.WALKING -> "Gehen"
    ExerciseType.BIKING -> "Radfahren"
}

@Composable
private fun RecordingPager(state: RecorderUiState) {
    val pagerState = rememberPagerState(initialPage = 0) { 3 }
    Column(Modifier.fillMaxSize()) {
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> LivePage(state)
                1 -> RoutePage(state)
                else -> ControlPage(state)
            }
        }
        PagerDots(pagerState.currentPage, 3)
    }
}

@Composable
private fun PagerDots(current: Int, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (i == current) 6.dp else 4.dp)
                    .clip(CircleShape)
                    .background(if (i == current) Color.White else Color.DarkGray),
            )
        }
    }
}

/** Page 1: the numbers that exist whether or not a route is known. */
@Composable
private fun LivePage(state: RecorderUiState) {
    // The duration shown is always the one Health Services reports. Interpolating
    // between updates with a local clock would make the watch disagree with the
    // recording, and in ambient mode it would invent seconds that were never
    // sampled — §1.3 is explicit that ambient staleness is expected behaviour,
    // not something to paper over.
    val shownDuration = state.activeDurationMs

    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (state.phase == RecorderPhase.PREPARING) {
            Text("GPS…", fontSize = 14.sp, color = Color(0xFFFFB74D))
            Spacer(Modifier.height(4.dp))
        }
        Text(Format.duration(shownDuration), fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Text(Format.distance(state.distanceM), fontSize = 20.sp)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.Center) {
            Text(Format.speed(state.speedKmh ?: Format.speedFrom(state.distanceM, shownDuration)), fontSize = 14.sp)
            Text("  ·  ", fontSize = 14.sp, color = Color.Gray)
            Text("${Format.heartRate(state.heartRateBpm)} bpm", fontSize = 14.sp)
        }
        if (state.phase == RecorderPhase.PAUSED) {
            Spacer(Modifier.height(4.dp))
            Text(
                if (state.autoPaused) "Auto-Pause" else "Pausiert",
                fontSize = 12.sp,
                color = Color(0xFFFFB74D),
            )
        }
    }
}

/** Page 2: everything route-relative (US-4, US-5, US-6). */
@Composable
private fun RoutePage(state: RecorderUiState) {
    val now = System.currentTimeMillis()
    Column(
        Modifier.fillMaxSize().padding(horizontal = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Green: worked out from today's track. Blue: assumed, because you
        // ranked this route above the others it is currently indistinguishable
        // from. Both are a name on the screen; only one of them is a finding.
        val (label, colour) = when {
            state.matcherDisabled -> "Matcher aus" to Color.Gray
            state.matchPhase == MatchPhase.COMMITTED && state.routeByPriority ->
                (state.routeName ?: "Route") to Color(0xFF64B5F6)
            state.matchPhase == MatchPhase.COMMITTED ->
                (state.routeName ?: "Route") to Color(0xFF81C784)
            state.matchPhase == MatchPhase.UNKNOWN -> "Unbekannt" to Color.Gray
            else -> "Suche…" to Color(0xFFFFB74D)
        }
        Text(
            label,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = colour,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Spacer(Modifier.height(8.dp))

        // US-5: ahead/behind is shown as soon as anything plausible exists,
        // suppressed only when the null hypothesis dominates.
        if (state.matchPhase != MatchPhase.UNKNOWN && state.deltaVsMedianS != null) {
            val ahead = state.deltaVsMedianS < 0
            Text(
                Format.delta(state.deltaVsMedianS),
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = if (ahead) Color(0xFF81C784) else Color(0xFFE57373),
            )
            Text(if (ahead) "schneller als sonst" else "langsamer als sonst", fontSize = 10.sp, color = Color.Gray)
        } else {
            Text("–", fontSize = 26.sp, color = Color.DarkGray)
        }

        Spacer(Modifier.height(8.dp))
        // US-6: meaningless without a route, so absent until committed.
        if (state.matchPhase == MatchPhase.COMMITTED) {
            Text("noch ${Format.distance(state.remainingM ?: 0.0)}", fontSize = 14.sp)
            Text("Ankunft ${Format.arrival(now, state.etaSecondsFromNow)}", fontSize = 14.sp)
        }
        if (state.offRoute) {
            Spacer(Modifier.height(6.dp))
            Text("Abweichung", fontSize = 13.sp, color = Color(0xFFE57373), fontWeight = FontWeight.Bold)
        }
    }
}

/** Page 3: pause, resume, stop. */
@Composable
private fun ControlPage(state: RecorderUiState) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (state.phase == RecorderPhase.PAUSED) {
            Button(
                onClick = { RecorderService.command(context, RecorderService.ACTION_RESUME) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Weiter") }
        } else {
            Button(
                onClick = { RecorderService.command(context, RecorderService.ACTION_PAUSE) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Pause") }
        }
        Spacer(Modifier.height(10.dp))
        Chip(
            onClick = { RecorderService.command(context, RecorderService.ACTION_STOP) },
            colors = ChipDefaults.primaryChipColors(backgroundColor = Color(0xFFB71C1C)),
            label = { Text("Beenden") },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (state.gpsReady) "GPS ok" else "GPS sucht",
            fontSize = 11.sp,
            color = if (state.gpsReady) Color.Gray else Color(0xFFFFB74D),
        )
    }
}
