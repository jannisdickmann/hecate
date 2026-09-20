package de.hecate.core.map

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** One covered patch of ground, as the settings screen and the fetch planner
 *  both want to talk about it. */
@Serializable
data class MapRegion(
    val name: String,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
) {
    val bounds: LatLonBounds get() = LatLonBounds(minLat, minLon, maxLat, maxLon)

    companion object {
        fun of(name: String, b: LatLonBounds) = MapRegion(name, b.minLat, b.minLon, b.maxLat, b.maxLon)
    }
}

/** When one tile was fetched, and at which level. */
@Serializable
data class TileStamp(val level: Int, val row: Int, val col: Int, val builtAtUtcMs: Long) {
    val id: TileId get() = TileId(row, col)
}

/**
 * Everything about the store that is not inside a tile: small, human-readable,
 * and the file both the settings screen and the fetch planner read.
 */
@Serializable
data class KarteIndex(
    val formatVersion: Int = FORMAT_VERSION,
    val attribution: String = ATTRIBUTION,
    val regions: List<MapRegion> = emptyList(),
    val tiles: List<TileStamp> = emptyList(),
) {
    companion object {
        const val FORMAT_VERSION: Int = 1

        /** ODbL obliges this to be on screen where the map is, not only in a
         *  licence file. It is shown twice — in settings, and under the
         *  full-screen heatmap. */
        const val ATTRIBUTION: String = "Kartendaten © OpenStreetMap-Mitwirkende (ODbL)"
    }
}

/**
 * The map on disk: **a directory of small files**, not one big file and not Room.
 *
 * `<root>/<level>/<row>_<col>.hmt`, a few kilobytes each, about six hundred of
 * them for the twelve regions a real history covers.
 *
 * - A growable store is the whole point. An incremental fetch appends tiles, and
 *   one `renameTo` per tile makes each one atomic; a single monolithic file
 *   would have to be rewritten on every fetch, and a fetch interrupted half way
 *   would take the map with it.
 * - **Not Room.** A map is not the user's data. `ArchiveRepository.export` walks
 *   the tables, so a map table would silently add megabytes to every backup —
 *   against the rule already written on the settings card, that the archive
 *   holds "das Einzige darin, was sonst nirgends steht". Tiles are re-fetchable
 *   and do not belong in a backup. Staying out of Room also means no schema v8,
 *   no migration and no `MigrationTest` case.
 */
