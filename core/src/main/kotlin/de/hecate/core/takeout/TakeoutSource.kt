package de.hecate.core.takeout

import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/**
 * The one or more ZIP files a Google Takeout arrives as, read as a single
 * flat set of files.
 *
 * Takeout splits a large export into `takeout-<stamp>-1-001.zip`,
 * `-002.zip` and so on. Those are *not* a spanned archive — each part is a
 * complete, independently readable ZIP holding a different slice of the tree —
 * so reading them all and merging the entries is exactly right, and reading only
 * the first silently loses whatever landed in the others.
 *
 * Entries are addressed by their file name rather than their full path. The
 * folder above them has been "Fitbit" and is now "Google Health", it is
 * localised in some exports, and nothing in here needs to know which: a file
 * called `gps_location_2026-03-20.csv` is that day's positions wherever Takeout
 * decided to put it. Where two parts hold the same name — parts can overlap at
 * the seam — the first one opened wins, and the duplicate is reported rather
 * than silently dropped.
 */
class TakeoutSource(paths: List<File>) : Closeable {

    class TakeoutException(message: String, cause: Throwable? = null) : Exception(message, cause)

    private val zips: List<ZipFile>
    private val index = LinkedHashMap<String, Pair<ZipFile, String>>()

    /** Names that appeared in more than one part, for the import report. */
    val duplicateNames: List<String>

    init {
        if (paths.isEmpty()) throw TakeoutException("Kein Takeout-Archiv angegeben")
        val opened = ArrayList<ZipFile>(paths.size)
        val dupes = ArrayList<String>()
        try {
            for (path in paths.sortedBy { it.name }) {
                val zip = try {
                    ZipFile(path)
                } catch (e: Exception) {
                    throw TakeoutException("Kein lesbares ZIP: $path", e)
                }
                opened.add(zip)
                for (entry in zip.entries()) {
                    if (entry.isDirectory) continue
                    val name = entry.name.substringAfterLast('/')
                    if (index.putIfAbsent(name, zip to entry.name) != null) dupes.add(name)
                }
            }
        } catch (e: Throwable) {
            opened.forEach { runCatching { it.close() } }
            throw e
        }
        zips = opened
        duplicateNames = dupes
    }

    /** Every file name, in the order the parts were read. */
    fun names(): Set<String> = index.keys

    /** File names matching `<prefix><something>.csv`, sorted. */
    fun dated(prefix: String): List<String> =
        index.keys.filter { it.startsWith(prefix) && it.endsWith(".csv") }.sorted()

    fun has(name: String): Boolean = index.containsKey(name)

    /**
     * Read one file. The stream is decompressed on the fly and closed afterwards;
     * nothing is unpacked to disk, so a 300 MB takeout costs 300 MB of nothing.
     */
    fun <T> read(name: String, block: (java.io.BufferedReader) -> T): T {
        val (zip, path) = index[name] ?: throw TakeoutException("Nicht im Takeout: $name")
        return zip.getInputStream(zip.getEntry(path)).bufferedReader().use(block)
    }

    /** [read], but null instead of throwing when the file is not in the export. */
    fun <T> readIfPresent(name: String, block: (java.io.BufferedReader) -> T): T? =
        if (has(name)) read(name, block) else null

    override fun close() {
        zips.forEach { runCatching { it.close() } }
    }

    companion object {

        /**
         * Work out which files to open from what the user typed.
         *
         * - nothing at all: every `*.zip` in [defaultDir]
         * - a directory: every `*.zip` in it
         * - a path containing `*` or `?`: that glob, within its own parent
         * - a plain file: itself
         *
         * The glob case is the one worth having. `takeout-…-001.zip` is a whole
         * export when the export was small and one part of several when it was
         * not, and the difference is not visible from the file: passing
         * `takeout-…-*.zip` always means all of it.
         */
        fun resolve(pattern: String?, defaultDir: File): List<File> {
            val target = pattern?.let { File(it) }
            val (dir, glob) = when {
                target == null -> defaultDir to "*.zip"
                target.isDirectory -> target to "*.zip"
                pattern.contains('*') || pattern.contains('?') ->
                    (target.parentFile ?: File(".")) to target.name
                else -> return listOf(target)
            }
            if (!dir.isDirectory) throw TakeoutException("Kein Verzeichnis: $dir")
            val regex = globToRegex(glob)
            return dir.listFiles { f -> f.isFile && regex.matches(f.name) }
                ?.sortedBy { it.name }
                ?: emptyList()
        }

        private fun globToRegex(glob: String): Regex {
            val sb = StringBuilder()
            for (c in glob) {
                when (c) {
                    '*' -> sb.append(".*")
                    '?' -> sb.append('.')
                    else -> sb.append(Regex.escape(c.toString()))
                }
            }
            return Regex(sb.toString())
        }
    }
}
