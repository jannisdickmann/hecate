package de.hecate.wear.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import de.hecate.core.display.HealthKind
import de.hecate.core.display.HealthSeverity
import de.hecate.core.display.RecordingHealth
import de.hecate.core.display.RecordingHealthInput
import de.hecate.core.display.RouteBeliefKind
import de.hecate.core.display.recordingHealth
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.RouteBundle
import de.hecate.wear.AppGraph
import de.hecate.wear.recorder.RecorderPhase
import de.hecate.wear.recorder.RecorderService
import de.hecate.wear.recorder.RecorderUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

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
 * **Two channels, not one.** Everything on the live page belongs to exactly one
 * of two questions, and they never share a slot, a word or a colour:
 *
 *  - *What do I know about the route?* — the headline, the metric grid, the
 *    bars. Bright, neutral, never alarming, and never blank: it degrades in
 *    detail rather than falling back to a dash (see
 *    [de.hecate.core.display.RouteBelief]).
 *  - *Is the recording working?* — one line at the bottom and the marker beside
 *    it, the only things here allowed to look wrong (see
 *    [de.hecate.core.display.recordingHealth]).
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
    // The cached bundle, read off the main thread, and re-read when a new one
    // arrives while this screen is up.
    //
    // It is the source of both things below: the order of the buttons, and the
    // version in the footer. The footer used to read the version out of the
    // recorder state instead, which only ever learns it when a bundle *arrives*
    // — so every start after the app had been closed said "Keine Routen" over a
    // watch that was holding a perfectly good bundle and matching against it.
    val bundle by produceState<RouteBundle?>(null, state.bundleVersion) {
        value = withContext(Dispatchers.IO) { AppGraph.bundles.load() }
    }
    val types = bundle?.typesByFrequency() ?: ExerciseType.entries.toList()
    ScreenScaffold(listState) { contentPadding ->
        ScalingLazyColumn(
            state = listState,
            contentPadding = contentPadding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item { ListHeader { Text("Hecate") } }
            items(types.size) { i ->
                val type = types[i]
                Button(
                    onClick = { RecorderService.start(context, type) },
                    label = { Text(type.label) },
                    icon = {
                        Icon(
                            painter = painterResource(type.iconRes),
                            // The label beside it already says the word, so an
                            // icon that repeated it would only make every button
                            // announce its type twice.
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Text(
                    buildString {
                        // How many routes the watch knows and how stale they are,
                        // which is what you want to know before pressing start.
                        // This used to be the bundle's version number, and a
                        // counter the phone bumps on every rebuild answers nothing
                        // at a glance — see Format.age.
                        val routes = bundle?.routes.orEmpty()
                        if (routes.isEmpty()) {
                            // Not a bare "Keine Routen", which reads as a refusal.
                            // Recording is the part that always works; the routes
                            // are what the phone adds to it later.
                            append("Noch keine Routen – Aufzeichnung geht trotzdem")
                        } else {
                            append(Format.routeCount(routes.size))
                            append(" · ").append(Format.age(bundle!!.builtAtUtcMs, System.currentTimeMillis()))
                        }
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
                        color = HealthTone.Degraded,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * The glyph for a sport, for the one screen that offers a choice between them.
 *
 * Buttons on a round face are read at arm's length and often in the dark, and
 * five German words of similar length are a slow way to find the one you want;
 * a shape is faster than a word here, which is the whole reason for it.
 *
 * The five files are copied from `:phone` rather than shared, because `:core` is
 * pure Kotlin and holds no Android resources — a common resource module would be
 * a lot of build for five static vectors. `when` without an `else` is the guard:
 * a sixth exercise type will not compile until it has an icon, which is how
 * A.18 asks new types to be added.
 */
@get:DrawableRes
private val ExerciseType.iconRes: Int
    get() = when (this) {
        ExerciseType.RUNNING -> de.hecate.wear.R.drawable.ic_run
        ExerciseType.WALKING -> de.hecate.wear.R.drawable.ic_walk
        ExerciseType.HIKING -> de.hecate.wear.R.drawable.ic_hike
        ExerciseType.BIKING -> de.hecate.wear.R.drawable.ic_bike
        ExerciseType.SKIING -> de.hecate.wear.R.drawable.ic_ski
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

// ------------------------------------------------------------ size adaptation

/**
 * The two Pixel Watch 4 sizes, and everything that differs between them.
 *
 * 41 mm is 408 px at density 2, so 204 dp of layout; 45 mm is 456 px, so 228 dp.
 * Same density, 24 dp more room — which the bigger watch spends on larger
 * numerals, a thicker bar and the long form of the arrival line rather than on
 * more rows, because the rows are not what is scarce.
 *
 * The breakpoint is the platform's own large-round threshold rather than a
 * device list, so a future watch lands on the right side of it without anyone
 * editing this file.
 *
 * Type sizes are set by overriding `numeralSmall`'s size rather than by reaching
 * for other entries in the numeral scale: one style is known to fit this layout
 * on both faces, and a size on it is a change this file can reason about.
 */
private data class LiveLayout(
    val large: Boolean,
    val arcStroke: Dp,
    val arcInset: Dp,
    val contentPadding: Dp,
    val heroStyle: TextStyle,
    val primaryStyle: TextStyle,
    val secondaryStyle: TextStyle,
    /** "noch 2,10 km · an 18:42" against "2,10 km · 18:42". */
    val verboseEta: Boolean,
    /** Whether a healthy recording spells out "Aufzeichnung" or leaves the
     *  marker to say it. Anything unhealthy is always spelled out. */
    val labelWhenHealthy: Boolean,
)

@Composable
private fun rememberLiveLayout(): LiveLayout {
    val large = LocalConfiguration.current.screenWidthDp >= LARGE_SCREEN_DP
    val numeral = MaterialTheme.typography.numeralSmall
    return LiveLayout(
        large = large,
        arcStroke = if (large) 6.dp else 5.dp,
        arcInset = if (large) 4.dp else 3.dp,
        // The bars cost no vertical space — they are drawn behind the column —
        // but they do cost width, and on the smaller face that is the whole
        // budget. Hence the wider gutter and the smaller primary numerals there.
        contentPadding = if (large) 22.dp else 18.dp,
        heroStyle = if (large) numeral.copy(fontSize = 38.sp) else numeral,
        primaryStyle = if (large) numeral else numeral.copy(fontSize = 26.sp),
        secondaryStyle = MaterialTheme.typography.labelMedium,
        verboseEta = large,
        labelWhenHealthy = large,
    )
}

private const val LARGE_SCREEN_DP = 225

// ----------------------------------------------------------------- live page

/**
 * Everything you need while moving, on one screen (US-4, US-5, US-6).
 *
 * Laid out as a headline plus a two-column grid rather than a stack of single
 * values: paired numbers halve the vertical space, and the pairs are the ones
 * you read together anyway — how far against how long, effort against standing.
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
    val layout = rememberLiveLayout()

    // A clock of its own for the health line, and the one place in this file that
    // is allowed one. Every other number here changes when the recorder publishes
    // something; "nothing has arrived for a minute" is by definition the case
    // where nothing will, so it has to be noticed by asking rather than by being
    // told. One second is the resolution the line is written at.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val health = remember(state, now) { state.healthAt(now) }

    ScreenScaffold(scrollState) { contentPadding ->
        Box(Modifier.fillMaxSize()) {
            EdgeBars(state, layout)
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(contentPadding)
                    .padding(horizontal = layout.contentPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                ReplayBanner(state)
                RouteHeadline(state)

                // Speed leads, and the full-width line is also the only place
                // its unit fits: "Ø km/h" in half a row was clipped to "Ø", which
                // reads as a quantity of its own rather than as a cut-off one.
                // A single cell in a row of one, so that it shrinks by the same
                // rule as every other number here.
                Row(Modifier.fillMaxWidth()) {
                    Metric(
                        Format.speedValue(state.speedKmh ?: Format.speedFrom(state.distanceM, shownDuration)),
                        if (state.speedKmh == null) "Ø km/h" else "km/h",
                        layout.heroStyle,
                    )
                }

                Spacer(Modifier.height(2.dp))
                // The primary pair: how far, and how long it has taken. Time sits
                // in a cell rather than on the wide line because it is the one
                // number on this screen that carries no unit, so it is the one
                // that can afford the narrow half.
                Row(Modifier.fillMaxWidth()) {
                    Metric(
                        Format.distanceValue(state.distanceM),
                        Format.distanceUnit(state.distanceM),
                        layout.primaryStyle,
                    )
                    Metric(
                        Format.duration(shownDuration),
                        Format.durationUnit(shownDuration),
                        layout.primaryStyle,
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
                        layout.secondaryStyle,
                    )
                    ComparisonMetric(state, layout.secondaryStyle)
                }

                // US-6: meaningless without a route, so absent until there is one
                // — but a *leading* route counts, marked as a guess. Withholding
                // it until commitment is what left a shared-prefix route family
                // with no arrival time for most of a workout (A.4b).
                val remaining = state.remainingM
                if (state.belief.hasRoute && remaining != null) {
                    Spacer(Modifier.height(3.dp))
                    val provisional = state.belief.isProvisional || state.positionStale
                    Text(
                        buildString {
                            if (provisional) append("≈ ")
                            if (layout.verboseEta) append("noch ")
                            append(Format.distance(remaining))
                            append(" · ")
                            if (layout.verboseEta) append("an ")
                            append(Format.arrival(state.arrivalAtUtcMs))
                        },
                        style = MaterialTheme.typography.bodyExtraSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = if (provisional) 0.6f else 1f),
                        maxLines = 1,
                    )
                }

                HealthLine(health, layout)
            }
        }
    }
}

/**
 * That these samples came out of a stored session rather than off the wrist.
 *
 * Its own line, in neither the belief half nor the health half, because it is a
 * fact about neither: the matcher below it is working normally and the recording
 * below that is healthy — on samples that are not being recorded. Absent in a
 * release build, where nothing can set it, and never suppressed otherwise, so
 * that no screenshot of a replay can be read as one of a workout.
 */
@Composable
private fun ReplayBanner(state: RecorderUiState) {
    val label = state.replayLabel ?: return
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = RouteTone.Assumed,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The route name, or the best thing the matcher can currently say instead.
 *
 * Green: worked out from today's track. Blue: assumed, because you ranked this
 * route above the others it is currently indistinguishable from. White with a
 * `≈`: the leading candidate, named before there is a commitment to it. Grey:
 * something true that is not a name — that this is a new route, that the app is
 * still comparing, that there are no routes yet.
 *
 * What is *not* here is any warning colour. This line is about knowledge, and
 * not knowing yet is not a fault.
 */
@Composable
private fun RouteHeadline(state: RecorderUiState) {
    val belief = state.belief
    val colour = when (belief.kind) {
        RouteBeliefKind.COMMITTED -> RouteTone.Confirmed
        RouteBeliefKind.ASSUMED -> RouteTone.Assumed
        RouteBeliefKind.LEADING -> RouteTone.Likely
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        Wording.route(belief),
        style = MaterialTheme.typography.labelSmall,
        color = colour,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The right-hand cell of the secondary pair: ahead or behind the usual time on
 * this ground, and nothing at all when there is no usual time to be had.
 *
 * It has held three different things. A grey dash, which said the app had given
 * up; then the session's average speed, which was genuinely the most useful
 * number available while the average lived nowhere else. The average is now the
 * headline, so that fallback would only print it twice — and a cell that repeats
 * the biggest number on the screen is worse than an empty one, because it costs
 * a glance to discover it says nothing new.
 */
@Composable
private fun RowScope.ComparisonMetric(
    state: RecorderUiState,
    style: TextStyle,
) {
    val delta = state.deltaVsMedianS
    if (delta == null) {
        // Nothing to compare against, so nothing here. It used to fall back to
        // the session average, which was worth showing when the average lived
        // nowhere else — it is now the headline number two rows up, and a screen
        // this small must not spend a cell saying the same thing twice.
        //
        // The space is held rather than collapsed: heart rate keeps its own half
        // of the row, so it does not slide to the centre and back the moment the
        // matcher finds something to compare.
        Spacer(Modifier.weight(1f))
        return
    }
    val colour = if (delta < 0) DeltaTone.Ahead else DeltaTone.Behind
    Metric(
        Format.delta(delta),
        // ± rather than Ø: the number is a signed deviation, not an average, and
        // Ø is already the session average's own mark two cells away.
        "±",
        style,
        // Dimmed rather than hidden while the projected position is stale: this
        // is the last honest comparison, and the alternative — recomputing it
        // against a position that has stopped moving — is what used to make a
        // GNSS outage look like the user slowing down.
        color = if (state.positionStale) colour.copy(alpha = 0.5f) else colour,
    )
}

/**
 * One line for the state of the recording, and a marker that is always drawn.
 *
 * The marker is the reassurance: an empty status line cannot be told apart from
 * a status line that is broken, so "everything is fine" is a green dot rather
 * than nothing at all.
 */
@Composable
private fun HealthLine(health: RecordingHealth, layout: LiveLayout) {
    val tone = when (health.severity) {
        HealthSeverity.OK -> HealthTone.Ok
        HealthSeverity.DEGRADED -> HealthTone.Degraded
        HealthSeverity.FAILED -> HealthTone.Failed
    }
    val healthy = health.kind == HealthKind.RECORDING
    Spacer(Modifier.height(3.dp))
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(tone, CircleShape))
        if (!healthy || layout.labelWhenHealthy) {
            Spacer(Modifier.width(4.dp))
            Text(
                Wording.health(health),
                style = MaterialTheme.typography.labelSmall,
                color = tone,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

/**
 * One cell of the metric grid: the number, with its unit set small beside it.
 *
 * Inline rather than stacked because vertical space is the scarce one here (see
 * [LivePage]); [valueStyle] is what ranks the cell, so the same helper serves
 * both the primary and the secondary pair.
 *
 * **It shrinks rather than clips.** A single line that does not fit used to be
 * cut at the right edge, and the unit is written last, so the unit was always
 * exactly what was lost: the distance cell read "12,98" with no "km" at all, and
 * a session average of 22,3 km/h read "22,3 Ø" — not a truncated number but a
 * different claim, and a silent one. The cell now steps the type down until the
 * whole of it fits, which is the trade the wrist wants: a number two points
 * smaller is still read at a glance, a number whose unit is missing is not.
 */
@Composable
private fun RowScope.Metric(
    value: String,
    unit: String,
    valueStyle: TextStyle,
    color: Color = MaterialTheme.colorScheme.onBackground,
) {
    val unitColor = MaterialTheme.colorScheme.onSurfaceVariant
    // Relative to the value rather than an absolute 12 sp, so that a cell forced
    // to give up four points does not keep a unit nearly as tall as its number.
    val unitSize = (UNIT_SP / valueStyle.fontSize.value).em
    BasicText(
        buildAnnotatedString {
            append(value)
            if (unit.isNotEmpty()) {
                withStyle(SpanStyle(fontSize = unitSize, color = unitColor)) { append(" $unit") }
            }
        },
        style = valueStyle.copy(color = color, textAlign = TextAlign.Center),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(
            minFontSize = MIN_METRIC_SP.sp,
            maxFontSize = valueStyle.fontSize,
            stepSize = 0.5.sp,
        ),
        modifier = Modifier.weight(1f),
    )
}

/** The unit's size at a cell's full size; scaled down with it from there. */
private const val UNIT_SP = 12f

/** Below this a metric is not glanceable, and the layout is wrong rather than
 *  the type too big. Nothing on either face has reached it. */
private const val MIN_METRIC_SP = 16f

// ------------------------------------------------------------------ edge bars

/**
 * The two bars down the sides of the screen: progress on the left, ahead/behind
 * on the right.
 *
 * Drawn behind the text rather than above or below it, which is the whole reason
 * they are affordable: they cost no vertical space at all, and the height budget
 * in [LivePage] is untouched. What they cost is width, and that is what the
 * gutter in [LiveLayout] pays.
 *
 * Geometry, in `drawArc`'s convention — zero degrees at three o'clock, positive
 * sweep clockwise:
 *
 *  - **Left**, 130° to 230°, filling upwards from the lower left, so the arc
 *    grows the way the workout does.
 *  - **Right**, anchored at 0° (three o'clock) with the sign of the sweep doing
 *    the work: negative runs anticlockwise, which is upwards, for ahead; positive
 *    runs down for behind. Full scale is [de.hecate.core.model.RouteProfile.deltaFullScaleS],
 *    the route's own interquartile spread rather than an arbitrary number of
 *    minutes.
 *
 * Both stop well short of twelve and six o'clock, where the clock and the pager
 * indicator live. An empty track is always drawn: a bar that is present and
 * empty says "no route yet", a bar that is absent says nothing at all.
 */
@Composable
private fun EdgeBars(state: RecorderUiState, layout: LiveLayout) {
    // Progress needs one specific route — it is a position along that route.
    // The delta does not: it is pooled across whatever the matcher is still
    // weighing, so the right-hand bar keeps working while the left one waits.
    val progress = if (state.belief.hasRoute) {
        state.progressFraction?.toFloat()?.coerceIn(0f, 1f)
    } else {
        null
    }
    val deltaFraction = deltaFractionOf(state)

    // Animated because the recorder publishes about once a second and an
    // un-animated bar steps visibly on every one of them.
    val shownProgress by animateFloatAsState(progress ?: 0f, tween(400), label = "progress")
    val shownDelta by animateFloatAsState(deltaFraction ?: 0f, tween(400), label = "delta")

    val faded = state.belief.isProvisional || state.positionStale
    val alpha = if (faded) 0.5f else 1f
    val trackColour = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
    val progressColour = RouteTone.Likely.copy(alpha = alpha)
    val deltaColour = (if (shownDelta >= 0f) DeltaTone.Ahead else DeltaTone.Behind).copy(alpha = alpha)

    Canvas(Modifier.fillMaxSize()) {
        val strokePx = layout.arcStroke.toPx()
        val inset = strokePx / 2f + layout.arcInset.toPx()
        val diameter = size.minDimension - 2f * inset
        if (diameter <= 0f) return@Canvas
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        val stroke = Stroke(width = strokePx, cap = StrokeCap.Round)

        fun arc(colour: Color, start: Float, sweep: Float) {
            if (abs(sweep) < MIN_VISIBLE_SWEEP) return
            drawArc(
                color = colour,
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = stroke,
            )
        }

        arc(trackColour, LEFT_START, ARC_SWEEP)
        arc(trackColour, -HALF_SWEEP, ARC_SWEEP)

        if (progress != null) arc(progressColour, LEFT_START, ARC_SWEEP * shownProgress)
        if (deltaFraction != null) arc(deltaColour, 0f, -HALF_SWEEP * shownDelta)

        // The median, ticked across the right-hand track so that a bar sitting
        // near zero is readably near zero rather than merely short.
        val radius = diameter / 2f
        val centreY = size.height / 2f
        val centreX = size.width / 2f
        drawLine(
            color = trackColour,
            start = Offset(centreX + radius - strokePx, centreY),
            end = Offset(centreX + radius + strokePx, centreY),
            strokeWidth = 1.dp.toPx(),
        )
    }
}

/**
 * Today's delta as a fraction of full scale, positive for ahead.
 *
 * The sign flip is deliberate: [de.hecate.core.model.MatchState.deltaVsMedianS]
 * is positive when *slower*, and a bar that grew upwards as the user fell behind
 * would be read backwards by everyone including its author.
 */
/**
 * The ahead/behind bar's deflection, or null when there is nothing to draw.
 *
 * The delta itself is pooled across the live candidates rather than taken from
 * one route, so it exists as soon as the matcher has any of them — including
 * while two routes are still indistinguishable. Only the *scale* comes from a
 * single route's own quartiles, and that is the one thing a tie cannot supply.
 * Falling back to a fixed scale there keeps the bar on screen through exactly
 * the stretch of a workout it used to vanish for: a route family sharing a long
 * prefix, where the watch knows perfectly well that you are a minute up.
 *
 * The fallback is deliberately coarse. It is a reading of "about how far ahead",
 * not a measurement against this route's own spread, and [DEFAULT_DELTA_SCALE_S]
 * is wide enough that a normal day sits well inside it rather than pinned.
 */
private fun deltaFractionOf(state: RecorderUiState): Float? {
    val delta = state.deltaVsMedianS ?: return null
    val scale = state.deltaScaleS?.takeIf { it > 0.0 } ?: DEFAULT_DELTA_SCALE_S
    return (-delta / scale).coerceIn(-1.0, 1.0).toFloat()
}

/** Full-scale deflection when no single route owns the bar: five minutes either
 *  way. See [deltaFractionOf]. */
private const val DEFAULT_DELTA_SCALE_S = 300.0

/** Where the arcs sit, in `drawArc` degrees. Both leave room at twelve and six
 *  o'clock for the clock and the pager indicator. */
private const val ARC_SWEEP = 100f
private const val LEFT_START = 130f
private const val HALF_SWEEP = ARC_SWEEP / 2f

/** Below this a round-capped arc is a dot rather than a bar, and a dot on an
 *  empty track reads as a value that is not there. */
private const val MIN_VISIBLE_SWEEP = 0.75f

// ------------------------------------------------------------------- health

/**
 * Turn the recorder's raw facts into the one thing worth saying about the
 * recording's health, at [nowMs].
 *
 * Ages are computed here, against the live clock, rather than stored: the states
 * that matter most are the ones where nothing is arriving to recompute them.
 */
private fun RecorderUiState.healthAt(nowMs: Long): RecordingHealth = recordingHealth(
    RecordingHealthInput(
        preparing = phase == RecorderPhase.PREPARING,
        paused = phase == RecorderPhase.PAUSED,
        autoPaused = autoPaused,
        hasLocationPermission = locationPermission,
        // Never had a fix? Then measure from the start of the session: a
        // recording that began without GPS — because the warm-up timed out — has
        // been without it for its whole length, and saying so beats a blank.
        fixAgeMs = (lastFixAtMs.takeIf { it > 0 } ?: startedAtUtcMs.takeIf { it > 0 })
            ?.let { (nowMs - it).coerceAtLeast(0) },
        gpsSignalLost = gpsSignalLost,
        sinceResumeMs = resumedAtMs.takeIf { it > 0 }?.let { (nowMs - it).coerceAtLeast(0) },
        sinceLastWriteMs = lastWriteAtMs.takeIf { it > 0 }?.let { (nowMs - it).coerceAtLeast(0) },
        accuracyM = lastAccuracyM,
        offRoute = offRoute,
        matcherDisabled = matcherDisabled,
        // Retired once it has been on the wrist long enough to have been read.
        // It stays on the start screen afterwards, which is where a message about
        // a finished session belongs.
        message = message?.takeIf { nowMs - messageAtMs in 0..MESSAGE_TTL_MS },
    ),
)

private const val MESSAGE_TTL_MS = 20_000L

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
                        else HealthTone.Degraded,
                    )
                }
            }
        }
    }
}
