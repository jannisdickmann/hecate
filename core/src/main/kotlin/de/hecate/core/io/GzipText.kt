package de.hecate.core.io

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Gzipped UTF-8 text, the way every stored blob in this project is written.
 *
 * The text is produced and parsed *inside* the gzip stream's `use`, exactly as
 * the codecs did when each spelled this out for itself: the stream — and with
 * it the caller's — is closed whether or not encoding or decoding throws.
 */
internal object GzipText {

    inline fun write(out: OutputStream, text: () -> String) {
        GZIPOutputStream(out).use { it.write(text().toByteArray(Charsets.UTF_8)) }
    }

    inline fun <T> read(input: InputStream, parse: (String) -> T): T =
        GZIPInputStream(input).use { parse(it.readBytes().toString(Charsets.UTF_8)) }
}
