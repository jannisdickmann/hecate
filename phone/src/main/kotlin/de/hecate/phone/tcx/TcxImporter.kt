package de.hecate.phone.tcx

import android.content.Context
import android.net.Uri
import android.util.Log
import de.hecate.core.tcx.TcxParser
import de.hecate.phone.ingest.SessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * US-9: history backfill from Google Health's per-workout TCX export.
 *
 * The point is the cold start (§7): without it nothing works for weeks, because
 * a route needs three recordings before it exists. With it, the app knows the
 * user's routes on the day it is installed.
 */
class TcxImporter(
    private val context: Context,
    private val repository: SessionRepository,
) {

    data class Report(
        val imported: Int = 0,
        val duplicates: Int = 0,
        val failed: Int = 0,
        val messages: List<String> = emptyList(),
    ) {
        val total: Int get() = imported + duplicates + failed
    }

    /** Import a multi-selection from the file picker. */
    suspend fun import(uris: List<Uri>): Report = withContext(Dispatchers.IO) {
        var imported = 0
        var duplicates = 0
        var failed = 0
        val messages = ArrayList<String>()

        for (uri in uris) {
            try {
                val sessions = context.contentResolver.openInputStream(uri)?.use { stream ->
                    TcxParser.parse(stream) { startedAt, index -> idFor(uri, startedAt, index) }
                } ?: emptyList()

                for (session in sessions) {
                    when (val r = repository.ingest(session, triggerFollowUp = false)) {
                        is SessionRepository.IngestResult.Stored -> imported++
                        is SessionRepository.IngestResult.Duplicate -> duplicates++
                        is SessionRepository.IngestResult.Rejected -> {
                            failed++
                            messages.add("${name(uri)}: ${r.reason}")
                        }
                    }
                }
            } catch (e: Exception) {
                failed++
                messages.add("${name(uri)}: ${e.message ?: "unlesbar"}")
                Log.w(TAG, "Import von $uri fehlgeschlagen", e)
            }
        }

        // One clustering pass for the whole import rather than one per file.
        if (imported > 0) repository.queueFollowUp()
        Report(imported, duplicates, failed, messages)
    }

    /**
     * A stable id derived from the start time, so re-importing the same export
     * is recognised as the same session even before the time-window check.
     */
    private fun idFor(uri: Uri, startedAtUtcMs: Long, index: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("tcx|$startedAtUtcMs|$index".toByteArray(Charsets.UTF_8))
        val hex = digest.take(16).joinToString("") { "%02x".format(it) }
        return "tcx-$hex"
    }

    private fun name(uri: Uri): String = uri.lastPathSegment?.substringAfterLast('/') ?: uri.toString()

    private companion object {
        const val TAG = "TcxImporter"
    }
}
