package de.hecate.core.io

import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.RouteBundle
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

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

    fun writeGzip(bundle: RouteBundle, out: OutputStream) {
        GZIPOutputStream(out).use { it.write(encode(bundle).toByteArray(Charsets.UTF_8)) }
    }

    fun readGzip(input: InputStream): RouteBundle =
        GZIPInputStream(input).use { decode(it.readBytes().toString(Charsets.UTF_8)) }

    fun encodeMatchLog(entries: List<MatchLogEntry>): String =
        entries.joinToString("\n") { json.encodeToString(it) }

    fun decodeMatchLog(text: String): List<MatchLogEntry> =
        text.lineSequence().filter { it.isNotBlank() }
            .map { json.decodeFromString<MatchLogEntry>(it) }.toList()
}
