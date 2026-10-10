package de.hecate.core.map

import de.hecate.core.Tuning
import de.hecate.core.archive.ArchiveCodec
import de.hecate.core.geo.LatLon
import de.hecate.core.geo.Track
import de.hecate.core.io.SessionCodec
import de.hecate.core.map.overpass.BasemapBuilder
import de.hecate.core.map.overpass.FetchPlanner
import de.hecate.core.map.overpass.OsmJson
import de.hecate.core.map.overpass.OverpassClient
import de.hecate.core.map.overpass.OverpassQuery
import de.hecate.core.map.overpass.UrlOverpassClient
import de.hecate.core.replay.CliInputs
import java.io.File

/**
 * The laptop half of the basemap: `karte` fills a store, `karteinfo` looks at
 * one.
 *
 * ```
 * ./gradlew :core:run --args="karte --archive takeout/hecate-sicherung-*.zip --out map/"
 * ./gradlew :core:run --args="karteinfo --store map/ --svg map/preview"
 * ```
 *
 * The laptop exists in this feature for two reasons that a phone cannot cover.
 * One is ethernet: a full prefetch is a few hundred megabytes over about forty
 * requests, and doing that once at a desk and carrying the result across is
 * kinder to a volunteer service and to a mobile plan than doing it on the train.
 * The other is `--svg`, which is the only way to see whether the map is right
 * before a phone is involved.
 */
object KarteCli {

    fun usage() {
        println(
            """
            hecate basemap (Appendix A.29)

              karte     (--archive <zip> | --sessions <dir> | --bbox s,w,n,e[;...])
                        [--out <dir>] [--margin <km>] [--detail-radius <km>]
                        [--level grob|fein|beide] [--refresh] [--offline]
                        [--dry-run] [--verbose]
                        Fetch the OpenStreetMap ground around your recordings
                        into a tile store. Never fetches a tile twice.
              karteinfo --store <dir> [--svg <prefix>] [--stats] [--zip <file>]
                        [--archive <zip> | --sessions <dir>] [--level grob|fein]
                        What is in a store; --svg renders it, with your own
                        tracks in red on top.
            """.trimIndent(),
        )
    }

    // ------------------------------------------------------------------- karte

    fun karte(args: List<String>, client: OverpassClient? = null): Int {
        val o = try {
            Options.parse(args)
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message); return 2
        }

        val marginM = (o.double("margin") ?: (Tuning.MAP_REGION_MARGIN_M / 1000.0)) * 1000.0
        val detailM = (o.double("detail-radius") ?: (Tuning.MAP_DETAIL_RADIUS_M / 1000.0)) * 1000.0
        val levels = when (o["level"]?.lowercase()) {
            null, "beide", "both" -> listOf(Level.GROB, Level.FEIN)
            "grob", "coarse" -> listOf(Level.GROB)
            "fein", "full" -> listOf(Level.FEIN)
            else -> { System.err.println("--level wants grob, fein or beide"); return 2 }
        }

