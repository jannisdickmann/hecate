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
    fun `inside the shared start segment one route is named and flagged as assumed`() {
        val b = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(51), seed = 992)
        val states = replay(today, b)
        // The long route and the shortcut coincide for the first 2 km. One of them
        // is named all the same — their ahead/behind is the same there — but as
        // an assumption, not as a finding (US-4), and not before it has been
        // followed for COMMIT_MIN_M.
        val committedEarly = states.filter { it.travelledM < 1900.0 && it.phase == MatchPhase.COMMITTED }
        assertTrue(committedEarly.isNotEmpty(), "nothing named on the shared segment")
        assertTrue(committedEarly.all { it.committedByPriority }, "a tie claimed as evidence")
        assertTrue(committedEarly.first().travelledM >= Tuning.COMMIT_MIN_M, "committed before COMMIT_MIN_M")
        // Named once, and kept: on shared ground the weights are noise.
        assertEquals(1, committedEarly.map { it.committedRouteId }.distinct().size, "the name changed on a tie")
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
    fun `a lollipop holds through a dropout on its stem`() {
        // "Abendrunde": learned from walks on the outbound pavement, then walked
        // today on the far one — so after a dropout on the stem the way home is
        // the nearer geometry, which is what put the real walk on it.
        val walks = (0 until 3).map {
            Synthetic.record("walk$it", Synthetic.lollipop(), day(it), seed = it + 61, speedMps = 1.4, type = ExerciseType.WALKING)
        }
        val bundle = BundleBuilder.bundleFrom(walks.mapNotNull { Track.of(it) }, walks.associate { it.id to it.samples })
        assertEquals(1, bundle.routes.size)
        val today = Synthetic.record(
            "today", Synthetic.lollipop(outboundEastM = 5.0), day(72), seed = 1009, speedMps = 1.4,
            jitterM = 1.0, type = ExerciseType.WALKING, dropoutAfterS = 300.0, dropoutDurationS = 25.0,
        )
        val states = replay(today, bundle)

        val afterDropout = states.filter { it.tOffsetMs in 330_000L..600_000L }
        assertTrue(afterDropout.isNotEmpty())
        assertTrue(
            afterDropout.all { (it.candidates.firstOrNull()?.positionM ?: 0.0) < 1200.0 },
            "re-localised onto the way home after the dropout",
        )
        val committedShare = states.count { it.phase == MatchPhase.COMMITTED }.toDouble() / states.size
        assertTrue(committedShare > 0.8, "committed for only ${"%.0f".format(committedShare * 100)} % of the walk")
        assertEquals(MatchPhase.COMMITTED, states.last().phase)
    }

    @Test
    fun `positions that arrive late do not move the user backwards`() {
        // Health Services sometimes hands over a few seconds of positions after
        // newer ones. Fed as they arrive, each of those used to pull the user
        // back along the route.
        val b = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(73), seed = 1010)
        val inOrder = replay(today, b)

        // Every 60 samples, the next 5 arrive after the 5 that follow them.
        val arrival = today.samples.chunked(10).flatMapIndexed { i, chunk ->
            if (i % 6 == 3 && chunk.size == 10) chunk.drop(5) + chunk.take(5) else chunk
        }
        val m = RouteMatcher(b, today.header.exerciseType, today.header.startedAtUtcMs, today.header.tzOffsetMinutes)
        val late = arrival.map { m.update(it) }

        assertEquals(inOrder.last().phase, late.last().phase)
        val ratio = late.last().travelledM / inOrder.last().travelledM
        assertTrue(ratio in 0.97..1.01, "travelled ${"%.2f".format(ratio)} x the in-order distance")
        val committedAt = late.indexOfFirst { it.phase == MatchPhase.COMMITTED }
        assertTrue(committedAt >= 0)
        assertTrue(
            late.drop(committedAt).all { it.phase == MatchPhase.COMMITTED },
            "late positions demoted the route",
        )
    }

    @Test
    fun `a delivery flagged paused while the user kept moving is still followed`() {
        // The walk of 8 Oct: the batch that ends at an auto-pause carries the
        // pause state on half a minute of positions taken while walking. The
        // watch's own active time at each of them kept running.
        val b = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(76), seed = 1013)
        val inOrder = replay(today, b)
        val flagged = today.copy(samples = today.samples.mapIndexed { i, s -> s.copy(paused = i in 300..330) })
        val states = replay(flagged, b)

        val ratio = states.last().travelledM / inOrder.last().travelledM
        assertTrue(ratio in 0.99..1.01, "travelled ${"%.2f".format(ratio)} x the unflagged distance")
        val deltas = states.mapNotNull { it.deltaVsMedianS }
        val jump = deltas.zipWithNext { x, y -> abs(y - x) }.maxOrNull() ?: 0.0
        assertTrue(jump < 10.0, "ahead/behind jumped by $jump s across the flagged stretch")
    }

    @Test
    fun `a clock that ran faster than time across a hole counts none of it`() {
        // A recording from before the watch asked for the active time per
        // position: across a minute's auto-pause its clock read 381 s on one
        // side and 675 s on the other. That is not a measurement of anything.
        val b = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(77), seed = 1014)
        val holeMs = 57_000L
        val warped = today.copy(
            samples = today.samples.mapIndexed { i, s ->
                if (i < 400) s else s.copy(tOffsetMs = s.tOffsetMs + holeMs, activeMs = s.activeMs + 300_000L)
            },
        )
        val states = replay(warped, b)
        val before = states[399].deltaVsMedianS!!
        val after = states[400].deltaVsMedianS!!
        assertTrue(abs(after - before) < 10.0, "a pause read as ${"%.0f".format(after - before)} s of riding")
    }

    @Test
    fun `starting a recording part-way along the route is not a head start`() {
        val b = learnedBundle()
        // Learned at 3 m/s; today at the same pace, but the first 300 m — about
        // 100 s of the route's usual time — were never recorded.
        val full = Synthetic.record("today", Synthetic.workRoute, day(74), seed = 1011)
        val joinedLate = full.copy(
            samples = full.samples.filter { it.cumDistanceM == null || it.cumDistanceM!! >= 300.0 }
                .let { rest -> rest.map { it.copy(tOffsetMs = it.tOffsetMs - rest.first().tOffsetMs, activeMs = it.activeMs - rest.first().activeMs) } },
        )
        val states = replay(joinedLate, b)
        val early = states.filter { it.deltaVsMedianS != null && it.travelledM in 400.0..800.0 }
        assertTrue(early.isNotEmpty())
        assertTrue(
            early.all { abs(it.deltaVsMedianS!!) < 30.0 },
            "a steady pace read as ${"%+.0f".format(early.first().deltaVsMedianS)} s",
        )
    }

    @Test
    fun `a commitment holds through a long stretch shared with another route`() {
        // The commute: two ways out of town that join for three kilometres in the
        // middle and part again at the end. Committed before the shared stretch,
        // the matcher used to let go half way along it, as the old evidence
        // decayed and the two weights drifted back to a half each.
        val a = Synthetic.path(listOf(Synthetic.at(0.0, 0.0), Synthetic.at(1000.0, 0.0), Synthetic.at(1000.0, 500.0), Synthetic.at(4000.0, 500.0), Synthetic.at(4000.0, 1500.0)))
        val b = Synthetic.path(listOf(Synthetic.at(0.0, 0.0), Synthetic.at(0.0, 500.0), Synthetic.at(4000.0, 500.0), Synthetic.at(4000.0, -500.0)))
        val sessions = buildList {
            repeat(3) { add(Synthetic.record("a$it", a, day(it), seed = 70 + it)) }
            repeat(3) { add(Synthetic.record("b$it", b, day(10 + it), seed = 80 + it)) }
        }
        val bundle = BundleBuilder.bundleFrom(sessions.mapNotNull { Track.of(it) }, sessions.associate { it.id to it.samples })
        assertEquals(2, bundle.routes.size)

        val states = replay(Synthetic.record("today", a, day(75), seed = 1012), bundle)
        val firstCommit = states.indexOfFirst { it.phase == MatchPhase.COMMITTED }
        assertTrue(firstCommit >= 0 && states[firstCommit].travelledM < 1500.0, "never committed before the shared stretch")
        val routeA = states[firstCommit].committedRouteId
        val afterwards = states.drop(firstCommit)
        assertTrue(
            afterwards.all { it.phase == MatchPhase.COMMITTED && it.committedRouteId == routeA },
            "let go at ${afterwards.firstOrNull { it.phase != MatchPhase.COMMITTED }?.travelledM?.toInt()} m",
        )
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
