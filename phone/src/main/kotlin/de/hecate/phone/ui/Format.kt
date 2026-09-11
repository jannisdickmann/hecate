package de.hecate.phone.ui

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** German formatting for the phone UI (§4: no localization). */
object Format {

    private val DE = Locale.GERMANY

    fun distance(meters: Double): String =
        if (meters >= 1000) String.format(DE, "%.2f km", meters / 1000.0)
        else String.format(DE, "%d m", meters.roundToInt())

    fun duration(millis: Long): String {
        val total = (millis / 1000).coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) String.format(DE, "%d:%02d:%02d", h, m, s)
        else String.format(DE, "%d:%02d", m, s)
    }

    fun pace(secondsPerKm: Double?): String {
        if (secondsPerKm == null || !secondsPerKm.isFinite() || secondsPerKm <= 0) return "–"
        val t = secondsPerKm.roundToInt()
        return String.format(DE, "%d:%02d", t / 60, t % 60)
    }

    /** "Mo, 04.03.2024 · 08:30" in the session's own time zone. */
    fun dateTime(utcMs: Long, tzOffsetMinutes: Int): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), DE).apply {
            timeInMillis = utcMs + tzOffsetMinutes * 60_000L
        }
        val days = arrayOf("So", "Mo", "Di", "Mi", "Do", "Fr", "Sa")
        return String.format(
            DE, "%s, %02d.%02d.%d · %02d:%02d",
            days[cal.get(Calendar.DAY_OF_WEEK) - 1],
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.YEAR),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
        )
    }

    fun date(utcMs: Long, tzOffsetMinutes: Int): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), DE).apply {
            timeInMillis = utcMs + tzOffsetMinutes * 60_000L
        }
        return String.format(
            DE, "%02d.%02d.%02d",
            cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR) % 100,
        )
    }

    fun timeOfDay(utcMs: Long, tzOffsetMinutes: Int): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), DE).apply {
            timeInMillis = utcMs + tzOffsetMinutes * 60_000L
        }
        return String.format(DE, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    fun hr(value: Double?): String = value?.let { String.format(DE, "%d", it.roundToInt()) } ?: "–"

    fun exerciseType(name: String): String = when (name) {
        "RUNNING" -> "Laufen"
        "WALKING" -> "Gehen"
        "BIKING" -> "Radfahren"
        else -> name
    }

    fun hcState(state: Int): String = when (state) {
        0 -> "ausstehend"
        1 -> "geschrieben"
        2 -> "fehlgeschlagen"
        else -> "übersprungen"
    }
}
