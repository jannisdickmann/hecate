package de.hecate.phone.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import de.hecate.core.map.MapClass
import de.hecate.core.model.Intensity

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

    /**
     * The route lines drawn over the heatmap, light and dark.
     *
     * Warm on purpose. The heat underneath is a one-hue blue ramp — magnitude,
     * light to dark — so an annotation drawn in any blue would be read as part
     * of the scale. The opposite hue separates the two layers at every step of
     * the ramp, which is also why the lines need no casing: a casing would erase
     * the very density it sits on.
     */
    val RouteOnHeatLight = Color(0xFFB4560F)
    val RouteOnHeatDark = Color(0xFFF0A868)
}

/**
 * The four intensity bands, on the track drawing and the altitude profile.
 *
 * Fixed, like the start and end markers above and for the same reason: these
 * carry meaning. Blue → green → orange → red is the order every heart-rate
 * display in the sport uses, so it is already read correctly by anybody who has
 * seen one, and it runs cool to hot, which is the one ordering of hues a person
 * infers without a legend. A wallpaper must not be able to turn "Höchstleistung"
 * into the colour of an easy day.
 *
 * A legend is still drawn under the chart, because four colours *in this order*
 * is a convention rather than a fact, and because the bands have names the user
 * set the boundaries of.
 */
object HecateIntensityColors {
    val Easy = Color(0xFF1E88E5)
    val Moderate = Color(0xFF2E9E5B)
    val Hard = Color(0xFFEF6C00)
    val Peak = Color(0xFFD32F2F)

    /** The colour of an effort band. Fixed, so it can be looked up while
     *  drawing, outside composition. */
    fun of(intensity: Intensity): Color = when (intensity) {
        Intensity.EASY -> Easy
        Intensity.MODERATE -> Moderate
        Intensity.HARD -> Hard
        Intensity.PEAK -> Peak
    }

    /**
     * The colour of a stretch that has no reading, on a drawing that is
     * [measured] elsewhere or not.
     *
     * The right answer depends on what is beside it. A drawing with no heart
     * rate anywhere is just a drawing, and takes the scheme's line colour. A
     * drawing that is *mostly* coloured has holes in it, and a hole drawn in the
     * scheme's primary — which under dynamic colour can be the brightest thing
     * on the screen — reads as a highlight: the eye is drawn to precisely the
     * stretches where nothing was measured. Absence has to be the quietest thing
     * on the picture, not the loudest.
     */
    @Composable
    fun unmeasured(measured: Boolean): Color = if (measured) Unmeasured else routeLineColor

    /** What a stretch with no reading looks like on a drawing that has readings
     *  elsewhere: the surface's own quiet grey, dimmed further. */
    val Unmeasured: Color
        @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
}

/** A point of a drawing coloured by effort: a track ([TrackPoint]) or a chart
 *  ([ChartPoint]). */
interface HeartRated {
    val hrBpm: Double?
}

/**
 * The band the segment ending at [index] belongs to: the mean of its two ends,
 * so a segment is coloured by what was happening along it. One definition for
 * the track and the charts, so the same second is the same colour on both.
 */
fun bandOf(points: List<HeartRated>, index: Int, peakHrBpm: Int): Intensity? {
    val a = points[index - 1].hrBpm
    val b = points[index].hrBpm
    val hr = if (a != null && b != null) (a + b) / 2.0 else a ?: b
    return Intensity.of(hr, peakHrBpm)
}

/**
 * The route line itself, which *is* decorative, so it follows the scheme.
 *
 * Split out from [HecateMapColors] to keep the distinction visible at the call
 * site: this one is allowed to change with the wallpaper, those two are not.
 */
val routeLineColor: Color
    @Composable get() = MaterialTheme.colorScheme.primary

