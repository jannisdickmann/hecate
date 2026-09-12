package de.hecate.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * The phone theme.
 *
 * The app was on Material 3 from the start but never had a theme: it called
 * `MaterialTheme {}` bare, got the baseline purple scheme, and then overrode it
 * everywhere with literal greys and teals. That is why it looked a decade old,
 * and why it had no dark mode — see Appendix A.
 *
 * `minSdk 34` (§4) puts this well past the API 31 floor for dynamic colour, so
 * there is no version fence and no hand-written fallback palette to keep in sync
 * with anything. The scheme is the wallpaper's, in whichever mode the system is
 * currently in.
 *
 * Not `MaterialExpressiveTheme`: the Material 3 Expressive surface — that theme,
 * `MotionScheme`, `ButtonGroup`, `LoadingIndicator`, the Flexible app bars — is
 * compiled into material3 1.4.0 but every piece of it is `internal` there, and
 * only becomes public in the 1.5.0 alphas. Reaching it means overriding the
 * Compose BOM with an alpha across the whole module, which §7 pins against and
 * which is a poor trade for what is, here, styling. The stable API below gets
 * the same theme, the same dynamic colour and the same type scale.
 */
@Composable
fun HecateTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
        content = content,
    )
}

/**
 * The two colours that carry meaning rather than style.
 *
 * Everything else in this app is a scheme role and moves with the wallpaper.
 * These do not: a route drawn on a map has a start and an end, and which is
 * which must not depend on what the user set as their background. They are read
 * as a pair against each other, not against the surface, so a fixed pair is the
 * honest choice.
 */
object HecateMapColors {
    /** Where the track began. */
    val Start = Color(0xFF43A047)

    /** Where it ended. */
    val End = Color(0xFFE53935)
}

/**
 * The route line itself, which *is* decorative, so it follows the scheme.
 *
 * Split out from [HecateMapColors] to keep the distinction visible at the call
 * site: this one is allowed to change with the wallpaper, those two are not.
 */
val routeLineColor: Color
    @Composable get() = MaterialTheme.colorScheme.primary
