package de.hecate.core

import de.hecate.core.cluster.BundleBuilder
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.geo.Track
import de.hecate.core.matcher.RouteMatcher
import de.hecate.core.model.MatchEvent
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.MatchPhase
import de.hecate.core.model.MatchState
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.RouteBundle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Manual route priority: the user's answer to a question the evidence cannot
 * answer, namely which of two routes that share their first two kilometres to
 * assume they are on.
 */
class PriorityTest {

    private fun day(n: Int) = Synthetic.MONDAY_0730_UTC + n * Synthetic.DAY_MS

    /** The long work route and a shortcut of it, sharing their first 2 km. */
    private fun learned(): RouteBundle {
        val sessions = buildList {
            repeat(3) { add(Synthetic.record("long$it", Synthetic.workRoute, day(it), seed = it + 1)) }
            repeat(3) { add(Synthetic.record("short$it", Synthetic.shortcut, day(10 + it), seed = 20 + it)) }
        }
        return BundleBuilder.bundleFrom(
            sessions.mapNotNull { Track.of(it) },
            sessions.associate { it.id to it.samples },
        )
    }

    private fun polyLength(p: List<LatLon>): Double {
        var d = 0.0
        for (i in 1 until p.size) d += Geo.haversine(p[i - 1], p[i])
        return d
    }

    /** Identify a bundle route by the fixture shape it was learned from. */
    private fun idOf(bundle: RouteBundle, shape: List<LatLon>): Long =
        bundle.routes.minByOrNull { e ->
            val g = e.geometry.points
            Geo.haversine(LatLon(g.first().lat, g.first().lon), shape.first()) +
                Geo.haversine(LatLon(g.last().lat, g.last().lon), shape.last()) +
                abs(e.geometry.lengthM - polyLength(shape))
        }!!.geometry.routeId

    private fun withPriorities(bundle: RouteBundle, priorities: Map<Long, Int>): RouteBundle =
        bundle.copy(
            routes = bundle.routes.map {
                it.copy(priority = priorities[it.geometry.routeId] ?: Tuning.DEFAULT_ROUTE_PRIORITY)
            },
        )

    private fun replay(
        session: RecordedSession,
        bundle: RouteBundle,
        log: MutableList<MatchLogEntry>? = null,
    ): List<MatchState> {
        val m = RouteMatcher(
            bundle, session.header.exerciseType,
            session.header.startedAtUtcMs, session.header.tzOffsetMinutes,
            log?.let { l -> { e: MatchLogEntry -> l.add(e); Unit } },
        )
        return session.samples.map { m.update(it) }
    }

    // ------------------------------------------------------------------- tests

    @Test
    fun `by default nothing changes — equal priorities decide nothing`() {
        val bundle = learned()
        assertTrue(bundle.routes.all { it.priority == Tuning.DEFAULT_ROUTE_PRIORITY })
        val today = Synthetic.record("today", Synthetic.workRoute, day(50), seed = 501)
        val states = replay(today, bundle)
        // The shared 2 km stay in Suche…, exactly as before the feature existed.
        assertTrue(
            states.none { it.travelledM < 1900 && it.phase == MatchPhase.COMMITTED },
            "committed inside the shared segment with no priority set",
        )
    }

    @Test
    fun `a higher priority commits inside the shared segment`() {
        val base = learned()
        val longId = idOf(base, Synthetic.workRoute)
        val bundle = withPriorities(base, mapOf(longId to 1))

        val today = Synthetic.record("today", Synthetic.workRoute, day(51), seed = 502)
        val states = replay(today, bundle)

        val early = states.firstOrNull { it.phase == MatchPhase.COMMITTED }
        assertNotNull(early, "never committed at all")
        assertEquals(longId, early.committedRouteId)
        assertTrue(early.committedByPriority, "should be flagged as a priority commitment")
        // Committed while the two routes still coincide, which is the whole point.
        assertTrue(early.travelledM < 1900, "only committed at ${early.travelledM} m, after the branch")
        assertTrue(early.travelledM >= Tuning.COMMIT_MIN_M, "committed before COMMIT_MIN_M")
    }

    @Test
    fun `a priority commitment carries remaining distance and eta`() {
        val base = learned()
        val longId = idOf(base, Synthetic.workRoute)
        val bundle = withPriorities(base, mapOf(longId to 2))

        val today = Synthetic.record("today", Synthetic.workRoute, day(52), speedMps = 3.0, seed = 503)
        val states = replay(today, bundle)

        val early = states.first { it.phase == MatchPhase.COMMITTED && it.travelledM < 1900 }
        assertNotNull(early.remainingM, "no remaining distance while committed by priority")
        assertNotNull(early.etaSecondsFromNow, "no ETA while committed by priority")

        val totalS = today.activeDurationMs / 1000.0
        val predicted = early.tOffsetMs / 1000.0 + early.etaSecondsFromNow!!
        assertTrue(
            abs(predicted - totalS) < totalS * 0.25,
            "ETA from inside the shared segment predicted $predicted vs actual $totalS",
        )
    }

    @Test
    fun `a tie at the top of the priorities decides nothing`() {
        val base = learned()
        // Both raised, and equally: the user has expressed a preference over the
        // rest of the world but not between these two.
        val bundle = withPriorities(base, base.routes.associate { it.geometry.routeId to 1 })
        val today = Synthetic.record("today", Synthetic.workRoute, day(53), seed = 504)
        val states = replay(today, bundle)
        assertTrue(
            states.none { it.travelledM < 1900 && it.phase == MatchPhase.COMMITTED },
            "committed on a tie",
        )
    }

