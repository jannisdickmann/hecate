package de.hecate.core

import de.hecate.core.geo.LatLon
import de.hecate.core.map.Level
import de.hecate.core.map.MapClass
import de.hecate.core.map.MapFeature
import de.hecate.core.map.MapPlace
import de.hecate.core.map.PlaceClass
import de.hecate.core.map.Tile
import de.hecate.core.map.TileCodec
import de.hecate.core.map.TileFormatException
import de.hecate.core.map.TileGrid
import de.hecate.core.map.TileId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The stored tile, and in particular the invariant clipping exists to provide:
 * every vertex of every feature in tile C lies inside C. A rounding bug there
 * hides from the eye — a road a metre outside its tile looks like a road — and
 * shows up much later as a seam that only appears at one zoom level.
 */
class TileCodecTest {

    private val id = TileId(TileGrid.rowOf(49.62), TileGrid.colOf(11.01))

    private fun featureIn(cls: MapClass, n: Int, seed: Int, ring: Boolean = false): MapFeature {
        val b = TileGrid.bounds(id)
        val lat = DoubleArray(n)
        val lon = DoubleArray(n)
        for (i in 0 until n) {
            val fx = ((i * 37 + seed * 13) % 100) / 100.0
            val fy = ((i * 53 + seed * 29) % 100) / 100.0
            lat[i] = b.minLat + (b.maxLat - b.minLat) * fy
            lon[i] = b.minLon + (b.maxLon - b.minLon) * fx
        }
        if (ring) {
            lat[n - 1] = lat[0]
            lon[n - 1] = lon[0]
        }
        return MapFeature(cls, lat, lon, ring)
    }

    private fun sample(): Tile {
        val b = TileGrid.bounds(id)
        return Tile(
            id = id,
            level = Level.FEIN,
            builtAtUtcMs = 1_758_000_000_000L,
            features = listOf(
                featureIn(MapClass.MOTORWAY, 12, 1),
                featureIn(MapClass.MINOR, 40, 2),
                featureIn(MapClass.PATH, 3, 3),
                featureIn(MapClass.WATER_AREA, 9, 4, ring = true),
            ),
            places = listOf(
                MapPlace(PlaceClass.TOWN, LatLon(b.minLat + 0.01, b.minLon + 0.01), "Möhrendorf", 4_200),
                MapPlace(PlaceClass.HAMLET, LatLon(b.minLat + 0.02, b.minLon + 0.02), "Kleinseebacher Mühle"),
            ),
            urban = listOf(featureIn(MapClass.URBAN, 6, 5, ring = true)),
        )
    }

    @Test
    fun `a tile round trips inside one quantisation unit`() {
        val original = sample()
        val decoded = TileCodec.decode(id, TileCodec.encode(original))

        assertEquals(original.level, decoded.level)
        assertEquals(original.builtAtUtcMs, decoded.builtAtUtcMs)
        assertEquals(original.features.size, decoded.features.size)
        assertEquals(original.urban.size, decoded.urban.size)

        val unit = 1.0 / TileGrid.UNITS_PER_DEGREE
        for (cls in MapClass.entries) {
            val before = original.features.filter { it.cls == cls }
            val after = decoded.features.filter { it.cls == cls }
            assertEquals(before.size, after.size, "class $cls")
            for ((a, b) in before.zip(after)) {
                assertEquals(a.closedRing, b.closedRing)
                assertEquals(a.size, b.size)
                for (i in 0 until a.size) {
                    assertTrue(abs(a.lat[i] - b.lat[i]) <= unit, "lat off by ${abs(a.lat[i] - b.lat[i])}")
                    assertTrue(abs(a.lon[i] - b.lon[i]) <= unit, "lon off by ${abs(a.lon[i] - b.lon[i])}")
                }
            }
        }
        assertEquals(original.places.map { it.name }, decoded.places.map { it.name })
        assertEquals(4_200, decoded.places.first().population)
        assertEquals(PlaceClass.HAMLET, decoded.places[1].cls)
    }

    /** The invariant. */
    @Test
    fun `every vertex of every feature lies inside its own tile`() {
        val decoded = TileCodec.decode(id, TileCodec.encode(sample()))
        val b = TileGrid.bounds(id)
        for (f in decoded.features + decoded.urban) {
            for (i in 0 until f.size) {
                assertTrue(f.lat[i] >= b.minLat && f.lat[i] <= b.maxLat, "lat ${f.lat[i]} outside $b")
                assertTrue(f.lon[i] >= b.minLon && f.lon[i] <= b.maxLon, "lon ${f.lon[i]} outside $b")
            }
        }
        for (p in decoded.places) assertTrue(b.contains(p.at))
    }

