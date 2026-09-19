package de.hecate.core.replay

import de.hecate.core.archive.Archive
import de.hecate.core.archive.ArchiveCodec
import de.hecate.core.geo.Track
import de.hecate.core.cluster.BundleBuilder
import de.hecate.core.io.BundleCodec
import de.hecate.core.io.SessionCodec
import de.hecate.core.matcher.RouteMatcher
import de.hecate.core.model.MatchEvent
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
        exitProcess(dispatch(args))
    }

    /**
     * Everything [main] does except ending the process, which is what lets the
     * commands be tested: a test that called [main] would take the JVM with it.
     */
    fun dispatch(args: Array<String>): Int {
        if (args.isEmpty()) {
            usage()
            return 2
        }
        val opts = parseOptions(args.drop(1))
        return when (args[0]) {
            "replay" -> cmdReplay(opts)
            "bundle" -> cmdBundle(opts)
            "verify" -> cmdVerify(opts)
            "selfcheck" -> cmdSelfCheck(opts)
            "onwrist" -> cmdOnWrist(opts)
            "sessions" -> cmdSessions(opts)
            "extract" -> cmdExtract(opts)
            "takeout" -> cmdTakeout(opts)
            "help", "--help", "-h" -> { usage(); 0 }
            else -> { usage(); 2 }
        }
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
              sessions  (--archive <zip> | --sessions <dir>) [--bundle <f>]
                        [--type T] [--matched]
                        List the stored activities and what each one matches, so
                        that a replay can be picked by what it does rather than by
                        the date on it. --bundle answers for that bundle.
              extract   --archive <zip> --session <id> [--out <f>]
                        Write one session out of a backup as JSONL, by id or by
                        any unique prefix of one.
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

    /**
     * Which stored activities are worth replaying, and which of them lock on.
     *
     * The point of this listing is its last column. Replaying a session that never
     * matches anything shows a minute of "Suche…" and nothing else, and there are
     * only two ways to know which is which before spending that minute: the
     * archive remembers what the phone concluded when it ingested each session,
     * and `--bundle` replays every session against the bundle you are about to put
     * on the watch — which answers for *that* bundle rather than for the day the
     * session was recorded, and is therefore the one to trust when picking
     * something to watch the matcher do.
     */
    private fun cmdSessions(o: Map<String, String>): Int {
        val bundle = o["bundle"]?.let { loadBundle(it) ?: return 2 }
        val typeFilter = o["type"]?.let { name ->
            ExerciseType.parse(name.uppercase()) ?: run {
                System.err.println(
                    "no such type: $name (${ExerciseType.entries.joinToString(", ") { it.name }})",
                )
                return 2
            }
        }

        val archive = o["archive"]
        val rows = when {
            archive != null -> archiveRows(File(archive), bundle) ?: return 2
            o["sessions"] != null -> directoryRows(File(o["sessions"]!!), bundle) ?: return 2
            else -> { usage(); return 2 }
        }

        val shown = rows
            .filter { typeFilter == null || it.type == typeFilter }
            .filter { !o.containsKey("matched") || it.matched }
            .sortedBy { it.startedAtUtcMs }
        if (shown.isEmpty()) {
            println("no sessions to list")
            return 0
        }

        // The id column is there to be typed back into `extract`, so it is only
        // as short as it can be while still naming one session — and it is
        // measured against every session in the archive rather than against the
        // ones that survived a filter, because `extract` searches all of them.
        val width = shortIdWidth(rows.map { it.id })
        println("%-${width}s  %-16s %-7s %10s %9s  %s".format("id", "started", "type", "distance", "active", "locks onto"))
        for (r in shown) {
            println(
                "%-${width}s  %s %-7s %7.2f km %9s  %s".format(
                    r.id.take(width),
                    // In the time zone the session was recorded in, which for a
                    // holiday's worth of workouts is not this laptop's.
                    LOCAL_STAMP.withZone(java.time.ZoneOffset.ofTotalSeconds(r.tzOffsetMinutes * 60))
                        .format(Instant.ofEpochMilli(r.startedAtUtcMs)),
                    r.type,
                    r.distanceM / 1000.0,
                    hms(r.activeMs),
                    r.verdict,
                ),
            )
        }
        println("---")
        println("${shown.size} of ${rows.size} sessions")
        if (bundle == null) {
            println("note: verdicts are what was decided when each session was recorded or ingested.")
            println("      --bundle <f> replays them against a bundle instead, which is the")
            println("      question you actually care about before sideloading that bundle.")
        }
        if (archive != null) {
            println("extract one with: extract --archive $archive --session <id>")
        }
        return 0
    }

    /**
     * One session out of a backup, in the watch's own JSONL format.
     *
     * The format is the same on both sides of the ZIP (see [Archive]), so this is
     * a copy rather than a conversion — and what comes out is exactly what
     * `replay`, `verify` and `tools/emu replay` all read.
     */
    private fun cmdExtract(o: Map<String, String>): Int {
        val archive = o["archive"]?.let { File(it) } ?: run { usage(); return 2 }
        if (!archive.isFile) { System.err.println("no such file: $archive"); return 2 }
        val wanted = o["session"] ?: run { usage(); return 2 }

        val reader = try {
            ArchiveCodec.Reader(archive)
        } catch (e: Exception) {
            System.err.println("not a Hecate backup: $archive (${e.message})")
            return 2
        }
        reader.use {
            val ids = it.database.sessions.map { s -> s.id }
            // Exact first, then unique prefix: a full UUID is not something anyone
            // retypes off a listing, and the listing prints the first eight
            // characters for exactly this reason.
            val matches = ids.filter { id -> id == wanted }
                .ifEmpty { ids.filter { id -> id.startsWith(wanted) } }
            val id = when (matches.size) {
                0 -> { System.err.println("no session in $archive starts with \"$wanted\""); return 2 }
                1 -> matches.single()
                else -> {
                    System.err.println("\"$wanted\" matches ${matches.size} sessions:")
                    matches.take(10).forEach { m -> System.err.println("  $m") }
                    return 2
                }
            }
            val session = it.session(id) ?: run {
                System.err.println("$id is in the database part but the archive holds no file for it")
                return 2
            }
            val out = o["out"]?.let { path -> File(path) } ?: File("fixtures/$id.jsonl")
            out.parentFile?.mkdirs()
            out.writeText(SessionCodec.encodeSession(session))
            println("$id -> $out (${session.samples.size} samples)")
            return 0
        }
    }

    // --------------------------------------------------- listing the candidates

    private data class SessionRow(
        val id: String,
        val startedAtUtcMs: Long,
        val tzOffsetMinutes: Int,
        val type: ExerciseType,
        val distanceM: Double,
        val activeMs: Long,
        val verdict: String,
        val matched: Boolean,
    )

    /**
     * Rows from a backup.
     *
     * Without a bundle this reads nothing but `database.json`, which already
     * carries the distance, the duration and what the phone matched — 65 sessions
     * without parsing a single sample. A `--bundle` is what makes it parse them,
     * because a replay needs the samples.
     */
    private fun archiveRows(file: File, bundle: RouteBundle?): List<SessionRow>? {
        if (!file.isFile) { System.err.println("no such file: $file"); return null }
        val reader = try {
            ArchiveCodec.Reader(file)
        } catch (e: Exception) {
            System.err.println("not a Hecate backup: $file (${e.message})")
            return null
        }
        return reader.use {
            val names = it.database.routes.associate { r -> r.id to r.name }
            it.database.sessions.map { row ->
                val replayed = bundle?.let { b -> it.session(row.id)?.let { s -> verdictOf(s, b) } }
                SessionRow(
                    id = row.id,
                    startedAtUtcMs = row.startedAtUtc,
                    tzOffsetMinutes = row.tzOffsetMinutes,
                    type = row.exerciseType,
                    distanceM = row.totalDistanceM,
                    activeMs = row.activeDurationMs,
                    verdict = replayed?.text ?: storedVerdict(row, names),
                    matched = replayed?.matched ?: (row.matchedRouteId != null),
                )
            }
        }
    }

    /** Rows from a directory of session files, as `fixtures/` holds them. */
    private fun directoryRows(dir: File, bundle: RouteBundle?): List<SessionRow>? {
        if (!dir.isDirectory) { System.err.println("not a directory: $dir"); return null }
        val files = dir.listFiles { f -> f.isFile && (f.name.endsWith(".jsonl") || f.name.endsWith(".json")) }
            ?.sortedBy { it.name }
            ?: emptyList()
        val rows = ArrayList<SessionRow>()
        for (f in files) {
            val session = SessionCodec.decodeSession(f.readLines().asSequence())
            if (session == null) {
                System.err.println("not a session file, skipped: $f")
                continue
            }
            val replayed = bundle?.let { verdictOf(session, it) }
            rows.add(
                SessionRow(
                    id = session.id,
                    startedAtUtcMs = session.header.startedAtUtcMs,
                    tzOffsetMinutes = session.header.tzOffsetMinutes,
                    type = session.header.exerciseType,
                    distanceM = Track.of(session)?.totalDistanceM ?: 0.0,
                    activeMs = session.activeDurationMs,
                    verdict = replayed?.text ?: ownLogVerdict(session),
                    matched = replayed?.matched ?: session.matchLog.any { it.event.isCommit },
                ),
            )
        }
        return rows
    }

    private data class Verdict(val text: String, val matched: Boolean)

    /** What this bundle does with this session, which is what a replay will show. */
    private fun verdictOf(session: RecordedSession, bundle: RouteBundle): Verdict {
        val log = ArrayList<MatchLogEntry>()
        val states = run(session, bundle, log)
        val names = bundle.routes.associate { it.geometry.routeId to it.geometry.name }
        val commit = log.firstOrNull { it.event.isCommit }
        val final = states.lastOrNull()
        if (commit == null) {
            return Verdict(if (final?.phase == MatchPhase.UNKNOWN) "unknown" else "no commit", false)
        }
        return Verdict(
            "%s @ %.1f km, t=%.0fs%s".format(
                names[commit.committedRouteId] ?: "route ${commit.committedRouteId}",
                commit.travelledM / 1000.0,
                commit.tOffsetMs / 1000.0,
                // A route that is committed to and then lost is still worth
                // replaying — it is the more interesting thing to watch — but you
                // should know before you start that the name goes away again.
                if (final?.phase != MatchPhase.COMMITTED) ", lost again" else "",
            ),
            matched = true,
        )
    }

    /** What the phone concluded about this session when it ingested it. */
    private fun storedVerdict(row: Archive.Session, names: Map<Long, String>): String {
        val id = row.matchedRouteId ?: return "-"
        val name = names[id] ?: "route $id"
        return row.committedAtM?.let { "%s @ %.1f km".format(name, it / 1000.0) } ?: name
    }

    /** What the watch itself decided while recording, if it held a bundle then. */
    private fun ownLogVerdict(session: RecordedSession): String {
        if (session.header.bundleVersion == null) return "no bundle when recorded"
        val commit = session.matchLog.firstOrNull { it.event.isCommit } ?: return "no commit"
        return "route %d @ %.1f km, t=%.0fs".format(
            commit.committedRouteId ?: commit.routeId ?: -1L,
            commit.travelledM / 1000.0,
            commit.tOffsetMs / 1000.0,
        )
    }

    /** "42:15", or "1:04:33" once it needs the hours. */
    private fun hms(ms: Long): String {
        val total = ms / 1000
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
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

    /** Enough of a UUID to pick one session out of a few hundred by eye, and the
     *  length the id column starts at. */
    private const val SHORT_ID = 8

    /**
     * The shortest prefix length that still tells every one of [ids] apart, never
     * below [SHORT_ID].
     *
     * A listing whose ids are ambiguous is worse than one with long ids: the
     * whole column exists so that it can be pasted into `extract --session`, and
     * `extract` refuses a prefix that matches two sessions.
     */
    private fun shortIdWidth(ids: List<String>): Int {
        val longest = ids.maxOfOrNull { it.length } ?: SHORT_ID
        for (n in SHORT_ID..longest) {
            if (ids.distinctBy { it.take(n) }.size == ids.size) return n
        }
        return longest
    }

    private val LOCAL_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

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
