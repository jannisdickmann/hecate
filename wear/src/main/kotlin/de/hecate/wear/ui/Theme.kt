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
 *  - **Status colours** ([HecateStatus]) are fixed. "Faster than usual" must be
 *    green on every watch face, not green-unless-your-watch-face-is-orange, so
 *    these deliberately opt out of dynamic theming.
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
 * Colours that mean something, held constant across watch faces.
 *
 * Chosen against a black background at the contrast the wrist needs in daylight:
 * these are read at a glance, mid-stride, through sweat.
 */
object HecateStatus {
    /** A route the matcher worked out from today's track (§5.6). */
    val Matched = Color(0xFF6EE7A8)

    /** A route assumed from the user's own ranking, not from the track. A
     *  different colour because it is a setting, not a finding (US-4). */
    val Assumed = Color(0xFF7FB8F5)

    /** Ahead of the median. Same green as [Matched] on purpose: both are "good". */
    val Ahead = Matched

    /** Behind the median, off route, or otherwise wrong. */
    val Behind = Color(0xFFFF8A80)

    /** Working on it: searching for a route, waiting for GPS, paused. */
    val Pending = Color(0xFFFFCC80)
}
