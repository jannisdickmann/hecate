package de.hecate.core.display

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The two figures both apps print the same way: a distance and a duration.
 *
 * German conventions (§4: no localization) — a comma for the decimal point and
 * a 24-hour clock. Here rather than in either app because the phone and the
 * watch each had a character-for-character copy of these two, and a recording
 * reads as one distance on the wrist and another on the phone only if somebody
 * edits one copy. Everything that differs between the two screens — units on
 * their own line, a dash below 1 km/h — stays in each app's own `Format`.
 */
object Units {

    private val DE = Locale.GERMANY

    /** "4,20 km" from a kilometre up, "840 m" below it. */
    fun distance(meters: Double): String =
        if (meters >= 1000) String.format(DE, "%.2f km", meters / 1000.0)
        else String.format(DE, "%d m", meters.roundToInt())

    /** "23:11" or, from an hour up, "1:04:22". Negative reads as zero. */
    fun duration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(DE, "%d:%02d:%02d", h, m, s)
        else String.format(DE, "%d:%02d", m, s)
    }
}
