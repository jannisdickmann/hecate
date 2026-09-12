package de.hecate.wear.data

import android.content.Context
import de.hecate.core.io.SessionCodec
import de.hecate.core.model.RecordedSession
import java.io.File

/**
 * The watch's own storage: one append-only file per session in app-internal
 * storage, plus a marker per session the phone has acknowledged.
 *
 * A session file is deleted only after the phone says the session is in the
 * database (US-2). Until then it is re-offered on every service start, which is
 * what makes a recording survive a reboot, a crash, or a phone that was switched
 * off for two days.
 */
class SessionStore(context: Context) {

    private val root: File = File(context.filesDir, "sessions").apply { mkdirs() }
    private val ackDir: File = File(context.filesDir, "acked").apply { mkdirs() }

    fun fileFor(sessionId: String): File = File(root, "session-$sessionId.jsonl")

    /** Every session file currently held, oldest first. */
    fun all(): List<File> =
        root.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            ?.sortedBy { it.lastModified() }
            ?: emptyList()

    fun sessionIdOf(file: File): String =
        file.name.removePrefix("session-").removeSuffix(".jsonl")

    /** Sessions not yet acknowledged by the phone. */
    fun unacked(): List<File> = all().filterNot { isAcked(sessionIdOf(it)) }

    fun isAcked(sessionId: String): Boolean = File(ackDir, sessionId).exists()

    /**
     * Record the phone's acknowledgement and drop the payload. The marker outlives
     * the file so a duplicate ack, or a redelivered one, is harmless.
     */
    fun markAcked(sessionId: String) {
        File(ackDir, sessionId).writeText("")
        fileFor(sessionId).delete()
        pruneMarkers()
    }

    /**
     * Throw a session away without ever offering it to the phone.
     *
     * Distinct from [markAcked], which records that the phone has it. Nothing
     * has it; there was nothing to have. The ack marker is written anyway, so a
     * copy that somehow reached the Data Layer earlier cannot come back as a
     * pending upload.
     */
    fun discard(sessionId: String) {
        fileFor(sessionId).delete()
        File(ackDir, sessionId).writeText("")
        pruneMarkers()
    }

    fun read(file: File): RecordedSession? =
        runCatching { file.bufferedReader().useLines { SessionCodec.decodeSession(it) } }.getOrNull()

    /** Anything left behind by a process that died mid-recording. */
    fun hasPending(): Boolean = unacked().isNotEmpty()

    /** Ack markers are tiny, but they should not accumulate for ever. */
    private fun pruneMarkers() {
        val markers = ackDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (markers.size <= MAX_ACK_MARKERS) return
        markers.take(markers.size - MAX_ACK_MARKERS).forEach { it.delete() }
    }

    private companion object {
        const val MAX_ACK_MARKERS = 500
    }
}
