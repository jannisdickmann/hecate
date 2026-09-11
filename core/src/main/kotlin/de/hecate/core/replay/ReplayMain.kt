package de.hecate.core.replay

import de.hecate.core.geo.Track
import de.hecate.core.cluster.BundleBuilder
import de.hecate.core.io.BundleCodec
import de.hecate.core.io.SessionCodec
import de.hecate.core.matcher.RouteMatcher
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.MatchPhase
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.RouteBundle
import java.io.File
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
        val hr = sessions.associate { it.id to it.samples }
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
