package de.hecate.core.map

import de.hecate.core.geo.LatLon
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/** One stored tile, decoded. */
class Tile(
    val id: TileId,
    val level: Level,
    /** When this tile was fetched. Nothing nags about a stale tile — a warning
     *  about scenery would be worse than the scenery being two years old — but
     *  a refresh has to be able to ask. */
    val builtAtUtcMs: Long,
    val features: List<MapFeature>,
    val places: List<MapPlace>,
    /** The coarse built-up outline, derived at build time. See [UrbanPatch]. */
    val urban: List<MapFeature>,
) {
    val isEmpty: Boolean get() = features.isEmpty() && places.isEmpty() && urban.isEmpty()
}

/** A tile file that is not one, or is one from a future version of this app. */
class TileFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The stored tile: a few kilobytes of deflated integers per 7.8 km² of ground.
 *
 * ```
 * FILE  <level>/<row>_<col>.hmt
 *   magic "HKT1" | level u8 | sectionCount u8
 *   builtAtUtcMs i64
 *   per section: u32 deflated length            // 0 = nothing of this kind here
 *   per section: one DEFLATE stream of
 *       featureCount varint
 *       per feature: (vertexCount << 1 | closedRing) varint,
 *                    x0 varint, y0 varint,       // tile-local, 0..4096
 *                    then (dx, dy) as zig-zag varints
 *   places: count varint, then per place:
 *       rank u8 | x varint | y varint | population varint | name (len + UTF-8)
 * ```
 *
 * Three decisions are worth defending.
 *
 * **Coordinates are integers in units of 2^-17 degrees** ([TileGrid]). A power
 * of two, so a phone decodes with a shift and a tile's own origin is exact. The
 * quantisation error is 0.85 m north–south, an order of magnitude under the
 * simplification tolerance and two under GNSS noise, so it is never the step
 * that loses anything.
 *
 * **Features are clipped to the tile at build time.** That costs one duplicated
 * vertex per crossing and buys three things: every vertex is provably inside its
 * own tile, so a coordinate fits in a short varint; the viewport query needs no
 * per-feature bounding-box test; and two hundred metres of motorway on screen
 * can never drag in forty kilometres of it.
 *
 * **DEFLATE per section, not per tile.** That is the entire point of splitting
 * the classes: a whole-region view inflates the motorway, rail and water
 * sections and never touches the residential streets sitting in the same files.
 *
 * This is a binary format in a codebase that otherwise prefers gzipped JSON you
 * can read when it goes wrong, and the justification is the one `HeatmapCodec`
 * already writes down for itself — it is the decoder that would not survive four
 * million JSON integers. Unlike a heatmap raster this cannot be run-length
 * encoded, because no two coordinates repeat. What replaces "open it in a text
 * editor" is `karteinfo --svg`, which renders the decoded store as a picture.
 */
object TileCodec {

    private val MAGIC = byteArrayOf('H'.code.toByte(), 'K'.code.toByte(), 'T'.code.toByte(), '1'.code.toByte())

    /** One section per [MapClass], in id order — eight drawn classes and the
     *  derived urban patch. A ninth *classified* class would be a format
     *  version bump, which is the trade a trivial loader is worth. */
    val SECTIONS: Int = MapClass.entries.size

    /** Sanity ceilings. A truncated or corrupt file can otherwise ask for two
     *  billion vertices, and the allocation, not the error, is what the user
     *  would notice. */
    private const val MAX_FEATURES = 1 shl 20
    private const val MAX_VERTICES = 1 shl 20
    private const val MAX_PLACES = 1 shl 16
    private const val MAX_NAME_BYTES = 512

    const val EXTENSION: String = "hmt"

    fun fileName(id: TileId): String = "${id.row}_${id.col}.$EXTENSION"

    /** `12_34.hmt` back into a [TileId], or null for anything else in the
     *  directory. */
    fun parseFileName(name: String): TileId? {
        if (!name.endsWith(".$EXTENSION")) return null
        val stem = name.removeSuffix(".$EXTENSION")
        val cut = stem.indexOf('_', startIndex = if (stem.startsWith("-")) 1 else 0)
        if (cut <= 0) return null
        val row = stem.substring(0, cut).toIntOrNull() ?: return null
        val col = stem.substring(cut + 1).toIntOrNull() ?: return null
        return TileId(row, col)
    }

    // ------------------------------------------------------------------ encode