class TileStore(val root: File) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun dir(level: Level): File = File(root, level.dir)

    fun file(level: Level, id: TileId): File = File(dir(level), TileCodec.fileName(id))

    fun has(level: Level, id: TileId): Boolean = file(level, id).isFile

    /** Which of [ids] are not on disk yet, in a stable order. */
    fun missing(level: Level, ids: Collection<TileId>): List<TileId> =
        ids.distinct().filterNot { has(level, it) }.sorted()

    /** Every tile present at [level], read off the directory rather than out of
     *  the index — the filesystem is what a reader can actually open. */
    fun present(level: Level): List<TileId> =
        dir(level).listFiles()?.mapNotNull { TileCodec.parseFileName(it.name) }?.sorted() ?: emptyList()

    /**
     * One tile, or null when it was never fetched.
     *
     * A tile that *is* there but cannot be read is a different thing entirely
     * and throws [TileFormatException] — a half-written file after a power cut
     * should be noticed and re-fetched, not silently treated as empty ground.
     */
    fun read(level: Level, id: TileId): Tile? {
        val f = file(level, id)
        if (!f.isFile) return null
        return TileCodec.decode(id, f.readBytes())
    }

    /** As [read], but a corrupt tile is deleted and reported as absent. On a
     *  phone there is nobody to tell, and the tile is re-fetchable. */
    fun readOrDiscard(level: Level, id: TileId): Tile? = try {
        read(level, id)
    } catch (_: TileFormatException) {
        file(level, id).delete()
        null
    }

    /** Write one tile atomically: a reader sees either the previous tile or the
     *  new one, never half of either. */
    fun write(tile: Tile) {
        val dir = dir(tile.level)
        dir.mkdirs()
        val target = file(tile.level, tile.id)
        val temp = File(dir, target.name + ".part")
        temp.writeBytes(TileCodec.encode(tile))
        if (!temp.renameTo(target)) {
            // Windows and some SAF-backed volumes refuse a rename onto an
            // existing file. Losing the old tile for an instant is acceptable;
            // losing it permanently is not, hence the retry rather than a delete
            // up front.
            target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                throw java.io.IOException("Kachel ${target.name} konnte nicht geschrieben werden")
            }
        }
    }

    // ------------------------------------------------------------------- index

    fun indexFile(): File = File(root, INDEX_NAME)

    fun index(): KarteIndex {
        val f = indexFile()
        if (!f.isFile) return KarteIndex()
        return try {
            json.decodeFromString(KarteIndex.serializer(), f.readText())
        } catch (_: Exception) {
            // The index is a convenience; the tiles are the data. A damaged one
            // is rebuilt from the directory on the next write rather than
            // taking the map down with it.
            KarteIndex()
        }
    }

    fun writeIndex(index: KarteIndex) {
        root.mkdirs()
        val temp = File(root, "$INDEX_NAME.part")
        temp.writeText(json.encodeToString(KarteIndex.serializer(), index))
        temp.renameTo(indexFile()) || run { indexFile().delete(); temp.renameTo(indexFile()) }
    }

    /** Fold the tiles now on disk, and [regions], into the stored index. */
    fun refreshIndex(regions: List<MapRegion> = index().regions, nowMs: Long = System.currentTimeMillis()) {
        val previous = index().tiles.associateBy { it.level to it.id }
        val stamps = ArrayList<TileStamp>()
        for (level in Level.entries) {
            for (id in present(level)) {
                val known = previous[level.ordinal to id]
                stamps.add(TileStamp(level.ordinal, id.row, id.col, known?.builtAtUtcMs ?: nowMs))
            }
        }
        writeIndex(KarteIndex(regions = regions, tiles = stamps))
    }

    // ------------------------------------------------------------------- stats

    data class Stats(val tiles: Int, val bytes: Long, val newestUtcMs: Long?, val regions: Int) {
        val isEmpty: Boolean get() = tiles == 0
    }

    fun stats(): Stats {
        var tiles = 0
        var bytes = 0L
        var newest: Long? = null
        for (level in Level.entries) {
            dir(level).listFiles()?.forEach { f ->
                if (TileCodec.parseFileName(f.name) == null) return@forEach
                tiles++
                bytes += f.length()
                val t = f.lastModified()
                if (newest == null || t > newest!!) newest = t
            }
        }
        indexFile().takeIf { it.isFile }?.let { bytes += it.length() }
        return Stats(tiles, bytes, newest, index().regions.size)
    }

    /** Remove everything. The off switch for the whole feature: there is no
     *  toggle, because a toggle is a fifth state to test for something that was
     *  asked for unconditionally. */
    fun clear() {
        root.deleteRecursively()
    }

    // ---------------------------------------------------------------- transfer

    /**
     * The store as one ZIP, so a map built over ethernet on a laptop can be
     * carried onto the phone instead of fetched again over mobile data.
     */
    fun packInto(out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            indexFile().takeIf { it.isFile }?.let { f ->
                zip.putNextEntry(ZipEntry(INDEX_NAME))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            for (level in Level.entries) {
                for (id in present(level)) {
                    zip.putNextEntry(ZipEntry("${level.dir}/${TileCodec.fileName(id)}"))
                    file(level, id).inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Unpack such a ZIP into this store, merging rather than replacing.
     *
     * Every entry is validated by decoding it before it is kept, so a file that
     * is not a tile — or a path trying to climb out of the store — never reaches
     * the directory the app draws from. Returns how many tiles were taken.
     */
    fun unpack(input: InputStream): Int {
        var taken = 0
        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory) {
                    if (name == INDEX_NAME) {
                        val text = zip.readBytes().toString(Charsets.UTF_8)
                        runCatching { json.decodeFromString(KarteIndex.serializer(), text) }
                            .getOrNull()?.let { incoming ->
                                writeIndex(index().copy(regions = (index().regions + incoming.regions).distinct()))
                            }
                    } else {
                        parseEntry(name)?.let { (level, id) ->
                            val bytes = zip.readBytes()
                            // Decoding is the validation. An entry that is not a
                            // tile, or is a tile from a future format, is
                            // skipped rather than installed and drawn as
                            // nothing.
                            runCatching { TileCodec.decode(id, bytes) }.getOrNull()?.let { tile ->
                                write(Tile(id, level, tile.builtAtUtcMs, tile.features, tile.places, tile.urban))
                                taken++
                            }
                        }
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        if (taken > 0) refreshIndex()
        return taken
    }

    private fun parseEntry(name: String): Pair<Level, TileId>? {
        // Nothing with a path separator beyond the one level directory, and
        // nothing with a "..": a ZIP is a file the user was handed by somebody.
        if (name.contains("..")) return null
        val parts = name.split('/')
        if (parts.size != 2) return null
        val level = Level.entries.firstOrNull { it.dir == parts[0] } ?: return null
        val id = TileCodec.parseFileName(parts[1]) ?: return null
        return level to id
    }

    companion object {
        const val INDEX_NAME: String = "karte.json"

        /** What the phone calls the directory inside `filesDir`. */
        const val DIR_NAME: String = "karte"
    }
}