    @Test
    fun `the evidence overrules the priority once the paths separate`() {
        val base = learned()
        val longId = idOf(base, Synthetic.workRoute)
        val shortId = idOf(base, Synthetic.shortcut)
        // The long route is preferred, but today the shortcut is actually run.
        val bundle = withPriorities(base, mapOf(longId to 2, shortId to -1))

        val today = Synthetic.record("today", Synthetic.shortcut, day(54), seed = 505)
        val log = ArrayList<MatchLogEntry>()
        val states = replay(today, bundle, log)

        // Early on it assumes the preferred route, as instructed...
        val early = states.first { it.phase == MatchPhase.COMMITTED }
        assertEquals(longId, early.committedRouteId)
        assertTrue(early.committedByPriority)

        // ...and gives it up as soon as the ground says otherwise.
        assertEquals(MatchPhase.COMMITTED, states.last().phase)
        assertEquals(shortId, states.last().committedRouteId, "did not switch to the route actually run")
        assertTrue(!states.last().committedByPriority, "final commitment should rest on evidence")
        assertTrue(log.any { it.event == MatchEvent.COMMIT_BY_PRIORITY })
        assertTrue(log.any { it.event == MatchEvent.DEMOTE })
    }

    @Test
    fun `a priority commitment is not claimed as evidence once it is one`() {
        val base = learned()
        val longId = idOf(base, Synthetic.workRoute)
        val bundle = withPriorities(base, mapOf(longId to 1))
        val today = Synthetic.record("today", Synthetic.workRoute, day(55), seed = 506)
        val states = replay(today, bundle)

        // After the branch the long route is alone and the flag must drop.
        val late = states.last { it.phase == MatchPhase.COMMITTED }
        assertEquals(longId, late.committedRouteId)
        assertTrue(!late.committedByPriority, "still claiming to be a priority commitment after separation")
    }

    @Test
    fun `priority never invents a route that is not there`() {
        val base = learned()
        val longId = idOf(base, Synthetic.workRoute)
        val bundle = withPriorities(base, mapOf(longId to 2))
        // A path with nothing in common with either route.
        val today = Synthetic.record("today", Synthetic.novelRoute, day(56), seed = 507)
        val states = replay(today, bundle)
        assertTrue(states.none { it.phase == MatchPhase.COMMITTED }, "priority committed on a novel path")
        assertEquals(MatchPhase.UNKNOWN, states.last().phase)
    }

    @Test
    fun `taking a known shortcut raises no deviation alert`() {
        // The case that makes this reachable at all: committed to the long route
        // by priority, the user then takes the shortcut — a route they have run
        // three times. Leaving the long route is not leaving the route network,
        // and buzzing at them for doing something entirely familiar would be a
        // false alarm (§5.6 takes the minimum across live candidates).
        val base = learned()
        val longId = idOf(base, Synthetic.workRoute)
        val shortId = idOf(base, Synthetic.shortcut)
        val bundle = withPriorities(base, mapOf(longId to 1))

        val today = Synthetic.record("today", Synthetic.shortcut, day(57), seed = 508)
        val states = replay(today, bundle)

        assertTrue(states.any { it.committedRouteId == longId }, "never committed to the long route")
        assertEquals(shortId, states.last().committedRouteId)
        assertEquals(0, states.count { it.deviationAlert }, "alerted for running a learned shortcut")
        assertTrue(states.none { it.offRoute }, "registered as off route on a known route")
    }

    @Test
    fun `leaving the network entirely still alerts`() {
        // The counterpart: the same commitment, but this time a genuine detour
        // that matches nothing. Suppressing the false alarm above must not have
        // suppressed the real one.
        val base = learned()
        val longId = idOf(base, Synthetic.workRoute)
        val bundle = withPriorities(base, mapOf(longId to 1))

        val detour = Synthetic.path(
            listOf(
                Synthetic.at(0.0, 0.0), Synthetic.at(1200.0, 0.0), Synthetic.at(1250.0, -220.0),
                Synthetic.at(1600.0, -240.0), Synthetic.at(1700.0, 0.0), Synthetic.at(2000.0, 0.0),
                Synthetic.at(2000.0, 1500.0), Synthetic.at(3000.0, 1500.0),
            ),
        )
        val today = Synthetic.record("today", detour, day(58), seed = 509)
        val states = replay(today, bundle)
        assertEquals(1, states.count { it.deviationAlert }, "no alert for a genuine detour")
    }

    @Test
    fun `priority is carried through bundle serialization`() {
        val bundle = withPriorities(learned(), mapOf(1L to 2, 2L to -1))
        val out = java.io.ByteArrayOutputStream()
        de.hecate.core.io.BundleCodec.writeGzip(bundle, out)
        val back = de.hecate.core.io.BundleCodec.readGzip(java.io.ByteArrayInputStream(out.toByteArray()))
        assertEquals(bundle, back)
    }

    @Test
    fun `a bundle written before priorities existed reads back at the default`() {
        // Forward compatibility: the watch may still hold an older bundle.
        val json = """
            {"version":3,"builtAtUtcMs":0,"routes":[{"geometry":{"routeId":1,"name":"Alt",
            "exerciseType":"RUNNING","points":[{"lat":50.0,"lon":9.0,"d":0.0},
            {"lat":50.001,"lon":9.0,"d":111.0}]},"profile":{"routeId":1,"binM":10.0,
            "medianCumTimeS":[0.0,10.0]},"prior":{"totalCount":3}}]}
        """.trimIndent()
        val bundle = de.hecate.core.io.BundleCodec.decode(json)
        assertEquals(Tuning.DEFAULT_ROUTE_PRIORITY, bundle.routes.single().priority)
    }
}
