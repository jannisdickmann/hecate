package de.hecate.phone.io

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException

/**
 * A `content://` Uri as a file the app can seek in.
 *
 * ZIP needs random access and a content Uri is a one-shot stream, so anything
 * the user hands the app through the Storage Access Framework is copied into the
 * cache first and deleted afterwards. That was true of the backup archive
 * already; the map bundle made it true twice, which is what lifted it out of
 * `ArchiveRepository` into a file of its own.
 */
object CacheFile {

    /**
     * Copy [uri] into the cache directory and hand back the file. The caller
     * deletes it — see [use], which is the form worth reaching for.
     */
    fun copy(context: Context, uri: Uri, prefix: String, suffix: String = ".zip"): File {
        val temp = File.createTempFile(prefix, suffix, context.cacheDir)
        val input = context.contentResolver.openInputStream(uri)
            ?: run {
                temp.delete()
                throw IOException("Datei konnte nicht geöffnet werden")
            }
        input.use { source -> temp.outputStream().use { source.copyTo(it) } }
        return temp
    }

    /** [copy], with the deletion attached. */
    inline fun <T> use(context: Context, uri: Uri, prefix: String, block: (File) -> T): T {
        val temp = copy(context, uri, prefix)
        return try {
            block(temp)
        } finally {
            temp.delete()
        }
    }
}