    fun encode(tile: Tile): ByteArray {
        val all = tile.features + tile.urban
        val sections = Array(SECTIONS) { ByteArray(0) }
        for (cls in MapClass.entries) {
            val own = all.filter { it.cls == cls }
            if (own.isNotEmpty()) sections[cls.id] = deflate(encodeFeatures(tile.id, own))
        }

        val out = ByteArrayOutputStream(1024)
        out.write(MAGIC)
        out.write(tile.level.ordinal)
        out.write(SECTIONS)
        writeLong(out, tile.builtAtUtcMs)
        for (s in sections) writeInt(out, s.size)
        for (s in sections) out.write(s)
        encodePlaces(out, tile.id, tile.places)
        return out.toByteArray()
    }

    private fun encodeFeatures(id: TileId, features: List<MapFeature>): ByteArray {
        val out = ByteArrayOutputStream(features.size * 32)
        writeVarint(out, features.size.toLong())
        for (f in features) {
            writeVarint(out, (f.size.toLong() shl 1) or (if (f.closedRing) 1L else 0L))
            var px = 0
            var py = 0
            for (i in 0 until f.size) {
                val x = TileGrid.localX(id.col, f.lon[i])
                val y = TileGrid.localY(id.row, f.lat[i])
                if (i == 0) {
                    writeVarint(out, x.toLong())
                    writeVarint(out, y.toLong())
                } else {
                    writeVarint(out, zigzag((x - px).toLong()))
                    writeVarint(out, zigzag((y - py).toLong()))
                }
                px = x
                py = y
            }
        }
        return out.toByteArray()
    }

    private fun encodePlaces(out: ByteArrayOutputStream, id: TileId, places: List<MapPlace>) {
        writeVarint(out, places.size.toLong())
        for (p in places) {
            out.write(p.cls.id)
            writeVarint(out, TileGrid.localX(id.col, p.at.lon).toLong())
            writeVarint(out, TileGrid.localY(id.row, p.at.lat).toLong())
            writeVarint(out, p.population.toLong().coerceAtLeast(0L))
            val name = p.name.toByteArray(Charsets.UTF_8)
            val cut = if (name.size > MAX_NAME_BYTES) name.copyOf(MAX_NAME_BYTES) else name
            writeVarint(out, cut.size.toLong())
            out.write(cut)
        }
    }

    // ------------------------------------------------------------------ decode

    fun decode(id: TileId, bytes: ByteArray): Tile {
        if (bytes.size < MAGIC.size + 2 + 8 + 4 * SECTIONS) {
            throw TileFormatException("Kacheldatei ist abgeschnitten (${bytes.size} B)")
        }
        for (i in MAGIC.indices) {
            if (bytes[i] != MAGIC[i]) throw TileFormatException("keine Hecate-Kartenkachel")
        }
        val cursor = Cursor(bytes, MAGIC.size)
        val levelOrdinal = cursor.u8()
        val level = Level.of(levelOrdinal)
            ?: throw TileFormatException("unbekannte Detailstufe $levelOrdinal")
        val sectionCount = cursor.u8()
        // Not ">=": a file with fewer sections is one from before a class was
        // added, and reading it would quietly draw a map with a class missing.
        if (sectionCount != SECTIONS) {
            throw TileFormatException("Kachel hat $sectionCount Abschnitte, erwartet $SECTIONS")
        }
        val builtAt = cursor.long()
        val lengths = IntArray(SECTIONS) {
            val n = cursor.int()
            if (n < 0) throw TileFormatException("negative Abschnittslänge")
            n
        }
        var total = 0L
        for (n in lengths) total += n
        if (cursor.pos + total > bytes.size) {
            throw TileFormatException("Abschnitte ragen über das Dateiende hinaus")
        }

        val features = ArrayList<MapFeature>()
        val urban = ArrayList<MapFeature>()
        for (section in 0 until SECTIONS) {
            val len = lengths[section]
            val start = cursor.pos
            cursor.skip(len)
            if (len == 0) continue
            val cls = MapClass.of(section) ?: throw TileFormatException("unbekannte Klasse $section")
            val decoded = decodeFeatures(id, inflate(bytes, start, len), cls)
            if (cls == MapClass.URBAN) urban.addAll(decoded) else features.addAll(decoded)
        }
        val places = decodePlaces(id, cursor)
        return Tile(id, level, builtAt, features, places, urban)
    }

