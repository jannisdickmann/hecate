package de.hecate.core.archive

import de.hecate.core.io.SessionCodec
import de.hecate.core.model.RecordedSession
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * The ZIP layer of the backup format, with no Android and no Room in it.
 *
 * Split out from [ArchiveRepository] for one reason: this is the part that can
 * silently lose data — an unflushed writer, an entry name that does not match
 * what the reader looks for, a version check that passes when it should not —
 * and splitting it out is what lets it be tested without Android in the way. A
 * backup nobody has ever read back is not worth having, so this part carries the
 * tests that read one back; [ArchiveRepository]'s own are next door in `:phone`,
 * under Robolectric.
 *
 * Neither class holds more than one session in memory at a time.
 */
object ArchiveCodec {

    /**
     * Writes an archive. The caller drives it — manifest, database, then one call
     * per session — so that reading each session out of the database can happen
     * between writes without this class knowing about coroutines or Room.
     */
    class Writer(out: OutputStream) : Closeable {

        private val counting = CountingOutputStream(out)
        private val zip = ZipOutputStream(counting.buffered())

        /** Bytes written to the underlying stream. Only final after [close]. */
        val bytesWritten: Long get() = counting.count

        fun writeManifest(manifest: Archive.Manifest) =
            entry(Archive.MANIFEST, Archive.json.encodeToString(manifest))

        fun writeDatabase(database: Archive.Database) =
            entry(Archive.DATABASE, Archive.json.encodeToString(database))

        /** One session, in the watch's own JSONL format. */
        fun writeSession(session: RecordedSession) {
            zip.putNextEntry(ZipEntry(Archive.sessionEntry(session.id)))
            // Deliberately not SessionCodec.encodeSession: that builds the whole
            // file as one String, which for an hour at 1 Hz is megabytes held for
            // no reason. A writer flushed into the entry costs a buffer, and
            // writeSession is the same lines in the same order.
            val writer = zip.bufferedWriter()
            SessionCodec.writeSession(session, writer)
            // Flush rather than close: closing the writer would close the ZIP.
            writer.flush()
            zip.closeEntry()
        }

        private fun entry(name: String, text: String) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        override fun close() = zip.close()
    }

    /**
     * Reads an archive. Needs a [File] rather than a stream because ZIP is
     * random-access and a `content://` Uri is not; [ArchiveRepository] copies to
     * the cache first.
     *
     * The manifest and the database part are read eagerly, because an archive
     * missing either is not worth starting on. Sessions are read one at a time.
     */
    class Reader(file: File) : Closeable {

        private val zip = ZipFile(file)

        val manifest: Archive.Manifest = run {
            val text = text(Archive.MANIFEST)
                ?: fail("Keine Hecate-Sicherung: ${Archive.MANIFEST} fehlt")
            runCatching { Archive.json.decodeFromString<Archive.Manifest>(text) }
                .getOrElse { fail("Manifest unlesbar", it) }
        }

        val database: Archive.Database = run {
            if (manifest.formatVersion > Archive.FORMAT_VERSION) {
                fail(
                    "Sicherung hat Format ${manifest.formatVersion}, diese App kennt nur " +
                        "${Archive.FORMAT_VERSION}. Bitte die App aktualisieren.",
                )
            }
            val text = text(Archive.DATABASE)
                ?: fail("Keine Hecate-Sicherung: ${Archive.DATABASE} fehlt")
            runCatching { Archive.json.decodeFromString<Archive.Database>(text) }
                .getOrElse { fail("Datenbankteil unlesbar", it) }
        }

        /** The session with this id, or null when the archive has no file for it. */
        fun session(id: String): RecordedSession? =
            text(Archive.sessionEntry(id))?.let { SessionCodec.decodeSession(it.lineSequence()) }

        private fun text(name: String): String? =
            zip.getEntry(name)?.let { zip.getInputStream(it).bufferedReader().use { r -> r.readText() } }

        override fun close() = zip.close()
    }

    private fun fail(message: String, cause: Throwable? = null): Nothing =
        throw Archive.ArchiveException(message, cause)

    /** Counts bytes on the way through, for the "written N MB" line. */
    private class CountingOutputStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            out.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            count += len
        }

        override fun flush() = out.flush()
        override fun close() = out.close()
    }
}
