package de.hecate.core.replay

import de.hecate.core.archive.Archive
import de.hecate.core.archive.ArchiveCodec
import de.hecate.core.geo.Track
import de.hecate.core.cluster.BundleBuilder
import de.hecate.core.io.BundleCodec
import de.hecate.core.io.SessionCodec
import de.hecate.core.matcher.RouteMatcher
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.MatchPhase
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.RouteBundle
import de.hecate.core.model.ExerciseType
import de.hecate.core.takeout.TakeoutImport
import de.hecate.core.takeout.TakeoutSource
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * US-10: the replay harness.
 *
 * Runs a stored session through the same matcher the watch runs, at whatever
 * speed the developer likes, and prints or checks the decisions. Since
 * [RouteMatcher] is deterministic and clock-free, a divergence between a replayed
 * decision and the one the watch logged live is a bug in one of the two, never
 * an artefact of replay.
 *
 * ```
 * ./gradlew :core:run --args="replay --bundle bundle.json --session s.jsonl"
 * ./gradlew :core:run --args="bundle --sessions ./fixtures --out bundle.json"
 * ./gradlew :core:run --args="verify --bundle b.json --session s.jsonl --golden g.jsonl"
 * ./gradlew :core:run --args="selfcheck --bundle b.json --session s.jsonl"
 * ./gradlew :core:run --args="onwrist --session s.jsonl --bundle b.json"
 * ./gradlew :core:run --args="takeout --input takeout/takeout-*.zip"
 * ```
 */
