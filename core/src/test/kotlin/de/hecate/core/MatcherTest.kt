package de.hecate.core

import de.hecate.core.cluster.BundleBuilder
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.geo.Track
import de.hecate.core.matcher.RouteMatcher
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.MatchPhase
import de.hecate.core.model.MatchState
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.RouteBundle
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MatcherTest {

    /** A bundle learned from three recordings each of the work route, the shortcut
     *  and the reverse direction. */
    private fun learnedBundle(): RouteBundle {
        val sessions = buildList {
            repeat(3) { add(Synthetic.record("long$it", Synthetic.workRoute, day(it), seed = it + 1)) }
            repeat(3) { add(Synthetic.record("short$it", Synthetic.shortcut, day(10 + it), seed = 20 + it)) }
            repeat(3) { add(Synthetic.record("rev$it", Synthetic.reverseRoute, day(20 + it), seed = 40 + it)) }
        }
        val tracks = sessions.mapNotNull { Track.of(it) }
        return BundleBuilder.bundleFrom(tracks, sessions.associate { it.id to it.samples })
    }

    private fun day(n: Int) = Synthetic.MONDAY_0730_UTC + n * Synthetic.DAY_MS

    private fun replay(session: RecordedSession, bundle: RouteBundle, log: MutableList<MatchLogEntry>? = null): List<MatchState> {
        val m = RouteMatcher(bundle, session.header.exerciseType, session.header.startedAtUtcMs, session.header.tzOffsetMinutes, log?.let { l -> { e: MatchLogEntry -> l.add(e); Unit } })
        return session.samples.map { m.update(it) }
    }

    private fun routeIdOf(bundle: RouteBundle, sessionPrefix: String): Long {
        // Identify a route by which fixture shape its geometry follows: compare the
        // last point of the geometry against the fixture's own last point.
        val target = when (sessionPrefix) {
            "long" -> Synthetic.workRoute
            "short" -> Synthetic.shortcut
            "rev" -> Synthetic.reverseRoute
            else -> error("unknown fixture")
        }
        return bundle.routes.minByOrNull { e ->
            val g = e.geometry.points
            val a = LatLon(g.first().lat, g.first().lon)
            val b = LatLon(g.last().lat, g.last().lon)
            Geo.haversine(a, target.first()) + Geo.haversine(b, target.last()) +
                abs(e.geometry.lengthM - polyLength(target))
        }!!.geometry.routeId
    }

    private fun polyLength(p: List<LatLon>): Double {
        var d = 0.0
        for (i in 1 until p.size) d += Geo.haversine(p[i - 1], p[i])
        return d
    }

    // --------------------------------------------------------------------- tests

    @Test
    fun `three shapes are learned as three routes`() {
        val b = learnedBundle()
        assertEquals(3, b.routes.size, b.routes.map { "${it.geometry.name} ${it.geometry.lengthM}" }.toString())
    }

    @Test
    fun `the matcher commits to the route actually being run`() {
        val b = learnedBundle()
        val expected = routeIdOf(b, "long")
        val today = Synthetic.record("today", Synthetic.workRoute, day(50), seed = 991)
        val states = replay(today, b)
        val final = states.last()
        assertEquals(MatchPhase.COMMITTED, final.phase, "final=$final")
        assertEquals(expected, final.committedRouteId)
    }

    @Test
    fun `commitment does not happen inside the shared start segment`() {
        val b = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(51), seed = 992)
        val states = replay(today, b)
        // The long route and the shortcut coincide for the first 2 km. Nothing may
        // be committed to while both are still plausible (US-4).
        val committedEarly = states.filter { it.travelledM < 1900.0 && it.phase == MatchPhase.COMMITTED }
        assertTrue(committedEarly.isEmpty(), "committed after only ${committedEarly.firstOrNull()?.travelledM} m")
        assertTrue(states.any { it.phase == MatchPhase.COMMITTED }, "never committed at all")
    }

    @Test
    fun `the shortcut is recognised as the shortcut`() {
        val b = learnedBundle()
        val expected = routeIdOf(b, "short")
        val today = Synthetic.record("today", Synthetic.shortcut, day(52), seed = 993)
        val final = replay(today, b).last()
        assertEquals(MatchPhase.COMMITTED, final.phase)
        assertEquals(expected, final.committedRouteId)
    }

    @Test
    fun `the reverse direction is not mistaken for the forward one`() {
        val b = learnedBundle()
        val expected = routeIdOf(b, "rev")
        val today = Synthetic.record("today", Synthetic.reverseRoute, day(53), seed = 994)
        val final = replay(today, b).last()
        assertEquals(MatchPhase.COMMITTED, final.phase)
        assertEquals(expected, final.committedRouteId, "committed to the wrong direction")
    }

    @Test
    fun `a novel path never commits`() {
        val b = learnedBundle()
        val today = Synthetic.record("today", Synthetic.novelRoute, day(54), seed = 995)
        val states = replay(today, b)
        assertTrue(states.none { it.phase == MatchPhase.COMMITTED }, "committed on a novel path")
        assertEquals(MatchPhase.UNKNOWN, states.last().phase)
    }

    @Test
    fun `an empty bundle reports unknown and never throws`() {
        val today = Synthetic.record("today", Synthetic.workRoute, day(55), seed = 996)
        val final = replay(today, RouteBundle.EMPTY).last()
        assertEquals(MatchPhase.UNKNOWN, final.phase)
        assertNull(final.remainingM)
    }

    @Test
    fun `ahead-behind is available before commitment`() {
        val b = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(56), seed = 997)
        val states = replay(today, b)
        val early = states.filter { it.travelledM in 300.0..1500.0 && it.phase == MatchPhase.SEARCHING }
        assertTrue(early.isNotEmpty())
        assertTrue(early.any { it.deltaVsMedianS != null }, "no pooled ahead/behind while searching (US-5)")
    }

    @Test
    fun `a faster run reads as ahead of the median`() {
        val b = learnedBundle()
        val fast = Synthetic.record("fast", Synthetic.workRoute, day(57), speedMps = 4.0, seed = 998)
        val final = replay(fast, b).last()
        assertNotNull(final.deltaVsMedianS)
        assertTrue(final.deltaVsMedianS!! < -60.0, "delta=${final.deltaVsMedianS}")
    }

    @Test
    fun `remaining distance and eta appear once there is a route to measure against`() {
        val b = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(58), seed = 999)
        val states = replay(today, b)
        // Once only committed states carried these. They are now also offered for
        // an unambiguous leader, flagged `provisional` so the display can mark
        // them as the guess they are — without that, a route family sharing a
        // long prefix leaves the watch with no remaining distance and no arrival
        // time for most of a workout (A.4b). What has not changed is that they
        // never appear without *some* single route behind them.
        assertTrue(
            states.filter { it.phase != MatchPhase.COMMITTED }
                .all { (it.remainingM == null && it.etaSecondsFromNow == null) || it.provisional },
            "remaining distance offered with no route behind it",
        )
        assertTrue(
            states.none { it.provisional && it.contenderCount > 1 },
            "provisional while two routes were still indistinguishable",
        )
        val committed = states.filter { it.phase == MatchPhase.COMMITTED }
        assertTrue(committed.isNotEmpty())
        assertTrue(committed.last().remainingM != null)
        // Remaining distance shrinks as the route is consumed.
        val first = committed.first().remainingM!!
        val last = committed.last().remainingM!!
        assertTrue(last < first, "remaining did not shrink: $first -> $last")
        assertTrue(last < 60.0, "did not reach the end: $last m left")
    }

    @Test
    fun `eta is plausible at the halfway point`() {
        val b = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(59), speedMps = 3.0, seed = 1000)
        val states = replay(today, b)
        val totalS = today.activeDurationMs / 1000.0
        val mid = states.filter { it.phase == MatchPhase.COMMITTED && it.etaSecondsFromNow != null }
            .minByOrNull { abs(it.remainingM!! - polyLength(Synthetic.workRoute) / 2) }
        assertNotNull(mid)
        val predictedFinish = mid.tOffsetMs / 1000.0 + mid.etaSecondsFromNow!!
        assertTrue(abs(predictedFinish - totalS) < totalS * 0.2, "predicted $predictedFinish vs actual $totalS")
    }

    @Test
    fun `deviation fires once per departure and re-arms`() {
        val b = learnedBundle()
        // Run the work route but detour 150 m sideways in the middle, then return.
        val detour = Synthetic.path(
            listOf(
                Synthetic.at(0.0, 0.0), Synthetic.at(2000.0, 0.0), Synthetic.at(2000.0, 600.0),
                Synthetic.at(2180.0, 700.0), Synthetic.at(2180.0, 1000.0), Synthetic.at(2000.0, 1100.0),
                Synthetic.at(2000.0, 1500.0), Synthetic.at(3000.0, 1500.0),
            ),
        )
        val today = Synthetic.record("today", detour, day(60), seed = 1001)
        val states = replay(today, b)
        val alerts = states.count { it.deviationAlert }
        assertEquals(1, alerts, "expected exactly one alert, got $alerts")
        assertTrue(states.any { it.offRoute }, "never registered as off route")
        assertTrue(states.last().offRoute.not(), "still off route at the end")
    }

    @Test
    fun `deviation never fires while uncommitted`() {
        val b = learnedBundle()
        val today = Synthetic.record("today", Synthetic.novelRoute, day(61), seed = 1002)
        val states = replay(today, b)
        assertTrue(states.none { it.deviationAlert })
    }

    @Test
    fun `batched processing equals streamed processing`() {
        val b = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(62), seed = 1003)

        val streamed = ArrayList<MatchLogEntry>()
        replay(today, b, streamed)

        val batched = ArrayList<MatchLogEntry>()
        val m = RouteMatcher(b, today.header.exerciseType, today.header.startedAtUtcMs, today.header.tzOffsetMinutes, batched::add)
        today.samples.chunked(37).forEach { m.updateAll(it) }

        assertEquals(streamed, batched, "ambient batching changed the decisions (§5.6)")
    }

    @Test
    fun `replay is reproducible`() {
        val b = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(63), seed = 1004)
        val a = ArrayList<MatchLogEntry>().also { replay(today, b, it) }
        val c = ArrayList<MatchLogEntry>().also { replay(today, b, it) }
        assertEquals(a, c)
    }

    @Test
    fun `a gps dropout does not derail the match`() {
        val b = learnedBundle()
        val expected = routeIdOf(b, "long")
        val today = Synthetic.record(
            "today", Synthetic.workRoute, day(64), seed = 1005,
            dropoutAfterS = 400.0, dropoutDurationS = 90.0,
        )
        val final = replay(today, b).last()
        assertEquals(MatchPhase.COMMITTED, final.phase, "lost the route across a 90 s dropout")
        assertEquals(expected, final.committedRouteId)
    }

    @Test
    fun `a long pause does not erode the match`() {
        val b = learnedBundle()
        val expected = routeIdOf(b, "long")
        val today = Synthetic.record(
            "today", Synthetic.workRoute, day(65), seed = 1006,
            pauseAfterS = 500.0, pauseDurationS = 300.0,
        )
        val states = replay(today, b)
        assertEquals(MatchPhase.COMMITTED, states.last().phase)
        assertEquals(expected, states.last().committedRouteId)
    }

    @Test
    fun `a self-intersecting loop stays unambiguous`() {
        val sessions = (0 until 3).map {
            Synthetic.record("eight$it", Synthetic.figureEight, day(it), seed = it + 1)
        }
        val bundle = BundleBuilder.bundleFrom(sessions.mapNotNull { Track.of(it) }, sessions.associate { it.id to it.samples })
        assertEquals(1, bundle.routes.size)
        val today = Synthetic.record("today", Synthetic.figureEight, day(70), seed = 1007)
        val states = replay(today, bundle)
        // Position along the route must advance monotonically despite the crossing.
        val positions = states.mapNotNull { s -> s.candidates.firstOrNull()?.positionM }
        var regressions = 0
        for (i in 1 until positions.size) if (positions[i] < positions[i - 1] - 30.0) regressions++
        assertTrue(regressions < 5, "$regressions backward jumps through the crossing")
        assertEquals(MatchPhase.COMMITTED, states.last().phase)
    }

    @Test
    fun `a biking session ignores running routes`() {
        val b = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(71), seed = 1008, type = ExerciseType.BIKING)
        val states = replay(today, b)
        assertTrue(states.none { it.phase == MatchPhase.COMMITTED })
    }

    @Test
    fun `matcher throughput stays inside the budget`() {
        // NFR-3: < 5 ms per sample for <= 50 candidate routes.
        val sessions = buildList {
            for (r in 0 until 10) {
                val shifted = Synthetic.workRoute.map { Synthetic.at(0.0, 0.0).let { _ -> it } }
                repeat(3) { k ->
                    add(
                        Synthetic.record(
                            "r${r}_$k",
                            Synthetic.path(
                                listOf(
                                    Synthetic.at(0.0, r * 40.0),
                                    Synthetic.at(2000.0, r * 40.0),
                                    Synthetic.at(2000.0, 1500.0 + r * 40.0),
                                    Synthetic.at(3000.0, 1500.0 + r * 40.0),
                                ),
                            ),
                            day(r * 3 + k), seed = r * 10 + k,
                        ),
                    )
                    shifted.size // keep the compiler honest about the unused local
                }
            }
        }
        val bundle = BundleBuilder.bundleFrom(sessions.mapNotNull { Track.of(it) }, sessions.associate { it.id to it.samples })
        assertTrue(bundle.routes.size >= 5, "expected several routes, got ${bundle.routes.size}")

        val today = Synthetic.record("today", Synthetic.workRoute, day(99), seed = 1009)
        val m = RouteMatcher(bundle, ExerciseType.RUNNING, today.header.startedAtUtcMs, 120)
        // Warm up, then measure.
        today.samples.take(50).forEach { m.update(it) }
        val m2 = RouteMatcher(bundle, ExerciseType.RUNNING, today.header.startedAtUtcMs, 120)
        val t0 = System.nanoTime()
        today.samples.forEach { m2.update(it) }
        val perSampleMs = (System.nanoTime() - t0) / 1e6 / today.samples.size
        assertTrue(perSampleMs < 5.0, "matcher took %.2f ms/sample with ${bundle.routes.size} routes".format(perSampleMs))
    }

    @Test
    fun `prior context key is stable and sane`() {
        // Monday 07:30 UTC with a +120 min offset is 09:30 local: weekday, morning.
        val k = RouteMatcher.contextKeyOf(ExerciseType.RUNNING, Synthetic.MONDAY_0730_UTC, 120)
        assertEquals("RUNNING|WEEKDAY|MORNING", k)
        val sat = RouteMatcher.contextKeyOf(ExerciseType.RUNNING, Synthetic.MONDAY_0730_UTC + 5 * Synthetic.DAY_MS, 120)
        assertEquals("RUNNING|WEEKEND|MORNING", sat)
    }
}
