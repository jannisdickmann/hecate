package de.hecate.wear.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Number formatting for the watch face. German conventions throughout (§4: no
 * localization, single user) — comma as the decimal separator, 24-hour clock.
 */
object Format {

    private val DE = Locale.GERMANY

    /** "4,20 km" above a kilometre, "840 m" below it. */
    fun distance(meters: Double): String =
        if (meters >= 1000) String.format(DE, "%.2f km", meters / 1000.0)
        else String.format(DE, "%d m", meters.roundToInt())

    /** "23:11" or "1:04:22". */
    fun duration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(DE, "%d:%02d:%02d", h, m, s)
        else String.format(DE, "%d:%02d", m, s)
    }

    /** Just the number of [distance], for the metric grid, which puts the unit
     *  on its own line. Kept beside [distanceUnit] so the two cannot disagree
     *  about where the kilometre boundary is. */
    fun distanceValue(meters: Double): String =
        if (meters >= 1000) String.format(DE, "%.2f", meters / 1000.0)
        else String.format(DE, "%d", meters.roundToInt())

    /** The unit [distanceValue] left off. */
    fun distanceUnit(meters: Double): String = if (meters >= 1000) "km" else "m"

    /** Just the number of [speed], for the metric grid. Same floor: below
     *  1 km/h there is nothing to report but a jittery fix. */
    fun speedValue(kmh: Double?): String {
        if (kmh == null || !kmh.isFinite() || kmh < 1.0) return "–"
        return String.format(DE, "%.1f", kmh)
    }

    /** Speed as "10,8 km/h". Never pace: this app speaks km/h everywhere.
     *
     *  The lower bound is the old one restated — a pace above 3600 s/km is a
     *  speed below 1 km/h, which is standing still with a jittery fix, not a
     *  number worth showing. */
    fun speed(kmh: Double?): String {
        if (kmh == null || !kmh.isFinite() || kmh < 1.0) return "–"
        return String.format(DE, "%.1f km/h", kmh)
    }

    /** Average speed so far, in km/h. Null below 50 m, where it is all noise. */
    fun speedFrom(distanceM: Double, activeMs: Long): Double? {
        if (distanceM < 50 || activeMs <= 0) return null
        return (distanceM / 1000.0) / (activeMs / 3_600_000.0)
    }

    /**
     * How long ago the watch's routes were worked out: "heute", "gestern",
     * "vor 3 Tagen", "vor 5 Wochen".
     *
     * This replaced the bundle's version number, which was on the start screen
     * for a while and answered nothing: the version is a counter the phone bumps
     * on every rebuild, so "v42" cannot tell you whether your routes are current
     * or three weeks stale, which is the only thing worth knowing about them
     * before you press start. The counter is still what the Data Layer and the
     * session header use; it is simply not a fact about the user's day.
     */
    fun age(builtAtUtcMs: Long, nowMs: Long): String {
        val days = ((nowMs - builtAtUtcMs) / 86_400_000L).toInt()
        return when {
            days <= 0 -> "heute"
            days == 1 -> "gestern"
            days < 14 -> "vor $days Tagen"
            else -> "vor ${days / 7} Wochen"
        }
    }

    /** "4 Routen", "1 Route". */
    fun routeCount(n: Int): String = if (n == 1) "1 Route" else "$n Routen"

    /** Signed seconds against the median, e.g. "−12 s" (faster) or "+45 s". */
    fun delta(seconds: Double?): String {
        if (seconds == null || !seconds.isFinite()) return "–"
        val v = seconds.roundToInt()
        val sign = if (v < 0) "−" else "+"
        val a = abs(v)
        return if (a >= 60) String.format(DE, "%s%d:%02d", sign, a / 60, a % 60)
        else String.format(DE, "%s%d s", sign, a)
    }

    /**
     * Wall-clock arrival time, "18:42", from the instant it was worked out for.
     *
     * Takes an instant rather than "seconds from now" for a reason that cost a
     * real ride its credibility: the display turned seconds into a clock time on
     * every recomposition, so an arrival that had stopped being updated — after
     * the matcher was disabled — walked a minute further into the future for
     * every minute the user looked at it. A fixed instant is either right or
     * visibly stale, which are the only two honest options.
     */
    fun arrival(atUtcMs: Long?): String {
        if (atUtcMs == null || atUtcMs <= 0) return "–"
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = atUtcMs }
        return String.format(
            DE, "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }

    fun heartRate(bpm: Int?): String = bpm?.toString() ?: "–"
}
