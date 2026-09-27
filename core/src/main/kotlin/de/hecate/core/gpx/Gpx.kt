package de.hecate.core.gpx

import de.hecate.core.Tuning
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.Sample
import java.time.Instant
import java.util.Locale

/**
 * GPX 1.1 out of a recording or a route, for handing to other apps.
 *
 * Write-only on purpose. The app reads TCX because that is what Google Health
 * exports; GPX is what everything else — Komoot, Strava, OsmAnd, a friend's
 * phone — reads, so it is the format a track leaves in.
 *
 * Heart rate goes into Garmin's TrackPointExtension, which is the one extension
 * the readers that understand heart rate in GPX at all agree on. A reader that
 * does not know it skips it, as the schema allows.
 */
object Gpx {

    /** Registered with IANA as of 2023. Some share targets only look at the
     *  extension, which is why [fileName] always ends in `.gpx`. */
    const val MIME = "application/gpx+xml"

    data class Point(
        val lat: Double,
        val lon: Double,
        val eleM: Double? = null,
        val timeUtcMs: Long? = null,
        val hrBpm: Int? = null,
    )

    /**
     * A recording as track segments, one per stretch between pauses.
     *
     * Paused samples are left out, exactly as the detail screen leaves them out
     * of the drawing; a pause ends the segment it interrupts, so a reader does
     * not draw a straight line across whatever happened while it lasted — a
     * café, a train. Samples without a finite position are skipped rather than
     * written, since a GPX point without coordinates is invalid.
     */
    fun segments(startedAtUtcMs: Long, samples: List<Sample>): List<List<Point>> {
        val out = ArrayList<List<Point>>()
        var current = ArrayList<Point>()
        for (s in samples) {
            if (s.paused) {
                if (current.isNotEmpty()) {
                    out.add(current)
                    current = ArrayList()
                }
                continue
            }
            if (!s.lat.isFinite() || !s.lon.isFinite()) continue
            current.add(
                Point(
                    lat = s.lat,
                    lon = s.lon,
                    eleM = s.altM?.takeIf { it.isFinite() },
                    timeUtcMs = startedAtUtcMs + s.tOffsetMs,
                    hrBpm = s.hrBpm?.takeIf { it > 0 },
                ),
            )
        }
        if (current.isNotEmpty()) out.add(current)
        return out
    }

    /**
     * A route's geometry as a single untimed segment, with the median altitude
     * of its members where the profile has one.
     *
     * [medianAltM] is indexed by profile bin, and the bins are
     * [Tuning.RESAMPLE_M] apart along the route by construction — which is what
     * lets a point's distance along the route say which bin it is in.
     */
    fun routeSegment(
        points: List<Pair<Double, Double>>,
        cumDistM: List<Double>,
        medianAltM: List<Double?>,
    ): List<Point> = points.mapIndexedNotNull { i, (lat, lon) ->
        if (!lat.isFinite() || !lon.isFinite()) return@mapIndexedNotNull null
        val bin = cumDistM.getOrNull(i)?.let { (it / Tuning.RESAMPLE_M).toInt() }
        Point(lat, lon, eleM = bin?.let { medianAltM.getOrNull(it) }?.takeIf { it.isFinite() })
    }

    /**
     * The document itself. [timeUtcMs] is when the recording started, or null
     * for a route, which is not an event and has no time of its own.
     */
    fun write(
        name: String,
        type: ExerciseType?,
        timeUtcMs: Long?,
        segments: List<List<Point>>,
    ): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        append("""<gpx version="1.1" creator="Hecate"""")
        append(""" xmlns="http://www.topografix.com/GPX/1/1"""")
        append(""" xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1"""")
        append(""" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"""")
        append(
            """ xsi:schemaLocation="http://www.topografix.com/GPX/1/1""" +
                """ http://www.topografix.com/GPX/1/1/gpx.xsd">""",
        ).append('\n')

        append("  <metadata>\n")
        append("    <name>").append(escape(name)).append("</name>\n")
        timeUtcMs?.let { append("    <time>").append(time(it)).append("</time>\n") }
        append("  </metadata>\n")

        append("  <trk>\n")
        append("    <name>").append(escape(name)).append("</name>\n")
        type?.let { append("    <type>").append(typeName(it)).append("</type>\n") }
        for (segment in segments) {
            if (segment.isEmpty()) continue
            append("    <trkseg>\n")
            for (p in segment) point(p)
            append("    </trkseg>\n")
        }
        append("  </trk>\n")
        append("</gpx>\n")
    }

    /**
     * A file name for [base] that every share target and file system accepts:
     * no path separators, nothing Windows refuses, never empty.
     */
    fun fileName(base: String): String {
        val cleaned = base
            .map { c -> if (c.isISOControl() || c in FORBIDDEN) '_' else c }
            .joinToString("")
            .trim()
            .trim('.')
            .take(MAX_NAME_LENGTH)
            .trim()
        return (cleaned.ifEmpty { "hecate" }) + ".gpx"
    }

    private fun StringBuilder.point(p: Point) {
        append("      <trkpt lat=\"").append(coordinate(p.lat))
        append("\" lon=\"").append(coordinate(p.lon)).append("\">")
        // Element order is fixed by the schema: ele, time, then extensions.
        p.eleM?.let { append("<ele>").append(String.format(Locale.ROOT, "%.1f", it)).append("</ele>") }
        p.timeUtcMs?.let { append("<time>").append(time(it)).append("</time>") }
        p.hrBpm?.let {
            append("<extensions><gpxtpx:TrackPointExtension>")
            append("<gpxtpx:hr>").append(it).append("</gpxtpx:hr>")
            append("</gpxtpx:TrackPointExtension></extensions>")
        }
        append("</trkpt>\n")
    }

    /** Seven decimals is about a centimetre, which is finer than any fix the
     *  watch has ever reported. */
    private fun coordinate(v: Double): String = String.format(Locale.ROOT, "%.7f", v)

    /** ISO 8601 in UTC with a `Z`, which is what `xsd:dateTime` in GPX means
     *  in practice; offsets are legal but not every reader honours them. */
    private fun time(utcMs: Long): String = Instant.ofEpochMilli(utcMs).toString()

    /** Strava's and Garmin's vocabulary, which is the closest thing GPX `type`
     *  has to a standard one. */
    fun typeName(type: ExerciseType): String = when (type) {
        ExerciseType.RUNNING -> "running"
        ExerciseType.WALKING -> "walking"
        ExerciseType.HIKING -> "hiking"
        ExerciseType.BIKING -> "cycling"
        ExerciseType.SKIING -> "skiing"
    }

    private fun escape(s: String): String = buildString(s.length) {
        for (c in s) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                // XML 1.0 has no escape for these; dropping them beats writing
                // a file no parser will open.
                else -> if (c >= ' ' || c == '\t' || c == '\n' || c == '\r') append(c)
            }
        }
    }

    private const val FORBIDDEN = "/\\:*?\"<>|"
    private const val MAX_NAME_LENGTH = 80
}
