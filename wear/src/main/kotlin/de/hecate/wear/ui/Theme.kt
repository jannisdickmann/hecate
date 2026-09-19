package de.hecate.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme

/**
 * The watch theme.
 *
 * Wear Material 3 replaces the Material 2.5 look this app shipped with first —
 * see Appendix A. Two kinds of colour live here and they must not be confused:
 *
 *  - **Scheme colours** (buttons, cards, body text) come from the watch face via
 *    `dynamicColorScheme`, so the app looks like it belongs on this watch. They
 *    carry no meaning; they are whatever the user picked.
 *  - **Status colours** ([RouteTone], [DeltaTone], [HealthTone]) are fixed.
 *    "Faster than usual" must be green on every watch face, not
 *    green-unless-your-watch-face-is-orange, so these deliberately opt out of
 *    dynamic theming.
 */
@Composable
fun HecateWearTheme(content: @Composable () -> Unit) {
    // minSdk 36 (§4), so dynamic colour is always present — no version fence and
    // no static fallback scheme to keep in sync. `dynamicColorScheme` still
    // returns null if the watch face declines to publish one, and MaterialTheme's
    // own default is the right answer then.
    MaterialTheme(
        colorScheme = dynamicColorScheme(LocalContext.current) ?: MaterialTheme.colorScheme,
        content = content,
    )
}

/**
 * What the app knows about the route.
 *
 * Split from [HealthTone] on purpose, and the split is the point: these two used
 * to share one palette, so "two routes still plausible" — a healthy matcher
 * doing its job — was drawn in the same warning orange as "no GPS fix". Nothing
 * in this object is allowed to look like a problem, because none of it is one.
 *
 * Chosen against a black background at the contrast the wrist needs in daylight:
 * these are read at a glance, mid-stride, through sweat.
 */
object RouteTone {
    /** A route the matcher worked out from today's track (§5.6). */
    val Confirmed = Color(0xFF6EE7A8)

    /** A route assumed from the user's own ranking, not from the track. A
     *  different colour because it is a setting, not a finding (US-4). */
    val Assumed = Color(0xFF7FB8F5)

    /** The leading candidate, named before there is a commitment to it. Plain
     *  bright text: it is neither a finding nor a fault, just the current best
     *  answer. */
    val Likely = Color(0xFFF2F2F7)
}

/** Today against the route's own history. Green and red here mean fast and slow,
 *  never good and broken — the health line owns that meaning. */
object DeltaTone {
    val Ahead = RouteTone.Confirmed
    val Behind = Color(0xFFFF8A80)
}

/**
 * Whether the recording is working. The only colours on the live screen that are
 * allowed to alarm, and the reason the route line no longer can.
 */
object HealthTone {
    /** Everything is landing on disk. Drawn, not omitted: "fine" has to look
     *  different from "this line is broken". */
    val Ok = RouteTone.Confirmed

    /** Working, but with something worth knowing: paused, warming up, a poor
     *  fix, no heart rate permission. */
    val Degraded = Color(0xFFFFCC80)

    /** Off route, no fix at all, or nothing reaching the file. */
    val Failed = DeltaTone.Behind
}