/**
 * The dim OpenStreetMap ground under every track drawing (Appendix A.29).
 *
 * **Fixed literals per light and dark, not scheme roles**, for the reason
 * already written at the top of this file: dynamic colour can hand you a
 * near-black or a neon primary, and a wallpaper must never be able to make the
 * ground louder than the track. The loudest thing on this map is a mid grey at
 * 40 % against a track drawn at full opacity.
 *
 * **There are two palettes**, and the difference is water. On the heatmap views
 * the heat is a one-hue blue ramp, so a blue river crossing it would be read as
 * a value on that scale — the same argument that already made the route lines
 * warm ([HecateMapColors.RouteOnHeatLight]). There, water is drawn in the same
 * neutral ink as the roads. On a route drawing there is no ramp, so water is
 * blue and reads as water.
 */
data class BasemapColors(
    val water: Color,
    val waterway: Color,
    val rail: Color,
    val motorway: Color,
    val primary: Color,
    val minor: Color,
    val service: Color,
    val path: Color,
    val urban: Color,
    val label: Color,
    val labelHalo: Color,
    val dot: Color,
) {
    fun of(cls: MapClass): Color = when (cls) {
        MapClass.WATER_AREA -> water
        MapClass.WATERWAY -> waterway
        MapClass.RAIL -> rail
        MapClass.MOTORWAY -> motorway
        MapClass.PRIMARY -> primary
        MapClass.MINOR -> minor
        MapClass.SERVICE -> service
        MapClass.PATH -> path
        MapClass.URBAN -> urban
    }

    /** Stroke weight [dp], so a road is the same width on every screen rather
     *  than a hairline on a dense one. */
    fun widthDpOf(cls: MapClass): Float = when (cls) {
        MapClass.MOTORWAY -> 1.6f
        MapClass.PRIMARY -> 1.2f
        MapClass.WATERWAY -> 1.2f
        MapClass.RAIL -> 1.0f
        MapClass.MINOR -> 0.9f
        MapClass.SERVICE -> 0.7f
        else -> 0.6f
    }

    companion object {
        /** Neutral ink: near-black on a light ground, near-white on a dark one.
         *  Everything on the map is this colour at some alpha, which is what
         *  makes the map read as one quiet layer rather than as a palette. */
        private fun ink(dark: Boolean) = if (dark) Color(0xFFE9E6E0) else Color(0xFF1C1A17)

        private fun halo(dark: Boolean) =
            if (dark) Color(0xFF14130F).copy(alpha = 0.80f) else Color(0xFFFBFAF7).copy(alpha = 0.85f)

        private fun base(dark: Boolean, water: Color, waterway: Color): BasemapColors {
            val i = ink(dark)
            return BasemapColors(
                water = water,
                waterway = waterway,
                rail = i.copy(alpha = 0.30f),
                motorway = i.copy(alpha = 0.40f),
                primary = i.copy(alpha = 0.33f),
                minor = i.copy(alpha = 0.23f),
                service = i.copy(alpha = 0.16f),
                path = i.copy(alpha = 0.14f),
                urban = i.copy(alpha = 0.10f),
                label = i.copy(alpha = 0.62f),
                labelHalo = halo(dark),
                dot = i.copy(alpha = 0.50f),
            )
        }

        /** For track maps (`rememberTrackMap`): water is blue, because nothing else on the
         *  picture is. */
        fun forTrack(dark: Boolean): BasemapColors = base(
            dark,
            water = if (dark) Color(0xFF2B4257).copy(alpha = 0.55f) else Color(0xFF9FC3DC).copy(alpha = 0.45f),
            waterway = if (dark) Color(0xFF5E8FB3).copy(alpha = 0.55f) else Color(0xFF6A9EC4).copy(alpha = 0.50f),
        )

        /** For the heatmap views: water in the same ink as the roads, so it
         *  cannot be mistaken for a step on the blue density ramp. */
        fun forHeat(dark: Boolean): BasemapColors = base(
            dark,
            water = ink(dark).copy(alpha = 0.13f),
            waterway = ink(dark).copy(alpha = 0.26f),
        )
    }
}
