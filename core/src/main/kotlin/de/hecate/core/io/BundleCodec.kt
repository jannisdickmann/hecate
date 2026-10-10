package de.hecate.core.io

import de.hecate.core.model.HeartRate
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.Reading
import de.hecate.core.model.SessionEvent
import de.hecate.core.model.RouteBundle
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream

/**
 * Serialization of the route bundle pushed watch-wards, and of match logs.
 *
 * Gzipped JSON rather than protobuf: a bundle of fifty routes is a few hundred
 * kilobytes of coordinates, which gzip flattens to a fraction of that, and the
 * format stays readable with `zcat` when something goes wrong on the wrist (§4).
 */
object BundleCodec {

    val json: Json = Json {
        encodeDefaults = false
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encode(bundle: RouteBundle): String = json.encodeToString(bundle)

    fun decode(text: String): RouteBundle = json.decodeFromString(text)

    fun writeGzip(bundle: RouteBundle, out: OutputStream) = GzipText.write(out) { encode(bundle) }

    fun readGzip(input: InputStream): RouteBundle = GzipText.read(input, ::decode)

    fun encodeMatchLog(entries: List<MatchLogEntry>): String = encodeLines(entries)

    fun decodeMatchLog(text: String): List<MatchLogEntry> = decodeLines(text)

    /** The session's lifecycle events — pauses, GPS, service restarts — in the
     *  same line-per-entry shape as the match log, and stored the same way. The
     *  watch already writes these into the session file; this is what lets the
     *  phone keep them once that file is gone. */
    fun encodeEvents(events: List<SessionEvent>): String = encodeLines(events)

    fun decodeEvents(text: String): List<SessionEvent> = decodeLines(text)

    /**
     * A sensor's own series — altitude, speed — in the same line-per-entry shape,
     * and stored the same way.
     *
     * These exist for the reason the events table does: the watch writes them
     * into the session file, and until now whatever was not folded into a sample
     * column at ingest died with that file. For altitude that is most of it, the
     * barometer reporting once every half-minute against a position a second.
     */
    fun encodeReadings(readings: List<Reading>): String = encodeLines(readings)

    fun decodeReadings(text: String): List<Reading> = decodeLines(text)

    /** As [encodeReadings], for the heart rate, which keeps its own older shape
     *  because every session file and backup already written uses it. */
    fun encodeRates(rates: List<HeartRate>): String = encodeLines(rates)

    fun decodeRates(text: String): List<HeartRate> = decodeLines(text)

    /** One JSON object per line, joined by a bare newline and with none after
     *  the last: the shape every table above is stored in, written once. */
    private inline fun <reified T> encodeLines(items: List<T>): String =
        items.joinToString("\n") { json.encodeToString(it) }

    /** The inverse. Blank lines are skipped rather than failed on, so a file
     *  that gained a trailing newline somewhere along the way still reads. */
    private inline fun <reified T> decodeLines(text: String): List<T> =
        text.lineSequence().filter { it.isNotBlank() }
            .map { json.decodeFromString<T>(it) }.toList()
}
