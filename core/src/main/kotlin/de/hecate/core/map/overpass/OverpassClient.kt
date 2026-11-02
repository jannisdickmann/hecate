package de.hecate.core.map.overpass

import de.hecate.core.Tuning
import de.hecate.core.map.LatLonBounds
import de.hecate.core.map.Level
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.GZIPInputStream

/**
 * Where the map data comes from.
 *
 * An interface with exactly one method, for exactly one reason: **no test may
 * ever open a socket.** Everything above this line — the planner, the builder,
 * the codec, the CLI — is then testable against a checked-in fixture response,
 * and `--offline` is a client that throws rather than a flag that is trusted to
 * be honoured.
 */
fun interface OverpassClient {
    /** The raw response body for one Overpass QL query. */
    fun fetch(query: String): String
}

/** Something went wrong on the way to or from Overpass. */
class OverpassException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The real client, over `java.net.URL` and `HttpURLConnection`.
 *
 * Deliberately **not** `java.net.http.HttpClient`, which Android does not have:
 * this class runs unchanged on the phone and on a laptop, and the alternative
 * was a second module for the CLI.
 *
 * `Accept-Encoding: gzip` is not an optimisation, it is the measurement: one
 * query over 44 km² of dense city returned 12.9 MB of JSON and 1.9 MB on the
 * wire, a ratio of 6.7.
 *
 * ### Manners
 *
 * Structural rather than aspirational — the client sleeps, so no caller can
 * forget to:
 *
 * - one request at a time, never in parallel;
 * - [Tuning.MAP_FETCH_PAUSE_MS] between requests, and `Retry-After` honoured
 *   when the server names a longer one;
 * - [Tuning.MAP_FETCH_BACKOFF_MS] on 429 and 504, which are the two ways a
 *   loaded instance says "not now";
 * - `[timeout:180]` inside the query, so the server gives up before we do;
 * - a `User-Agent` that says what this is, and nothing that says who.
 *
 * Nothing but a bounding box and that header is ever sent. The operator learns
 * which tile-grid-aligned squares of ground were asked for, which is the real
 * cost of this feature for an app whose first line is "no cloud, no network" —
 * never a track, never a position, never a time.
 */
class UrlOverpassClient(
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val pauseMs: Long = Tuning.MAP_FETCH_PAUSE_MS,
    private val backoffMs: LongArray = Tuning.MAP_FETCH_BACKOFF_MS,
    private val sleep: (Long) -> Unit = { if (it > 0) Thread.sleep(it) },
    private val open: (String) -> HttpURLConnection = { (URI(it).toURL().openConnection() as HttpURLConnection) },
) : OverpassClient {

    private val lock = Any()
    private var lastRequestAtMs = 0L

    override fun fetch(query: String): String = synchronized(lock) {
        var lastError: Exception? = null
        for (endpoint in endpoints) {
            for (attempt in 0..backoffMs.size) {
                pause()
                try {
                    return@synchronized request(endpoint, query)
                } catch (e: Retryable) {
                    lastError = e
                    if (attempt < backoffMs.size) sleep(maxOf(backoffMs[attempt], e.retryAfterMs))
                } catch (e: IOException) {
                    // A refused connection or a DNS failure is not something a
                    // pause fixes; move to the next instance immediately.
                    lastError = e
                    break
                }
            }
        }
        throw OverpassException(
            "Overpass antwortet nicht (${endpoints.size} Server versucht)",
            lastError,
        )
    }

    private fun pause() {
        val now = System.currentTimeMillis()
        val since = now - lastRequestAtMs
        if (lastRequestAtMs != 0L && since < pauseMs) sleep(pauseMs - since)
        lastRequestAtMs = System.currentTimeMillis()
    }

    private class Retryable(message: String, val retryAfterMs: Long) : IOException(message)

    private fun request(endpoint: String, query: String): String {
        val connection = open(endpoint)
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("Accept-Encoding", "gzip")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.outputStream.use { it.write(("data=" + urlEncode(query)).toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            if (code == 429 || code == 504 || code == 503) {
                throw Retryable("Overpass: HTTP $code", retryAfter(connection))
            }
            if (code !in 200..299) {
                throw OverpassException("Overpass: HTTP $code ${connection.responseMessage ?: ""}".trim())
            }
            val raw = connection.inputStream
            val stream = if (connection.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
                GZIPInputStream(raw)
            } else {
                raw
            }
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private fun retryAfter(connection: HttpURLConnection): Long =
        connection.getHeaderField("Retry-After")?.trim()?.toLongOrNull()?.times(1000L) ?: 0L

    /** `URLEncoder` is in the platform on both sides, and the query is ASCII
     *  but for the occasional umlaut in a comment. */
    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

    companion object {
        /** Three instances, tried in order. Not mitigation for all three being
         *  down — that is the price of this data source — but the usual case is
         *  one of them having a bad afternoon. */
        val DEFAULT_ENDPOINTS: List<String> = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )

        const val USER_AGENT: String = "hecate-basemap/1"
        private const val CONNECT_TIMEOUT_MS = 20_000
        private const val READ_TIMEOUT_MS = 200_000
    }
}

/**
 * The Overpass QL this app sends, and nothing else.
 *
 * Two statements: the ways inside the box, with their geometry, and the place
 * nodes inside a box grown by [Tuning.MAP_PLACE_MARGIN_M] — because a city's
 * node sitting just outside a region must not cost the label for a city that
 * fills the screen.
 *
 * The coarse level asks for a strictly smaller set, which is what makes a
 * whole-region prefetch a seventh of the bytes rather than all of them filtered
 * afterwards.
 */
object OverpassQuery {

    private const val TIMEOUT_S = 180

    private const val HIGHWAY_GROB = "motorway|trunk|primary|secondary|motorway_link|trunk_link|primary_link|secondary_link"
    private const val HIGHWAY_FEIN =
        "$HIGHWAY_GROB|tertiary|tertiary_link|unclassified|residential|living_street|pedestrian|" +
            "service|track|path|footway|cycleway|bridleway|steps"

    private const val PLACES = "city|town|village|suburb|borough|quarter|hamlet"

    fun build(box: LatLonBounds, level: Level, placeMarginM: Double = Tuning.MAP_PLACE_MARGIN_M): String {
        val b = bbox(box)
        val p = bbox(box.expandedBy(placeMarginM))
        val highway = if (level == Level.GROB) HIGHWAY_GROB else HIGHWAY_FEIN
        return buildString {
            append("[out:json][timeout:$TIMEOUT_S];\n")
            append("(\n")
            append("  way[\"highway\"~\"^($highway)$\"]($b);\n")
            append("  way[\"railway\"~\"^(rail|light_rail|narrow_gauge|tram|subway)$\"]($b);\n")
            append("  way[\"waterway\"~\"^(river|canal|stream|riverbank)$\"]($b);\n")
            append("  way[\"natural\"=\"water\"]($b);\n")
            append("  way[\"landuse\"~\"^(reservoir|basin)$\"]($b);\n")
            append(");\n")
            append("out geom;\n")
            append("node[\"place\"~\"^($PLACES)$\"][\"name\"]($p);\n")
            append("out;\n")
        }
    }

    /** Overpass wants south,west,north,east. Six decimals is 11 cm, and the
     *  boxes are tile-grid multiples, so this is exact rather than rounded. */
    fun bbox(b: LatLonBounds): String = "%.6f,%.6f,%.6f,%.6f".format(
        java.util.Locale.ROOT, b.minLat, b.minLon, b.maxLat, b.maxLon,
    )
}
