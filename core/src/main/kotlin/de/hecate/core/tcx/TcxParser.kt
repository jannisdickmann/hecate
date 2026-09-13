package de.hecate.core.tcx

import de.hecate.core.model.ActivityNames
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.Sample
import de.hecate.core.model.SessionEndReason
import de.hecate.core.model.SessionFooter
import de.hecate.core.model.SessionHeader
import de.hecate.core.model.SessionSource
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.InputStream
import java.io.StringReader
import java.time.Instant
import java.time.ZoneId
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal TCX reader for history backfill (US-9).
 *
 * Uses the DOM parser from the JDK/Android platform rather than a library: TCX is
 * a flat, shallow format and a per-workout export is a couple of megabytes at
 * most, so there is nothing to stream and nothing to add to the dependency list
 * (NFR-8).
 *
 * Only what the app needs is read — time, position, heart rate, distance. Cadence,
 * power, laps and extensions are ignored.
 */
object TcxParser {

    class TcxException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * Parse one TCX document. Returns one session per `<Activity>`; most exports
     * contain exactly one.
     *
     * @param zone the time zone the workout happened in, used only when the file
     *        does not say — see [offsetMinutesFor]. The default is the zone of
     *        whatever is running the import, which on a phone is the right answer
     *        for a history the owner of that phone recorded.
     * @param idFor produces the session id; callers pass a UUID or a hash so that
     *        re-importing the same file yields the same id.
     */
    fun parse(
        input: InputStream,
        zone: ZoneId = ZoneId.systemDefault(),
        idFor: (startedAtUtcMs: Long, index: Int) -> String,
    ): List<RecordedSession> {
        val doc = try {
            val f = DocumentBuilderFactory.newInstance()
            f.isNamespaceAware = false
            // Backfill files come from the user's own export, but an XML parser
            // that resolves external entities is never worth the risk.
            //
            // Every line of this hardening is best-effort, because the two platforms
            // disagree about it. Android's factory does not merely ignore
            // `setXIncludeAware` — it throws UnsupportedOperationException from the
            // base class — and it does not recognise Xerces' feature names either,
            // while the desktop JVM accepts all of them. Setting any of it directly
            // therefore fails every import on a phone while every test on a laptop
            // passes.
            runCatching { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { f.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { f.isXIncludeAware = false }
            runCatching { f.isExpandEntityReferences = false }

            // The guarantee, as opposed to the hope above: an entity resolver that
            // hands back nothing resolves every external reference to the empty
            // string on both platforms, whatever the factory did with the flags.
            f.newDocumentBuilder()
                .apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }
                .parse(input)
        } catch (e: Exception) {
            throw TcxException("TCX konnte nicht gelesen werden", e)
        }

        val activities = doc.getElementsByTagName("Activity")
        val out = ArrayList<RecordedSession>()
        // Kept so that a file holding nothing but a sport this app does not
        // record can say which one, rather than "keine Aktivität gefunden".
        val unsupported = LinkedHashSet<String>()
        for (i in 0 until activities.length) {
            val a = activities.item(i) as? Element ?: continue
            val sport = a.getAttribute("Sport") ?: ""
            val type = exerciseTypeOf(sport)
            if (type == null) {
                unsupported.add(sport.ifBlank { "ohne Angabe" })
                continue
            }
            parseActivity(a, type, i, zone, idFor)?.let { out.add(it) }
        }
        if (out.isEmpty()) {
            if (unsupported.isNotEmpty()) {
                throw TcxException("Sportart wird nicht unterstützt: ${unsupported.joinToString(", ")}")
            }
            throw TcxException("Keine Aktivität in der Datei gefunden")
        }
        return out
    }

    private fun parseActivity(
        activity: Element,
        type: ExerciseType,
        index: Int,
        zone: ZoneId,
        idFor: (Long, Int) -> String,
    ): RecordedSession? {
        val trackpoints = activity.getElementsByTagName("Trackpoint")
        if (trackpoints.length == 0) return null

        data class Raw(val tMs: Long, val lat: Double?, val lon: Double?, val hr: Int?, val dist: Double?, val alt: Double?)

        val raws = ArrayList<Raw>(trackpoints.length)
        for (i in 0 until trackpoints.length) {
            val tp = trackpoints.item(i) as? Element ?: continue
            val t = child(tp, "Time")?.textContent?.let { parseIso8601Millis(it) } ?: continue
            val pos = child(tp, "Position")
            val lat = pos?.let { child(it, "LatitudeDegrees")?.textContent?.toDoubleOrNull() }
            val lon = pos?.let { child(it, "LongitudeDegrees")?.textContent?.toDoubleOrNull() }
            val hr = child(tp, "HeartRateBpm")?.let { child(it, "Value")?.textContent?.toIntOrNull() }
            val dist = child(tp, "DistanceMeters")?.textContent?.toDoubleOrNull()
            val alt = child(tp, "AltitudeMeters")?.textContent?.toDoubleOrNull()
            raws.add(Raw(t, lat, lon, hr, dist, alt))
        }
        if (raws.isEmpty()) return null
        raws.sortBy { it.tMs }

        val id = child(activity, "Id")?.textContent?.let { parseIso8601(it) }
        val startUtc = id?.utcMs ?: raws.first().tMs

        // A TCX export records no pauses, so wall-clock offset is also active time.
        // A gap longer than the dropout threshold is treated as a pause for the
        // purposes of active duration, which is the best an import can do.
        var active = 0L
        var prevT = raws.first().tMs
        val samples = ArrayList<Sample>(raws.size)
        for (r in raws) {
            val dt = r.tMs - prevT
            if (dt in 0..de.hecate.core.Tuning.MAX_INTERPOLATION_GAP_MS) active += dt
            prevT = r.tMs
            if (r.lat == null || r.lon == null) continue
            samples.add(
                Sample(
                    tOffsetMs = r.tMs - startUtc,
                    activeMs = active,
                    lat = r.lat,
                    lon = r.lon,
                    altM = r.alt,
                    hrBpm = r.hr,
                    accuracyM = null,
                    cumDistanceM = r.dist,
                ),
            )
        }
        if (samples.size < 2) return null

        val header = SessionHeader(
            sessionId = idFor(startUtc, index),
            startedAtUtcMs = startUtc,
            tzOffsetMinutes = id?.offsetMinutes ?: offsetMinutesFor(startUtc, zone),
            exerciseType = type,
            source = SessionSource.IMPORT_TCX,
        )
        val footer = SessionFooter(
            endedAtUtcMs = raws.last().tMs,
            activeDurationMs = active,
            totalDistanceM = raws.mapNotNull { it.dist }.maxOrNull() ?: 0.0,
            reason = SessionEndReason.IMPORTED,
        )
        return RecordedSession(header, samples, emptyList(), footer)
    }

    private fun child(e: Element, name: String): Element? {
        val kids = e.childNodes
        for (i in 0 until kids.length) {
            val n = kids.item(i)
            if (n.nodeType == Node.ELEMENT_NODE && n.nodeName == name) return n as Element
        }
        return null
    }

    /**
     * The TCX `Sport` attribute to a type, or null for one this app does not record.
     *
     * The TCX schema only ever promised `Running`, `Biking` and `Other`, and no
     * exporter has felt bound by that for years, so this is [ActivityNames] —
     * the same table the takeout importer uses — rather than a list of three.
     *
     * Null rather than falling back to [ExerciseType.RUNNING], which is what this
     * used to do: a ski tour exported as TCX then imported as a run, silently,
     * and the clusterer got a route nobody can ever run (Appendix A.18).
     */
    internal fun exerciseTypeOf(sport: String?): ExerciseType? = ActivityNames.typeOf(sport)

    /**
     * The zone offset, in minutes, in force at [utcMs] in [zone].
     *
     * Resolved at the instant of the workout rather than at the moment of import,
     * which is the whole point: a run from January and a run from July in the same
     * zone get +60 and +120, and both then display at the time they happened.
     */
    internal fun offsetMinutesFor(utcMs: Long, zone: ZoneId): Int =
        zone.rules.getOffset(Instant.ofEpochMilli(utcMs)).totalSeconds / 60

    /** An ISO-8601 timestamp: the instant, and the offset the text spelled out —
     *  null for `Z` and for a bare local time, neither of which says anything
     *  about where the workout was. */
    internal data class Iso8601(val utcMs: Long, val offsetMinutes: Int?)

    internal fun parseIso8601Millis(raw: String): Long? = parseIso8601(raw)?.utcMs

    /**
     * Parse the ISO-8601 timestamps TCX uses, by hand: the accepted shapes are
     * few — `2024-03-17T06:12:33Z`, with optional fractional seconds, or with a
     * `+hh:mm` offset — and a `DateTimeFormatter` per trackpoint is a poor trade
     * for a file with thirty thousand of them.
     */
    internal fun parseIso8601(raw: String): Iso8601? {
        val s = raw.trim()
        if (s.length < 19) return null
        return try {
            val year = s.substring(0, 4).toInt()
            val month = s.substring(5, 7).toInt()
            val day = s.substring(8, 10).toInt()
            val hour = s.substring(11, 13).toInt()
            val min = s.substring(14, 16).toInt()
            val sec = s.substring(17, 19).toInt()
            var ms = 0L
            var i = 19
            if (i < s.length && s[i] == '.') {
                var frac = 0
                var digits = 0
                i++
                while (i < s.length && s[i].isDigit()) {
                    if (digits < 3) { frac = frac * 10 + (s[i] - '0'); digits++ }
                    i++
                }
                while (digits < 3) { frac *= 10; digits++ }
                ms = frac.toLong()
            }
            var offsetMin: Int? = null
            if (i < s.length) {
                when (s[i]) {
                    'Z', 'z' -> Unit
                    '+', '-' -> {
                        val sign = if (s[i] == '-') -1 else 1
                        val oh = s.substring(i + 1, i + 3).toInt()
                        val om = if (s.length >= i + 6) s.substring(i + 4, i + 6).toInt() else 0
                        offsetMin = sign * (oh * 60 + om)
                    }
                    else -> Unit
                }
            }
            val utcMs = (daysFromCivil(year, month, day) * 86_400_000L) +
                hour * 3_600_000L + min * 60_000L + sec * 1_000L + ms -
                (offsetMin ?: 0) * 60_000L
            Iso8601(utcMs, offsetMin)
        } catch (_: Exception) {
            null
        }
    }

    /** Days since the Unix epoch for a civil date (Howard Hinnant's algorithm). */
    internal fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y.toLong(), 400L)
        val yoe = y - era * 400
        val mp = (m + 9) % 12
        val doy = (153 * mp + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }
}