    @Test
    fun `an empty tile is still a tile`() {
        val empty = Tile(id, Level.GROB, 1L, emptyList(), emptyList(), emptyList())
        val decoded = TileCodec.decode(id, TileCodec.encode(empty))
        assertTrue(decoded.isEmpty)
        assertEquals(Level.GROB, decoded.level)
    }

    @Test
    fun `wrong magic is refused`() {
        val bytes = TileCodec.encode(sample())
        bytes[0] = 'X'.code.toByte()
        assertFailsWith<TileFormatException> { TileCodec.decode(id, bytes) }
    }

    @Test
    fun `a file from another version of the format is refused`() {
        val bytes = TileCodec.encode(sample())
        // The section count, right after the magic and the level byte.
        bytes[5] = (TileCodec.SECTIONS + 1).toByte()
        assertFailsWith<TileFormatException> { TileCodec.decode(id, bytes) }
    }

    @Test
    fun `a truncated file is refused rather than half decoded`() {
        val bytes = TileCodec.encode(sample())
        for (cut in listOf(4, 12, 30, bytes.size / 2, bytes.size - 3)) {
            assertFailsWith<TileFormatException>("cut at $cut") {
                TileCodec.decode(id, bytes.copyOf(cut))
            }
        }
    }

    /**
     * A header claiming a trillion vertices must throw rather than allocate
     * the array and take the process with it. Hand-built, because no encoder
     * would ever produce it — which is the point.
     */
    @Test
    fun `an absurd vertex count is refused rather than allocated`() {
        val payload = java.io.ByteArrayOutputStream().apply {
            varint(1L)                              // one feature
            varint((1L shl 40) shl 1)               // ... of a trillion vertices
        }.toByteArray()
        assertFailsWith<TileFormatException> { TileCodec.decode(id, fileWith(MapClass.MOTORWAY.id, deflate(payload))) }
    }

    /** A section length that runs past the end of the file, which is what a
     *  half-written tile after a power cut looks like. */
    @Test
    fun `a section claiming more bytes than the file holds is refused`() {
        val bytes = fileWith(MapClass.MOTORWAY.id, deflate(byteArrayOf(0)))
        // The first section length sits after magic (4), level (1), count (1)
        // and the timestamp (8).
        val at = 14
        bytes[at] = 0x7F
        bytes[at + 1] = 0x7F
        assertFailsWith<TileFormatException> { TileCodec.decode(id, bytes) }
    }

    // ------------------------------------------------------------- hand-built

    private fun java.io.ByteArrayOutputStream.varint(value: Long) {
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) { write(b); return }
            write(b or 0x80)
        }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = java.util.zip.Deflater()
        d.setInput(data); d.finish()
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** A structurally valid tile file whose one section holds [section]. */
    private fun fileWith(index: Int, section: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write("HKT1".toByteArray(Charsets.US_ASCII))
        out.write(Level.FEIN.ordinal)
        out.write(TileCodec.SECTIONS)
        repeat(8) { out.write(0) }
        for (i in 0 until TileCodec.SECTIONS) {
            val n = if (i == index) section.size else 0
            for (shift in intArrayOf(24, 16, 8, 0)) out.write((n ushr shift) and 0xFF)
        }
        out.write(section)
        out.write(0) // no places
        return out.toByteArray()
    }

    @Test
    fun `the file name round trips, including negative coordinates`() {
        for (t in listOf(TileId(1588, 352), TileId(-4, -17), TileId(0, 0))) {
            assertEquals(t, TileCodec.parseFileName(TileCodec.fileName(t)))
        }
        assertEquals(null, TileCodec.parseFileName("karte.json"))
        assertEquals(null, TileCodec.parseFileName("nonsense.hmt"))
    }

    /**
     * The point of splitting the classes: a whole-region view inflates the loud
     * classes and never touches the residential streets in the same file. A
     * coarse tile therefore has to be markedly smaller than a detailed one over
     * the same ground.
     */
    @Test
    fun `dropping the detailed classes makes a markedly smaller tile`() {
        val full = sample()
        val coarse = Tile(
            id, Level.GROB, full.builtAtUtcMs,
            full.features.filter { it.cls.minLevel == Level.GROB },
            full.places, full.urban,
        )
        assertTrue(
            TileCodec.encode(coarse).size < TileCodec.encode(full).size,
            "the coarse tile was not smaller",
        )
    }
}
