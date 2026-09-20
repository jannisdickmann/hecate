package de.hecate.core

import de.hecate.core.map.KarteCli
import de.hecate.core.map.Level
import de.hecate.core.map.MapClass
import de.hecate.core.map.TileStore
import de.hecate.core.map.overpass.OverpassClient
import de.hecate.core.replay.ReplayMain
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two map commands, in the shape of `ReplayCliTest` and against the same
 * kind of failure: a command that reports success and leaves a store nothing can
 * read, or a `--offline` that quietly reaches for the network anyway.
 *
 * **No test here opens a socket**, which is what the [OverpassClient] interface
 * exists for.
 */
class KarteCliTest {

    private fun tempDir(): File =
        Files.createTempDirectory("karte-cli").toFile().also { it.deleteOnExit() }

    /**
     * One Overpass response, hand-written: a Bundesstraße split into two ways
     * that share an endpoint (which is how OSM stores every road with a tag
     * change in the middle of it), a residential street, a lake, and two places.
     */
    private val fixture: String = """
        {"version": 0.6, "generator": "fixture", "elements": [
          {"type":"way","id":1,"tags":{"highway":"primary","name":"B4"},
           "geometry":[{"lat":49.601,"lon":11.001},{"lat":49.606,"lon":11.006},{"lat":49.611,"lon":11.012}]},
          {"type":"way","id":2,"tags":{"highway":"primary","name":"B4"},
           "geometry":[{"lat":49.611,"lon":11.012},{"lat":49.618,"lon":11.019},{"lat":49.624,"lon":11.028}]},
          {"type":"way","id":3,"tags":{"highway":"residential"},
           "geometry":[{"lat":49.603,"lon":11.003},{"lat":49.6035,"lon":11.0042},{"lat":49.6041,"lon":11.0051}]},
          {"type":"way","id":4,"tags":{"natural":"water","name":"Weiher"},
           "geometry":[{"lat":49.607,"lon":11.002},{"lat":49.609,"lon":11.002},
                       {"lat":49.609,"lon":11.005},{"lat":49.607,"lon":11.005},{"lat":49.607,"lon":11.002}]},
          {"type":"way","id":5,"tags":{"railway":"abandoned"},
           "geometry":[{"lat":49.602,"lon":11.001},{"lat":49.612,"lon":11.011}]},
          {"type":"node","id":10,"lat":49.6055,"lon":11.0075,
           "tags":{"place":"village","name":"Möhrendorf","population":"4200"}},
          {"type":"node","id":11,"lat":49.6200,"lon":11.0200,"tags":{"place":"hamlet","name":"Thurn"}}
        ]}
    """.trimIndent()

    private val box = "49.60,11.00,49.63,11.03"

    @Test
    fun `karte fills a store that decodes`() {
        val out = tempDir()
        val code = KarteCli.karte(
            listOf("--bbox", box, "--out", out.path, "--level", "fein"),
            client = OverpassClient { fixture },
        )
        assertEquals(0, code)

        val store = TileStore(out)
        val tiles = store.present(Level.FEIN)
        assertTrue(tiles.isNotEmpty(), "nothing was written")

        val decoded = tiles.mapNotNull { store.read(Level.FEIN, it) }
        assertEquals(tiles.size, decoded.size, "a tile did not decode")

        val classes = decoded.flatMap { it.features }.map { it.cls }.toSet()
        assertTrue(MapClass.PRIMARY in classes, "the Bundesstraße is missing")
        assertTrue(MapClass.MINOR in classes, "the residential street is missing")
        assertTrue(MapClass.WATER_AREA in classes, "the lake is missing")
        // `railway=abandoned` is a line that was lifted decades ago; OSM keeps
        // it and this map does not.
        assertFalse(MapClass.RAIL in classes, "an abandoned railway was drawn")

        val names = decoded.flatMap { it.places }.map { it.name }.toSet()
        assertEquals(setOf("Möhrendorf", "Thurn"), names)
        assertEquals(4200, decoded.flatMap { it.places }.first { it.name == "Möhrendorf" }.population)

        // The two halves of the B4 share an endpoint and are joined before
        // simplification, so the road is one feature per tile rather than two.
        val perTile = decoded.associate { it.id to it.features.count { f -> f.cls == MapClass.PRIMARY } }
        assertTrue(perTile.values.all { it <= 1 }, "the Bundesstraße came out in pieces: $perTile")
    }

