package de.hecate.phone.export

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import de.hecate.core.gpx.Gpx
import de.hecate.phone.db.HecateDatabase
import de.hecate.phone.db.toSample
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * One recording or one route as a GPX file the share sheet can hand on.
 *
 * The file goes into a private cache directory and leaves through a
 * [FileProvider] Uri with a one-off read grant, so the receiving app — a
 * messenger, Drive, „In Dateien speichern" — gets that file and nothing else,
 * and this app still needs no storage permission. Where it ends up is the
 * user's choice made in the share sheet, as it is for the backup (NFR-7: the
 * app itself sends nothing anywhere).
 *
 * Every export clears the previous ones first. A shared file has to outlive
 * the share call, because the receiver reads it whenever it gets round to it,
 * so it cannot be deleted afterwards; clearing on the next export keeps the
 * directory at one file without racing the reader of the last one.
 */
class GpxExporter(
    private val context: Context,
    private val db: HecateDatabase,
) {

    /** What the share sheet needs: the Uri to grant, and a name to show. */
    data class Share(val uri: Uri, val title: String)

    /** Null if the recording is gone or has no position worth writing. */
    suspend fun session(id: String): Share? = withContext(Dispatchers.IO) {
        val session = db.sessions().byId(id) ?: return@withContext null
        val samples = db.sessions().samples(id).map { it.toSample() }
        val segments = Gpx.segments(session.startedAtUtc, samples)
        if (segments.isEmpty()) return@withContext null

        // Local time in the name, as the list shows it: "Lauf 2024-03-04 07-30"
        // is what the user will be looking for in a folder later. Hyphens rather
        // than colons, which half the file systems a share can land on refuse.
        val local = Instant.ofEpochMilli(session.startedAtUtc)
            .atOffset(ZoneOffset.ofTotalSeconds(session.tzOffsetMinutes * 60))
        val title = "${session.exerciseType.sessionTitle} ${local.format(FILE_TIME)}"

        write(title, Gpx.write(title, session.exerciseType, session.startedAtUtc, segments))
    }

    /** Null if the route is gone or has no geometry yet. */
    suspend fun route(id: Long): Share? = withContext(Dispatchers.IO) {
        val route = db.routes().byId(id) ?: return@withContext null
        val points = db.routes().points(id)
        if (points.isEmpty()) return@withContext null
        val profile = db.routes().profile(id)

        // Bins are stored sparsely by index; spread them out so that a bin's
        // index is its position in the list, which is what Gpx.routeSegment
        // reads them by.
        val altitudes = arrayOfNulls<Double>((profile.maxOfOrNull { it.bin } ?: -1) + 1)
        for (bin in profile) altitudes[bin.bin] = bin.medianAltM

        val segment = Gpx.routeSegment(
            points = points.map { it.lat to it.lon },
            cumDistM = points.map { it.cumDistM },
            medianAltM = altitudes.asList(),
        )
        if (segment.isEmpty()) return@withContext null

        write(route.name, Gpx.write(route.name, route.exerciseType, null, listOf(segment)))
    }

    private fun write(title: String, xml: String): Share {
        val dir = File(context.cacheDir, DIRECTORY)
        dir.listFiles()?.forEach { it.delete() }
        dir.mkdirs()
        val file = File(dir, Gpx.fileName(title))
        file.writeText(xml, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, authority(context), file)
        return Share(uri, file.name)
    }

    companion object {
        /** Must match the `cache-path` in `res/xml/file_paths.xml`. */
        private const val DIRECTORY = "gpx"

        private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm")

        /** Must match the provider's `android:authorities` in the manifest. */
        fun authority(context: Context): String = "${context.packageName}.files"
    }
}
