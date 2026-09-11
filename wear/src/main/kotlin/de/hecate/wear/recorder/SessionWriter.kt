package de.hecate.wear.recorder

import android.os.SystemClock
import android.util.Log
import de.hecate.core.Tuning
import de.hecate.core.io.SessionCodec
import de.hecate.core.io.SessionRecord
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.Sample
import de.hecate.core.model.SessionEvent
import de.hecate.core.model.SessionFooter
import de.hecate.core.model.SessionHeader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * The append-only session file (NFR-1).
 *
 * Every observation is one line, appended and never rewritten, with an fsync at
 * least every [Tuning.FSYNC_INTERVAL_MS] and unconditionally on pause and stop.
 * Worst case on a power loss is ten seconds of samples and a torn final line,
 * which the reader discards.
 *
 * Not thread-safe by contract; the recorder feeds it from a single scope.
 */
class SessionWriter(val file: File) {

    private var out: FileOutputStream? = null
    private var lastSyncMs = 0L
    private var writeFailures = 0

    val exists: Boolean get() = file.exists()

    fun open() {
        if (out != null) return
        file.parentFile?.mkdirs()
        out = FileOutputStream(file, /* append = */ true)
        lastSyncMs = SystemClock.elapsedRealtime()
    }

    fun writeHeader(header: SessionHeader) = write(SessionRecord.Header(header), forceSync = true)

    fun writeSample(sample: Sample) = write(SessionRecord.SampleLine(sample))

    fun writeEvent(event: SessionEvent) = write(SessionRecord.EventLine(event), forceSync = true)

    fun writeMatch(entry: MatchLogEntry) = write(SessionRecord.MatchLine(entry))

    fun writeFooter(footer: SessionFooter) = write(SessionRecord.Footer(footer), forceSync = true)

    private fun write(record: SessionRecord, forceSync: Boolean = false) {
        val stream = out ?: run { open(); out } ?: return
        try {
            stream.write((SessionCodec.encodeLine(record) + "\n").toByteArray(Charsets.UTF_8))
            val now = SystemClock.elapsedRealtime()
            if (forceSync || now - lastSyncMs >= Tuning.FSYNC_INTERVAL_MS) {
                stream.flush()
                stream.fd.sync()
                lastSyncMs = now
            }
            writeFailures = 0
        } catch (e: IOException) {
            // Losing the workout is the one unrecoverable outcome, so a failed
            // write is retried on a fresh descriptor before anything is given up.
            writeFailures++
            Log.e(TAG, "Schreiben fehlgeschlagen (#$writeFailures)", e)
            runCatching { out?.close() }
            out = null
            if (writeFailures <= MAX_REOPEN) {
                runCatching { open(); write(record, forceSync = true) }
            }
        }
    }

    fun sync() {
        runCatching {
            out?.flush()
            out?.fd?.sync()
            lastSyncMs = SystemClock.elapsedRealtime()
        }
    }

    fun close() {
        sync()
        runCatching { out?.close() }
        out = null
    }

    private companion object {
        const val TAG = "SessionWriter"
        const val MAX_REOPEN = 3
    }
}