    @Test
    fun `a second run fetches nothing, because nothing is missing`() {
        val out = tempDir()
        var calls = 0
        val client = OverpassClient { calls++; fixture }
        KarteCli.karte(listOf("--bbox", box, "--out", out.path, "--level", "grob"), client)
        val first = calls
        assertTrue(first > 0)
        KarteCli.karte(listOf("--bbox", box, "--out", out.path, "--level", "grob"), client)
        assertEquals(first, calls, "the same ground was fetched twice")
    }

    /** The flag's whole job. */
    @Test
    fun `offline never reaches for the network`() {
        val out = tempDir()
        var called = false
        val code = KarteCli.karte(
            listOf("--bbox", box, "--out", out.path, "--offline"),
            client = OverpassClient { called = true; fixture },
        )
        assertFalse(called, "--offline called the client anyway")
        assertEquals(1, code)
        assertTrue(TileStore(out).present(Level.FEIN).isEmpty())
    }

    @Test
    fun `dry-run plans without asking anything`() {
        val out = tempDir()
        val code = KarteCli.karte(
            listOf("--bbox", box, "--out", out.path, "--dry-run"),
            client = OverpassClient { error("the planner must not fetch") },
        )
        assertEquals(0, code)
        assertFalse(out.resolve(Level.GROB.dir).exists())
    }

    /**
     * `parseOptions` keeps only the last value of a repeated key, which is right
     * for `--out` and silently wrong for `--bbox`: three boxes would become one,
     * and the user would find out by looking at a map with two thirds missing.
     */
    @Test
    fun `a repeated bbox is refused rather than silently reduced`() {
        val out = tempDir()
        assertEquals(
            2,
            KarteCli.karte(listOf("--bbox", box, "--bbox", "49.7,11.1,49.72,11.12", "--out", out.path)),
        )
        assertEquals(
            0,
            KarteCli.karte(
                listOf("--bbox", "$box;49.70,11.10,49.72,11.12", "--out", out.path, "--level", "grob"),
                client = OverpassClient { fixture },
            ),
        )
    }

    @Test
    fun `karteinfo reads a store, renders it and packs it`() {
        val out = tempDir()
        KarteCli.karte(listOf("--bbox", box, "--out", out.path), OverpassClient { fixture })

        val zip = File(out, "bundle.zip")
        val svg = File(out, "preview")
        assertEquals(
            0,
            KarteCli.karteinfo(
                listOf("--store", out.path, "--stats", "--zip", zip.path, "--svg", svg.path),
            ),
        )
        assertTrue(zip.isFile && zip.length() > 0)
        val rendered = File("${svg.path}-1.svg")
        assertTrue(rendered.isFile)
        val text = rendered.readText()
        assertTrue(text.startsWith("<?xml"))
        assertTrue(text.contains("Möhrendorf"))
        assertTrue(text.contains("OpenStreetMap"), "the attribution is missing from the preview")

        // The ZIP is what „Karte einlesen" takes on the phone.
        val second = TileStore(tempDir())
        val taken = zip.inputStream().use { second.unpack(it) }
        assertEquals(TileStore(out).present(Level.FEIN).size + TileStore(out).present(Level.GROB).size, taken)
    }

    @Test
    fun `the commands are reachable from the dispatcher`() {
        assertEquals(2, ReplayMain.dispatch(arrayOf("karteinfo", "--store", "/nonexistent-store")))
    }
}