        val explicit = try {
            o.boxes("bbox")
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message); return 2
        }
        val positions = if (explicit.isEmpty()) {
            val p = positionsFrom(o) ?: return 2
            if (p.isEmpty()) {
                System.err.println("no positions found; give --archive, --sessions or --bbox")
                return 2
            }
            p
        } else {
            emptyList()
        }

        val regions = if (explicit.isNotEmpty()) {
            explicit.map { Coverage.Region(TileGrid.snap(it), 0, Coverage.nameOf(it)) }
        } else {
            Coverage.regions(positions, marginM)
        }
        if (regions.isEmpty()) {
            System.err.println("no region reached ${Tuning.MAP_MIN_CLUSTER_SAMPLES} positions")
            return 2
        }

        val coarse = LinkedHashSet<TileId>()
        for (r in regions) coarse.addAll(r.tiles)
        val fine = if (explicit.isNotEmpty()) coarse.toSet() else detailTiles(positions, detailM, coarse)

        val store = TileStore(File(o["out"] ?: DEFAULT_STORE))

        println("regions: ${regions.size}")
        var totalKm2 = 0.0
        for (r in regions) {
            totalKm2 += r.bounds.areaKm2
            println(
                "  %-22s %7.0f km²  %4d Kacheln  %7d Positionen".format(
                    java.util.Locale.GERMANY, r.name, r.bounds.areaKm2, r.tiles.size, r.positions,
                ),
            )
        }
        println("---")
        println("%.0f km² total, %d coarse tiles, %d detailed tiles".format(java.util.Locale.GERMANY, totalKm2, coarse.size, fine.size))

        val work = LinkedHashMap<Level, List<FetchChunkAt>>()
        for (level in levels) {
            val wanted = if (level == Level.GROB) coarse else fine
            val missing = if (o.has("refresh")) wanted.toList() else store.missing(level, wanted)
            val chunks = FetchPlanner.plan(missing).map { FetchChunkAt(level, it) }
            work[level] = chunks
            val km2 = chunks.sumOf { it.chunk.bounds.areaKm2 }
            println(
                "%s: %d/%d tiles missing, %d requests, ~%.0f MB".format(
                    java.util.Locale.GERMANY,
                    level.dir, missing.size, wanted.size, chunks.size,
                    FetchPlanner.estimatedBytes(km2, level == Level.GROB) / 1_000_000.0,
                ),
            )
        }

        if (o.has("dry-run")) {
            println("--dry-run: nothing fetched")
            return 0
        }

        // --offline wins over an injected client, and deliberately so: the
        // flag's whole job is to fail rather than reach for the network, and a
        // test that could not prove the network was never reached would not be
        // testing anything.
        val overpass = when {
            o.has("offline") ->
                OverpassClient { throw de.hecate.core.map.overpass.OverpassException("--offline: kein Netz erlaubt") }
            client != null -> client
            else -> UrlOverpassClient()
        }

        var written = 0
        var failed = 0
        val names = HashMap<Int, String>()
        for (level in levels) {
            for ((i, at) in (work[level] ?: emptyList()).withIndex()) {
                val box = at.chunk.bounds
                print("[%s %d/%d] %s %.0f km² … ".format(java.util.Locale.GERMANY, level.dir, i + 1, work[level]!!.size, OverpassQuery.bbox(box), box.areaKm2))
                System.out.flush()
                try {
                    val body = overpass.fetch(OverpassQuery.build(box, level))
                    val result = BasemapBuilder.build(OsmJson.parse(body), level, at.chunk.tiles)
                    for (tile in result.tiles) store.write(tile)
                    written += result.tiles.size
                    result.largestPlace?.let { place ->
                        regions.indexOfFirst { it.bounds.intersects(box) }
                            .takeIf { it >= 0 }?.let { names.putIfAbsent(it, place) }
                    }
                    println("%d Kacheln, %d Objekte, %d Punkte".format(result.tiles.size, result.featureCount, result.vertexCount))
                } catch (e: Exception) {
                    failed++
                    println("fehlgeschlagen: ${e.message}")
                    if (o.has("verbose")) e.printStackTrace()
                }
            }
        }

        store.refreshIndex(
            regions.mapIndexed { i, r -> MapRegion.of(names[i] ?: r.name, r.bounds) },
        )
        val stats = store.stats()
        println("---")
        println("$written Kacheln geschrieben, $failed Anfragen fehlgeschlagen")
        println("${stats.tiles} Kacheln, ${stats.bytes / 1024} KiB in ${store.root}")
        // A chunk that failed leaves whole tiles behind rather than a corrupt
        // region, so running this again fetches only what is still missing.
        if (failed > 0) println("nochmal aufrufen holt nur das Fehlende nach")
        return if (failed > 0 && written == 0) 1 else 0
    }

    private data class FetchChunkAt(val level: Level, val chunk: de.hecate.core.map.overpass.FetchChunk)

    // --------------------------------------------------------------- karteinfo

    fun karteinfo(args: List<String>): Int {
        val o = try {
            Options.parse(args)
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message); return 2
        }
        val store = TileStore(File(o["store"] ?: DEFAULT_STORE))
        if (!store.root.isDirectory) {
            System.err.println("no such store: ${store.root}")
            return 2
        }
        val index = store.index()
        val stats = store.stats()
        println("store:   ${store.root}")
        println("format:  v${index.formatVersion}   ${index.attribution}")
        println("tiles:   ${stats.tiles}   ${stats.bytes / 1024} KiB")
        for (level in Level.entries) {
            val ids = store.present(level)
            if (ids.isEmpty()) continue
            println("  ${level.dir}: ${ids.size} tiles")
        }
        for (r in index.regions) {
            println("  region %-22s %7.0f km²".format(java.util.Locale.GERMANY, r.name, r.bounds.areaKm2))
        }

        if (o.has("stats")) printClassStats(store)

        o["zip"]?.let { path ->
            val f = File(path)
            f.parentFile?.mkdirs()
            f.outputStream().use { store.packInto(it) }
            println("bundle -> $f (${f.length() / 1024} KiB)")
        }

        o["svg"]?.let { prefix ->
            val level = when (o["level"]?.lowercase()) {
                "grob", "coarse" -> Level.GROB
                else -> Level.FEIN
            }
            val tracks = positionsAsTracks(o) ?: emptyList()
            val regions = index.regions.ifEmpty {
                // A store built with --bbox has no regions written; fall back to
                // the ground the tiles themselves cover, which is the same
                // picture under a duller name.
                store.present(level).takeIf { it.isNotEmpty() }?.let { ids ->
                    listOf(MapRegion.of("Kacheln", ids.map { TileGrid.bounds(it) }.reduce { a, b -> a.union(b) }))
                } ?: emptyList()
            }
            if (regions.isEmpty()) {
                System.err.println("nothing to render")
                return 2
            }
            for ((i, region) in regions.withIndex()) {
                val own = tracks.filter { t -> t.any { region.bounds.contains(it) } }
                val svg = BasemapSvg.render(
                    store = store,
                    level = level,
                    bounds = region.bounds,
                    tracks = own,
                    title = "${region.name} · ${level.dir}",
                )
                val out = File("$prefix-${i + 1}.svg")
                out.parentFile?.mkdirs()
                out.writeText(svg)
                println("svg -> $out (${own.size} Aufzeichnungen darüber)")
            }
        }
        return 0
    }

    private fun printClassStats(store: TileStore) {
        for (level in Level.entries) {
            val ids = store.present(level)
            if (ids.isEmpty()) continue
            val features = HashMap<MapClass, Int>()
            val vertices = HashMap<MapClass, Long>()
            var places = 0
            for (id in ids) {
                val tile = try {
                    store.read(level, id)
                } catch (e: TileFormatException) {
                    println("  ${level.dir}/$id: ${e.message}")
                    null
                } ?: continue
                places += tile.places.size
                for (f in tile.features + tile.urban) {
                    features.merge(f.cls, 1, Int::plus)
                    vertices.merge(f.cls, f.size.toLong(), Long::plus)
                }
            }
            println("${level.dir}: $places Orte")
            for (cls in MapClass.entries) {
                val n = features[cls] ?: continue
                println("  %-11s %7d Objekte %9d Punkte".format(cls.name, n, vertices[cls] ?: 0L))
            }
        }
    }

    // ------------------------------------------------------------------ inputs

    /** Every tile within [radiusM] of a recorded position, restricted to the
     *  ground a region already covers. */
    private fun detailTiles(positions: List<LatLon>, radiusM: Double, within: Set<TileId>): Set<TileId> {
        val out = LinkedHashSet<TileId>()
        for (p in positions) {
            val box = LatLonBounds(p.lat, p.lon, p.lat, p.lon).expandedBy(radiusM)
            for (id in TileGrid.tilesIn(box)) if (id in within) out.add(id)
        }
        return out
    }

    private fun positionsFrom(o: Options): List<LatLon>? =
        positionsAsTracks(o)?.flatten()

    /** Each recording's own geometry, which `--svg` draws and the coverage
     *  clusterer flattens. */
    private fun positionsAsTracks(o: Options): List<List<LatLon>>? {
        o["archive"]?.let { path ->
            val f = File(path)
            if (!f.isFile) { System.err.println("no such file: $path"); return null }
            return try {
                ArchiveCodec.Reader(f).use { reader ->
                    reader.database.sessions.mapNotNull { row ->
                        reader.session(row.id)?.let { Track.of(it)?.points }
                    }
                }
            } catch (e: Exception) {
                System.err.println("not a Hecate backup: $path (${e.message})")
                null
            }
        }
        o["sessions"]?.let { path ->
            val dir = File(path)
            if (!dir.isDirectory) { System.err.println("not a directory: $path"); return null }
            return CliInputs.sessionFiles(dir)
                .mapNotNull { f -> SessionCodec.decodeSession(f.readLines().asSequence())?.let { Track.of(it)?.points } }
        }
        return emptyList()
    }

    private const val DEFAULT_STORE = "map"

    // ----------------------------------------------------------------- options

    /**
     * Option parsing that can tell a repeated flag from a single one.
     *
     * `ReplayMain.parseOptions` keeps only the last value of a repeated key,
     * which is exactly right for `--out` and silently wrong for `--bbox`: a user
     * giving three boxes would get one, and would find out by looking at a map
     * with two thirds of it missing. So the repeated form is rejected with a
     * message that names the semicolon form instead.
     */
    class Options private constructor(private val values: Map<String, List<String>>) {

        operator fun get(key: String): String? = values[key]?.lastOrNull()
        fun has(key: String): Boolean = values.containsKey(key)
        fun double(key: String): Double? = get(key)?.replace(',', '.')?.toDoubleOrNull()

        /** `--bbox "s,w,n,e;s,w,n,e"`. */
        fun boxes(key: String): List<LatLonBounds> {
            val raw = values[key] ?: return emptyList()
            require(raw.size == 1) {
                "--$key given ${raw.size} times; separate several boxes with a semicolon instead"
            }
            return raw.single().split(';').filter { it.isNotBlank() }.map { part ->
                val n = part.split(',').map { it.trim().toDoubleOrNull() }
                require(n.size == 4 && n.all { it != null }) { "--$key wants s,w,n,e — got \"$part\"" }
                LatLonBounds(
                    minLat = minOf(n[0]!!, n[2]!!), minLon = minOf(n[1]!!, n[3]!!),
                    maxLat = maxOf(n[0]!!, n[2]!!), maxLon = maxOf(n[1]!!, n[3]!!),
                )
            }
        }

        companion object {
            fun parse(args: List<String>): Options {
                val out = LinkedHashMap<String, MutableList<String>>()
                var i = 0
                while (i < args.size) {
                    val a = args[i]
                    if (a.startsWith("--")) {
                        val key = a.removePrefix("--")
                        val next = args.getOrNull(i + 1)
                        if (next != null && !next.startsWith("--")) {
                            out.getOrPut(key) { ArrayList() }.add(next); i += 2
                        } else {
                            out.getOrPut(key) { ArrayList() }.add("true"); i++
                        }
                    } else {
                        i++
                    }
                }
                return Options(out)
            }
        }
    }
}
