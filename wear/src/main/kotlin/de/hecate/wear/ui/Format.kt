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

    /** Pace as "5:32 /km"; null when there is nothing meaningful to show. */
    fun pace(secondsPerKm: Double?): String {
        if (secondsPerKm == null || !secondsPerKm.isFinite() || secondsPerKm <= 0 || secondsPerKm > 3600) return "–"
        val total = secondsPerKm.roundToInt()
        return String.format(DE, "%d:%02d /km", total / 60, total % 60)
    }

    fun paceFrom(distanceM: Double, activeMs: Long): Double? {
        if (distanceM < 50 || activeMs <= 0) return null
        return (activeMs / 1000.0) / (distanceM / 1000.0)
    }

    /** Signed seconds against the median, e.g. "−12 s" (faster) or "+45 s". */
    fun delta(seconds: Double?): String {
        if (seconds == null || !seconds.isFinite()) return "–"
        val v = seconds.roundToInt()
        val sign = if (v < 0) "−" else "+"
        val a = abs(v)
        return if (a >= 60) String.format(DE, "%s%d:%02d", sign, a / 60, a % 60)
        else String.format(DE, "%s%d s", sign, a)
    }

    /** Wall-clock arrival time, "Ankunft 18:42". */
    fun arrival(nowMs: Long, secondsFromNow: Double?): String {
        if (secondsFromNow == null || !secondsFromNow.isFinite() || secondsFromNow < 0) return "–"
        val at = nowMs + (secondsFromNow * 1000).toLong()
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = at }
        return String.format(
            DE, "%02d:%02d",
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }

    fun heartRate(bpm: Int?): String = bpm?.toString() ?: "–"
}
