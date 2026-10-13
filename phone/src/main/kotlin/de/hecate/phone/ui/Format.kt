package de.hecate.phone.ui

import de.hecate.core.display.Units
import de.hecate.core.geo.Geo
import de.hecate.core.model.SessionSource
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/** German formatting for the phone UI (§4: no localization). */
object Format {

    private val DE = Locale.GERMANY

    /** "4,20 km" or "840 m" — as the watch writes it. */
    fun distance(meters: Double): String = Units.distance(meters)

    /** "23:11" or "1:04:22" — as the watch writes it. */
    fun duration(millis: Long): String = Units.duration(millis)

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
        return Geo.kmh(meters, activeMs)
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
        val cal = localCalendar(utcMs, tzOffsetMinutes)
        return String.format(
            DE, "%s, %02d.%02d.%d · %02d:%02d",
            DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1],
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.YEAR),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
        )
    }

    fun date(utcMs: Long, tzOffsetMinutes: Int): String {
        val cal = localCalendar(utcMs, tzOffsetMinutes)
        return String.format(
            DE, "%02d.%02d.%02d",
            cal.get(Calendar.DAY_OF_MONTH), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.YEAR) % 100,
        )
    }

    fun timeOfDay(utcMs: Long, tzOffsetMinutes: Int): String {
        val cal = localCalendar(utcMs, tzOffsetMinutes)
        return String.format(DE, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }

    /** A UTC calendar shifted by the session's own offset, so its fields read
     *  as the wall clock where the session was recorded — not where the phone
     *  is now. */
    private fun localCalendar(utcMs: Long, tzOffsetMinutes: Int): Calendar =
        Calendar.getInstance(TimeZone.getTimeZone("UTC"), DE).apply {
            timeInMillis = utcMs + tzOffsetMinutes * 60_000L
        }

    private val DAYS = arrayOf("So", "Mo", "Di", "Mi", "Do", "Fr", "Sa")

    /** "412 m", rounded — a metre of altitude is already more precision than a
     *  barometer deserves, and a decimal point would only invite belief in it. */
    fun elevation(meters: Double): String = String.format(DE, "%d m", meters.roundToInt())

    /** "+240 m" or "−185 m". The minus is U+2212, as elsewhere. */
    fun elevationSigned(meters: Double): String {
        val v = meters.roundToInt()
        return if (v < 0) String.format(DE, "−%d m", -v) else String.format(DE, "+%d m", v)
    }

    fun hr(value: Double?): String = value?.let { String.format(DE, "%d", it.roundToInt()) } ?: "–"

    /** A route match's coverage as "0,87". */
    fun coverage(value: Double?): String = value?.let { String.format(DE, "%.2f", it) } ?: "–"

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

    /** "13°", a temperature where the unit is understood. The minus is U+2212. */
    fun temp(c: Double?): String {
        if (c == null) return "–"
        val v = c.roundToInt()
        return if (v < 0) "−${-v}°" else "$v°"
    }

    /** "12–14 °C", or "13 °C" when it did not change. */
    fun tempRange(min: Double?, max: Double?): String {
        if (min == null || max == null) return "–"
        val a = temp(min).removeSuffix("°")
        val b = temp(max).removeSuffix("°")
        return if (a == b) "$a °C" else "$a–$b °C"
    }

    /** "1,2 mm", or "0 mm" for anything that would round to nothing. */
    fun precip(mm: Double?): String = when {
        mm == null -> "–"
        mm < 0.05 -> "0 mm"
        mm < 10 -> String.format(DE, "%.1f mm", mm)
        else -> String.format(DE, "%d mm", mm.roundToInt())
    }

    /** "0,8 mm/h" — an intensity, as a weather strip labels it. */
    fun precipRate(mmH: Double?): String =
        if (mmH == null || mmH < 0.05) "" else String.format(DE, "%.1f mm/h", mmH)

    /**
     * What a recording is called on its own screen: "Morgenlauf",
     * "Abendradfahrt" — the time of day it started, in the session's own
     * clock, and the noun Health Connect also uses.
     */
    fun title(type: de.hecate.core.model.ExerciseType, utcMs: Long, tzOffsetMinutes: Int): String {
        val hour = localCalendar(utcMs, tzOffsetMinutes).get(Calendar.HOUR_OF_DAY)
        val part = when (hour) {
            in 5..10 -> "Morgen"
            in 11..13 -> "Mittags"
            in 14..17 -> "Nachmittags"
            in 18..21 -> "Abend"
            else -> "Nacht"
        }
        return part + type.sessionTitle.lowercase(DE)
    }

    /** "Sa, 10. Okt. · 08:12" — the subtitle under a title that is already
     *  about the time of day. The year only when it is not this one. */
    fun dayAndTime(utcMs: Long, tzOffsetMinutes: Int, nowUtcMs: Long = System.currentTimeMillis()): String {
        val cal = localCalendar(utcMs, tzOffsetMinutes)
        val year = cal.get(Calendar.YEAR)
        val thisYear = localCalendar(nowUtcMs, tzOffsetMinutes).get(Calendar.YEAR)
        return String.format(
            DE, "%s, %d. %s%s · %02d:%02d",
            DAYS[cal.get(Calendar.DAY_OF_WEEK) - 1],
            cal.get(Calendar.DAY_OF_MONTH),
            MONTHS_SHORT[cal.get(Calendar.MONTH)],
            if (year != thisYear) " $year" else "",
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.MINUTE),
        )
    }

    /** "Oktober 2026". */
    fun month(utcMs: Long, tzOffsetMinutes: Int): String {
        val cal = localCalendar(utcMs, tzOffsetMinutes)
        return "${MONTHS[cal.get(Calendar.MONTH)]} ${cal.get(Calendar.YEAR)}"
    }

    /** A month as a sortable key, 2026 * 12 + 9. */
    fun monthKey(utcMs: Long, tzOffsetMinutes: Int): Int {
        val cal = localCalendar(utcMs, tzOffsetMinutes)
        return cal.get(Calendar.YEAR) * 12 + cal.get(Calendar.MONTH)
    }

    /** "1:23" or "0:42" — a difference in time, signless; the caller says
     *  which way. */
    fun delta(millis: Long): String {
        val s = kotlin.math.abs(millis) / 1000
        return if (s >= 3600) String.format(DE, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(DE, "%d:%02d", s / 60, s % 60)
    }

    /** "1,0 km" for a split's end. */
    fun splitKm(meters: Double): String =
        if (meters % 1000.0 == 0.0) String.format(DE, "%d km", (meters / 1000).toInt())
        else String.format(DE, "%.1f km", meters / 1000)

    private val MONTHS = arrayOf(
        "Januar", "Februar", "März", "April", "Mai", "Juni",
        "Juli", "August", "September", "Oktober", "November", "Dezember",
    )
    private val MONTHS_SHORT = arrayOf(
        "Jan.", "Feb.", "März", "Apr.", "Mai", "Juni", "Juli", "Aug.", "Sep.", "Okt.", "Nov.", "Dez.",
    )
}