    private fun decodeFeatures(id: TileId, payload: ByteArray, cls: MapClass): List<MapFeature> {
        val c = Cursor(payload, 0)
        val count = c.varint()
        if (count < 0 || count > MAX_FEATURES) throw TileFormatException("$count Objekte in einer Kachel")
        val out = ArrayList<MapFeature>(count.toInt())
        repeat(count.toInt()) {
            val header = c.varint()
            val vertices = (header ushr 1).toInt()
            val ring = (header and 1L) == 1L
            if (vertices <= 0 || vertices > MAX_VERTICES) {
                throw TileFormatException("$vertices Punkte in einem Objekt")
            }
            val lat = DoubleArray(vertices)
            val lon = DoubleArray(vertices)
            var x = c.varint().toInt()
            var y = c.varint().toInt()
            lon[0] = TileGrid.lonOf(id.col, x)
            lat[0] = TileGrid.latOf(id.row, y)
            for (i in 1 until vertices) {
                x += unzigzag(c.varint()).toInt()
                y += unzigzag(c.varint()).toInt()
                lon[i] = TileGrid.lonOf(id.col, x)
                lat[i] = TileGrid.latOf(id.row, y)
            }
            out.add(MapFeature(cls, lat, lon, ring))
        }
        return out
    }

    private fun decodePlaces(id: TileId, c: Cursor): List<MapPlace> {
        if (c.pos >= c.bytes.size) return emptyList()
        val count = c.varint()
        if (count < 0 || count > MAX_PLACES) throw TileFormatException("$count Orte in einer Kachel")
        val out = ArrayList<MapPlace>(count.toInt())
        repeat(count.toInt()) {
            val rank = c.u8()
            val cls = PlaceClass.of(rank) ?: throw TileFormatException("unbekannte Ortsklasse $rank")
            val x = c.varint().toInt()
            val y = c.varint().toInt()
            val population = c.varint()
            val len = c.varint()
            if (len < 0 || len > MAX_NAME_BYTES) throw TileFormatException("Ortsname mit $len Bytes")
            val name = c.utf8(len.toInt())
            out.add(
                MapPlace(
                    cls = cls,
                    at = LatLon(TileGrid.latOf(id.row, y), TileGrid.lonOf(id.col, x)),
                    name = name,
                    population = population.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
                ),
            )
        }
        return out
    }

    // ----------------------------------------------------------------- plumbing

    private class Cursor(val bytes: ByteArray, var pos: Int) {
        fun need(n: Int) {
            if (pos + n > bytes.size) throw TileFormatException("Kacheldatei endet mitten in einem Wert")
        }

        fun u8(): Int {
            need(1)
            return bytes[pos++].toInt() and 0xFF
        }

        fun int(): Int {
            need(4)
            var v = 0
            repeat(4) { v = (v shl 8) or (bytes[pos++].toInt() and 0xFF) }
            return v
        }

        fun long(): Long {
            need(8)
            var v = 0L
            repeat(8) { v = (v shl 8) or (bytes[pos++].toLong() and 0xFF) }
            return v
        }

        fun skip(n: Int) {
            need(n)
            pos += n
        }

        fun utf8(n: Int): String {
            need(n)
            val s = String(bytes, pos, n, Charsets.UTF_8)
            pos += n
            return s
        }

        fun varint(): Long {
            var shift = 0
            var out = 0L
            while (true) {
                if (shift > 63) throw TileFormatException("überlanger Varint")
                val b = u8()
                out = out or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return out
                shift += 7
            }
        }
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

    private fun writeInt(out: ByteArrayOutputStream, v: Int) {
        for (shift in intArrayOf(24, 16, 8, 0)) out.write((v ushr shift) and 0xFF)
    }

    private fun writeLong(out: ByteArrayOutputStream, v: Long) {
        for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) out.write(((v ushr shift) and 0xFF).toInt())
    }

    private fun zigzag(v: Long): Long = (v shl 1) xor (v shr 63)
    private fun unzigzag(v: Long): Long = (v ushr 1) xor -(v and 1L)

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        try {
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 32)
            val buf = ByteArray(8192)
            while (!d.finished()) {
                val n = d.deflate(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    private fun inflate(bytes: ByteArray, offset: Int, length: Int): ByteArray {
        val i = Inflater()
        try {
            i.setInput(bytes, offset, length)
            val out = ByteArrayOutputStream(length * 4 + 64)
            val buf = ByteArray(8192)
            while (!i.finished()) {
                val n = try {
                    i.inflate(buf)
                } catch (e: DataFormatException) {
                    throw TileFormatException("Abschnitt lässt sich nicht entpacken", e)
                }
                if (n == 0) {
                    if (i.needsInput() || i.needsDictionary()) {
                        throw TileFormatException("Abschnitt ist unvollständig")
                    }
                } else {
                    out.write(buf, 0, n)
                }
            }
            return out.toByteArray()
        } finally {
            i.end()
        }
    }
}