object ReplayMain {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            usage()
            exitProcess(2)
        }
        val opts = parseOptions(args.drop(1))
        val code = when (args[0]) {
            "replay" -> cmdReplay(opts)
            "bundle" -> cmdBundle(opts)
            "verify" -> cmdVerify(opts)
            "selfcheck" -> cmdSelfCheck(opts)
            "onwrist" -> cmdOnWrist(opts)
            "takeout" -> cmdTakeout(opts)
            "help", "--help", "-h" -> { usage(); 0 }
            else -> { usage(); 2 }
        }
        exitProcess(code)
    }

    private fun usage() {
        println(
            """
            hecate replay harness (US-10)

              replay    --bundle <f> --session <f> [--out <f>] [--verbose]
                        Replay a session; print the decision timeline.
              bundle    --sessions <dir> [--out <f>] [--min-members N]
                        Cluster a directory of session files into a route bundle.
              verify    --bundle <f> --session <f> --golden <f>
                        Replay and compare against a golden match log. Exit 1 on divergence.
              selfcheck --bundle <f> --session <f>
                        Assert that batched processing equals streamed processing.
              onwrist   --bundle <f> --session <f>
                        Compare the replayed log against the log the watch stored
                        inside the session file itself.
              takeout   [--input <zip|dir|glob>] [--out <dir|file>] [--existing <backup.zip>]
                        [--map Name=TYPE,...] [--zone <id>] [--no-auto] [--verbose]
                        Turn a Google Health takeout into a backup archive the
                        phone app can restore. Defaults to every *.zip under
                        ./takeout and writes to ./takeout/parsed.
            """.trimIndent(),
        )
    }

    // ------------------------------------------------------------------ commands

    private fun cmdReplay(o: Map<String, String>): Int {
        val bundle = loadBundle(o["bundle"]) ?: return 2
        val session = loadSession(o["session"]) ?: return 2
        val log = ArrayList<MatchLogEntry>()
        val states = run(session, bundle, log)

        println("session ${session.id}  ${session.samples.size} samples  ${session.header.exerciseType}")
        println("bundle  v${bundle.version}  ${bundle.routes.size} routes")
        val names = bundle.routes.associate { it.geometry.routeId to it.geometry.name }
        var lastPhase: MatchPhase? = null
        var lastRoute: Long? = null
        for (s in states) {
            val verbose = o.containsKey("verbose")
            if (verbose || s.phase != lastPhase || s.committedRouteId != lastRoute || s.deviationAlert) {
                println(
                    "t=%7.1fs d=%7.0fm %-9s %-24s null=%.3f %s%s".format(
                        s.tOffsetMs / 1000.0,
                        s.travelledM,
                        s.phase,
                        s.committedRouteId?.let { names[it] ?: "route $it" } ?: "-",
                        s.nullWeight,
                        s.deltaVsMedianS?.let { "Δ=%+.0fs ".format(it) } ?: "",
                        if (s.deviationAlert) "!! ABWEICHUNG" else "",
                    ),
                )
                lastPhase = s.phase
                lastRoute = s.committedRouteId
            }
        }
        val final = states.lastOrNull()
        println("---")
        println("final: ${final?.phase} route=${final?.committedRouteId?.let { names[it] } ?: "-"}")
        o["out"]?.let { File(it).writeText(BundleCodec.encodeMatchLog(log)); println("log -> $it") }
        return 0
    }

    private fun cmdBundle(o: Map<String, String>): Int {
        val dir = o["sessions"]?.let { File(it) } ?: run { usage(); return 2 }
        if (!dir.isDirectory) { System.err.println("not a directory: $dir"); return 2 }
        val sessions = dir.listFiles { f -> f.isFile && (f.name.endsWith(".jsonl") || f.name.endsWith(".json")) }
            ?.sortedBy { it.name }
            ?.mapNotNull { f -> SessionCodec.decodeSession(f.readLines().asSequence()) }
            ?: emptyList()
        if (sessions.isEmpty()) { System.err.println("no sessions found in $dir"); return 2 }

        val tracks = sessions.mapNotNull { Track.of(it) }
        val hr = sessions.associate { it.id to it.timedSamples }
        val minMembers = o["min-members"]?.toIntOrNull() ?: de.hecate.core.Tuning.MIN_MEMBERS
        val computed = BundleBuilder.computeRoutes(tracks, hr, minMembers)
        val bundle = BundleBuilder.bundleFrom(tracks, hr)

        println("${sessions.size} sessions -> ${computed.size} routes")
        computed.forEachIndexed { i, r ->
            println(
                "  route %d  %5.2f km  %2d members  medoid=%s".format(
                    i + 1, r.lengthM / 1000.0, r.memberSessionIds.size, r.medoidSessionId,
                ),
            )
        }
        o["out"]?.let { File(it).writeText(BundleCodec.encode(bundle)); println("bundle -> $it") }
        return 0
    }

    private fun cmdVerify(o: Map<String, String>): Int {
        val bundle = loadBundle(o["bundle"]) ?: return 2
        val session = loadSession(o["session"]) ?: return 2
        val goldenFile = o["golden"]?.let { File(it) } ?: run { usage(); return 2 }
        val log = ArrayList<MatchLogEntry>()
        run(session, bundle, log)
        if (!goldenFile.exists()) {
            goldenFile.writeText(BundleCodec.encodeMatchLog(log))
            println("golden written: $goldenFile (${log.size} entries)")
            return 0
        }
        val golden = BundleCodec.decodeMatchLog(goldenFile.readText())
        return report("golden", golden, log)
    }

    private fun cmdOnWrist(o: Map<String, String>): Int {
        val bundle = loadBundle(o["bundle"]) ?: return 2
        val session = loadSession(o["session"]) ?: return 2
        if (session.matchLog.isEmpty()) {
            System.err.println("session contains no on-wrist match log")
            return 2
        }
        val log = ArrayList<MatchLogEntry>()
        run(session, bundle, log)
        return report("on-wrist", session.matchLog, log)
    }

    private fun cmdSelfCheck(o: Map<String, String>): Int {
        val bundle = loadBundle(o["bundle"]) ?: return 2
        val session = loadSession(o["session"]) ?: return 2

        val streamed = ArrayList<MatchLogEntry>()
        run(session, bundle, streamed)

        val batched = ArrayList<MatchLogEntry>()
        val m = RouteMatcher(
            bundle, session.header.exerciseType,
            session.header.startedAtUtcMs, session.header.tzOffsetMinutes, batched::add,
        )
        // Deliver in bursts of 30, as an ambient-mode batch would arrive.
        session.samples.chunked(30).forEach { m.updateAll(it) }

        return report("streamed vs batched", streamed, batched)
    }

    // ------------------------------------------------------------------- helpers

    private fun report(what: String, expected: List<MatchLogEntry>, actual: List<MatchLogEntry>): Int {
        val diffs = diff(expected, actual)
        if (diffs.isEmpty()) {
            println("OK: $what matches (${expected.size} entries)")
            return 0
        }
        System.err.println("DIVERGENCE in $what (${diffs.size} differing entries):")
        diffs.take(20).forEach { System.err.println("  $it") }
        return 1
    }

    private fun diff(a: List<MatchLogEntry>, b: List<MatchLogEntry>): List<String> {
        val out = ArrayList<String>()
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrNull(i)
            val y = b.getOrNull(i)
            if (x == null) { out.add("[$i] missing on the left:  $y"); continue }
            if (y == null) { out.add("[$i] missing on the right: $x"); continue }
            if (x != y) out.add("[$i] $x != $y")
        }
        return out
    }

    fun run(session: RecordedSession, bundle: RouteBundle, log: MutableList<MatchLogEntry>) =
        RouteMatcher(
            bundle,
            session.header.exerciseType,
            session.header.startedAtUtcMs,
            session.header.tzOffsetMinutes,
            log::add,
        ).let { m -> session.samples.map { m.update(it) } }

    /**
     * US-9 at the scale of a whole history: a Google Health takeout in, a backup
     * archive the phone can restore out.
     *
     * The work is [TakeoutImport]'s; this is argument handling and a report. The
     * report is the point of running it on a laptop rather than on the phone —
     * every workout the import declined to take is listed with the reason, which
     * on a phone screen would be a toast saying "63 imported" and nothing else.
     */
    private fun cmdTakeout(o: Map<String, String>): Int {
        val defaultDir = File("takeout")
        val zips = try {
            TakeoutSource.resolve(o["input"], defaultDir)
        } catch (e: TakeoutSource.TakeoutException) {
            System.err.println(e.message); return 2
        }
        if (zips.isEmpty()) {
            System.err.println("no takeout ZIPs found in ${o["input"] ?: defaultDir.path}")
            return 2
        }

        val overrides = try {
            parseTypeMap(o["map"])
        } catch (e: IllegalArgumentException) {
            System.err.println(e.message); return 2
        }
        val zone = o["zone"]?.let {
            runCatching { ZoneId.of(it) }.getOrElse { _ -> System.err.println("unknown zone: $it"); return 2 }
        }
        val existing = o["existing"]?.let { path ->
            val f = File(path)
            if (!f.isFile) { System.err.println("no such file: $path"); return 2 }
            try {
                ArchiveCodec.Reader(f).use { it.database.sessions }
            } catch (e: Exception) {
                System.err.println("not a Hecate backup: $path (${e.message})"); return 2
            }
        } ?: emptyList()

        println("takeout: ${zips.joinToString(", ") { it.name }}")
        val result = TakeoutSource(zips).use { source ->
            TakeoutImport.read(
                source,
                TakeoutImport.Options(
                    includeAutoDetected = !o.containsKey("no-auto"),
                    typeOverrides = overrides,
                    existing = existing,
                    zone = zone,
                ),
            )
        }

        val out = outputFile(o["out"], defaultDir)
        out.parentFile?.mkdirs()
        var bytes = 0L
        out.outputStream().use { stream ->
            val writer = ArchiveCodec.Writer(stream)
            writer.use { w ->
                w.writeManifest(
                    Archive.Manifest(
                        formatVersion = Archive.FORMAT_VERSION,
                        exportedAtUtcMs = System.currentTimeMillis(),
                        appVersion = "core-takeout",
                        sessionCount = result.sessions.size,
                        routeCount = 0,
                    ),
                )
                w.writeDatabase(result.database)
                for (session in result.sessions) w.writeSession(session)
            }
            bytes = writer.bytesWritten
        }

        report(result, existing.size, out, bytes, o.containsKey("verbose"))
        // An empty archive is a legitimate answer — it is what a second run
        // against the same history produces — so it is not an error exit.
        return 0
    }

    private fun report(
        result: TakeoutImport.Result,
        existing: Int,
        out: File,
        bytes: Long,
        verbose: Boolean,
    ) {
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
        for (name in result.duplicateEntries.take(5)) println("note: $name appears in more than one part; the first was used")
        if (result.duplicateEntries.size > 5) println("note: ... and ${result.duplicateEntries.size - 5} more")

        println("fallback time zone: ${result.fallbackZone} (used only where an exercise named no offset)")
        if (existing > 0) println("compared against $existing sessions already in the app")
        println("---")
        for (session in result.sessions.sortedBy { it.header.startedAtUtcMs }) {
            val track = Track.of(session)
            println(
                "%s  %-7s %6.2f km  %5.0f min  %5d pts  %s".format(
                    stamp.withZone(java.time.ZoneOffset.ofTotalSeconds(session.header.tzOffsetMinutes * 60))
                        .format(Instant.ofEpochMilli(session.header.startedAtUtcMs)),
                    session.header.exerciseType,
                    (track?.totalDistanceM ?: 0.0) / 1000.0,
                    session.activeDurationMs / 60_000.0,
                    session.samples.size,
                    session.id,
                ),
            )
        }
        if (result.skipped.isNotEmpty()) {
            println("--- not imported (${result.skipped.size})")
            // The routine ones as one line per reason: a takeout has hundreds of
            // auto-detected rows that were never going to hold a track, and
            // printing them individually hides the few that matter.
            val (notable, routine) = result.skipped.partition { it.notable }
            for ((reason, group) in routine.groupBy { it.reason }.entries.sortedByDescending { it.value.size }) {
                println("%5d  %s".format(group.size, reason))
            }
            val shown = if (verbose) notable else notable.take(20)
            for (s in shown) {
                println(
                    "%s  %-24s %s".format(
                        stamp.format(Instant.ofEpochMilli(s.startUtcMs)),
                        s.activityName.ifEmpty { "?" },
                        s.reason,
                    ),
                )
            }
            if (shown.size < notable.size) {
                println("... and ${notable.size - shown.size} more; --verbose to see them all")
            }
        }
        println("---")
        println("${result.sessions.size} sessions -> $out (${bytes / 1024} KiB)")
        if (result.sessions.isEmpty()) println("nothing new to import; the archive is empty")
    }

    /** `--out` may be the archive itself or the directory to put it in. */
    private fun outputFile(arg: String?, defaultDir: File): File {
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.now())
        val name = "hecate-takeout-$stamp.zip"
        if (arg == null) return File(defaultDir, "parsed/$name")
        val f = File(arg)
        return if (f.isDirectory || !arg.endsWith(".zip")) File(f, name) else f
    }

    /** `--map "Schwimmen=WALKING,Tennis=RUNNING"`. */
    private fun parseTypeMap(arg: String?): Map<String, ExerciseType> {
        if (arg == null) return emptyMap()
        return arg.split(',').filter { it.isNotBlank() }.associate { pair ->
            val (name, type) = pair.split('=', limit = 2).let {
                require(it.size == 2) { "--map wants Name=TYPE, got: $pair" }
                it[0].trim() to it[1].trim().uppercase()
            }
            val parsed = ExerciseType.parse(type) ?: throw IllegalArgumentException(
                "--map: no such type: $type (${ExerciseType.entries.joinToString(", ") { it.name }})",
            )
            name.lowercase() to parsed
        }
    }

    private fun loadBundle(path: String?): RouteBundle? {
        if (path == null) { usage(); return null }
        val f = File(path)
        if (!f.exists()) { System.err.println("no such file: $path"); return null }
        return if (f.name.endsWith(".gz")) f.inputStream().use { BundleCodec.readGzip(it) }
        else BundleCodec.decode(f.readText())
    }

    private fun loadSession(path: String?): RecordedSession? {
        if (path == null) { usage(); return null }
        val f = File(path)
        if (!f.exists()) { System.err.println("no such file: $path"); return null }
        return SessionCodec.decodeSession(f.readLines().asSequence())
            ?: run { System.err.println("not a session file: $path"); null }
    }

    private fun parseOptions(args: List<String>): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var i = 0
        while (i < args.size) {
            val a = args[i]
            if (a.startsWith("--")) {
                val key = a.removePrefix("--")
                val next = args.getOrNull(i + 1)
                if (next != null && !next.startsWith("--")) { out[key] = next; i += 2 } else { out[key] = "true"; i++ }
            } else i++
        }
        return out
    }
}
