package de.hecate.phone.ui

import de.hecate.core.model.SessionSource
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

    /** Speed as "10,8". Never pace: this app speaks km/h everywhere. The unit is
     *  left off so the number fits a narrow column; callers with room for it
     *  append " km/h". */
    fun speed(kmh: Double?): String {
        if (kmh == null || !kmh.isFinite() || kmh <= 0) return "–"
        return String.format(DE, "%.1f", kmh)
    }

    /** "28,4 km/h", a speed as a chart labels it. Zero is zero, not a dash:
     *  standing at a light is the slowest a ride was. */
    fun speedLabel(kmh: Double): String = String.format(DE, "%.1f km/h", kmh.coerceAtLeast(0.0))

    /** km/h from a distance and the active time taken over it. */
    fun speedFrom(meters: Double, activeMs: Long): Double? {
        if (meters <= 0 || activeMs <= 0) return null
        return (meters / 1000.0) / (activeMs / 3_600_000.0)
    }

    /** "14,8 MB" or "412 kB". Decimal units, as file managers show them. */
    fun fileSize(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(DE, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(DE, "%d kB", bytes / 1_000)
        else -> String.format(DE, "%d B", bytes)
    }

    /** The provenance line in the session detail view. */
    fun source(source: SessionSource): String = when (source) {
        SessionSource.WATCH -> "Uhr"
        SessionSource.IMPORT_TCX -> "TCX-Import"
        SessionSource.IMPORT_TAKEOUT -> "Google-Health-Takeout"
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

    /** "412 m", rounded — a metre of altitude is already more precision than a
     *  barometer deserves, and a decimal point would only invite belief in it. */
    fun elevation(meters: Double): String = String.format(DE, "%d m", meters.roundToInt())

    /** "+240 m" or "−185 m". The minus is U+2212, as elsewhere. */
    fun elevationSigned(meters: Double): String {
        val v = meters.roundToInt()
        return if (v < 0) String.format(DE, "−%d m", -v) else String.format(DE, "+%d m", v)
    }

    fun hr(value: Double?): String = value?.let { String.format(DE, "%d", it.roundToInt()) } ?: "–"

    /**
     * A route name short enough to sit in a list row: at most
     * [MAX_ROUTE_NAME_CHARS] characters, then an ellipsis.
     *
     * Cut here rather than left to the text layout, because the pill it goes in
     * is as wide as its content and a name a route inherited from a long file
     * name pushed the row's date and figures off the screen. Twenty characters
     * is about as much as a person reads to recognise a route they named
     * themselves; the whole name is one tap away on the recording's own screen,
     * which has a line to spare for it.
     *
     * The ellipsis is U+2026, one character rather than three dots, so the
     * budget is spent on the name.
     */
    fun routeName(name: String): String =
        if (name.length <= MAX_ROUTE_NAME_CHARS) name
        else name.take(MAX_ROUTE_NAME_CHARS).trimEnd() + "…"

    const val MAX_ROUTE_NAME_CHARS: Int = 20

    /**
     * A route priority as the user sees it: "−1", "0", "+1".
     *
     * Signed on both sides, because the scale is centred and a bare "1" beside a
     * "−1" reads as a rank rather than as a preference. The minus is U+2212, as
     * everywhere else in this app.
     */
    fun priority(value: Int): String = when {
        value > 0 -> "+$value"
        value < 0 -> "−${-value}"
        else -> "0"
    }

    fun hcState(state: Int): String = when (state) {
        0 -> "ausstehend"
        1 -> "geschrieben"
        2 -> "fehlgeschlagen"
        else -> "übersprungen"
    }
}
