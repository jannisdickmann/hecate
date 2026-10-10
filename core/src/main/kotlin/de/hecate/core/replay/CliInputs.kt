package de.hecate.core.replay

import de.hecate.core.archive.ArchiveCodec
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * What the laptop commands — [ReplayMain]'s and `KarteCli`'s — read their input
 * through, so that the same backup or directory is opened the same way and
 * refused with the same words by every one of them.
 *
 * The messages are what a person sees when a path is wrong, and there were six
 * copies of them; they are written here once rather than once per command.
 */
internal object CliInputs {

    /**
     * A backup, open; or null with the reason already on stderr.
     *
     * [shownAs] is the path as the user typed it, which is what the message
     * names. It defaults to [file]'s own spelling, which is the same thing
     * unless the argument had a redundant slash in it.
     *
     * Only the opening is guarded. What a command then reads out of the archive
     * is its own business, and a failure there is not "not a backup".
     */
    fun openArchive(file: File, shownAs: String = file.toString()): ArchiveCodec.Reader? {
        if (!file.isFile) {
            System.err.println("no such file: $shownAs")
            return null
        }
        return try {
            ArchiveCodec.Reader(file)
        } catch (e: Exception) {
            System.err.println("not a Hecate backup: $shownAs (${e.message})")
            null
        }
    }

    /** The session files in [dir], as `fixtures/` holds them — `*.jsonl` and
     *  `*.json`, by name — or none when it cannot be listed. */
    fun sessionFiles(dir: File): List<File> =
        dir.listFiles { f -> f.isFile && (f.name.endsWith(".jsonl") || f.name.endsWith(".json")) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /** "2026-09-11 14:32", in the time zone the session was recorded in — which
     *  for a holiday's worth of workouts is not this laptop's. */
    fun localStamp(utcMs: Long, tzOffsetMinutes: Int): String =
        LOCAL_STAMP.withZone(ZoneOffset.ofTotalSeconds(tzOffsetMinutes * 60)).format(Instant.ofEpochMilli(utcMs))

    private val LOCAL_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
}
