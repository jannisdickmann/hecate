package de.hecate.core

import de.hecate.core.cluster.BundleBuilder
import de.hecate.core.geo.Track
import de.hecate.core.matcher.RouteMatcher
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.MatchPhase
import de.hecate.core.model.MatchState
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.RouteBundle
import de.hecate.core.model.Sample
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the matcher is willing to say before it has committed, and what it stops
 * saying when the ground goes out from under it.
 *
 * The old display had one answer for everything up to commitment — "Suche…" —
 * which was a spinner over a belief that usually had a clear favourite, and
 * carried no remaining distance and no arrival time for most of a workout on a
 * route family that shares a prefix (A.4b). These tests are that favourite, and
 * the honesty conditions attached to it.
 */
class LiveBeliefTest {

    private fun learnedBundle(): RouteBundle {
        val sessions = buildList {
            repeat(3) { add(Synthetic.record("long$it", Synthetic.workRoute, day(it), seed = it + 1)) }
            repeat(3) { add(Synthetic.record("short$it", Synthetic.shortcut, day(10 + it), seed = 20 + it)) }
        }
        val tracks = sessions.mapNotNull { Track.of(it) }
        return BundleBuilder.bundleFrom(tracks, sessions.associate { it.id to it.samples })
    }

    private fun day(n: Int) = Synthetic.MONDAY_0730_UTC + n * Synthetic.DAY_MS

    private fun matcherFor(session: RecordedSession, bundle: RouteBundle) = RouteMatcher(
        bundle,
        session.header.exerciseType,
        session.header.startedAtUtcMs,
        session.header.tzOffsetMinutes,
    )

    private fun replay(session: RecordedSession, bundle: RouteBundle): List<MatchState> {
        val m = matcherFor(session, bundle)
        return session.samples.map { m.update(it) }
    }

    @Test
    fun `a leader is reported from the start, long before anything is committed`() {
        val bundle = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(50), seed = 991)
        val states = replay(today, bundle)

        val beforeCommitment = states.takeWhile { it.phase != MatchPhase.COMMITTED }
        assertTrue(beforeCommitment.size > 30, "nothing to test if it commits immediately")
        assertTrue(
            beforeCommitment.drop(5).all { it.leaderRouteId != null },
            "the matcher carried a favourite the display was never told about",
        )
    }

    @Test
    fun `a commitment brings progress, an arrival and a bar scale with it`() {
        val bundle = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(51), seed = 992)
        val committed = replay(today, bundle).last { it.phase == MatchPhase.COMMITTED }

        val progress = assertNotNull(committed.progressFraction, "no progress fraction to draw a bar from")
        assertTrue(progress in 0.0..1.0, "progress out of range: $progress")
        assertTrue(progress > 0.5, "near the end of the route, progress should be well advanced: $progress")
        assertNotNull(committed.remainingM)
        val scale = assertNotNull(committed.deltaScaleS, "no full-scale deflection for the ahead/behind bar")
        assertTrue(scale >= Tuning.DELTA_SCALE_MIN_S)
    }

    @Test
    fun `nothing route-relative is offered while two routes share the ground`() {
        val bundle = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(52), seed = 993)
        // The long route and the shortcut coincide for the first two kilometres.
        val early = replay(today, bundle).filter { it.travelledM in 300.0..1800.0 }
        assertTrue(early.isNotEmpty())
        assertTrue(
            early.none { it.provisional },
            "a provisional route inside the shared segment is a coin toss with an ETA attached",
        )
    }

    @Test
    fun `an empty bundle says so rather than calling the route unknown`() {
        val today = Synthetic.record("today", Synthetic.workRoute, day(53), seed = 994)
        val empty = RouteBundle(version = 0, builtAtUtcMs = 0, routes = emptyList())
        val final = replay(today, empty).last()
        assertEquals(MatchPhase.UNKNOWN, final.phase)
        assertTrue(final.noRoutesKnown, "no routes at all is not the same claim as off every route")
        assertNull(final.deltaVsMedianS)
    }

    @Test
    fun `a run of unusable samples freezes the comparison instead of making the user look slower`() {
        val bundle = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(54), seed = 995)
        val m = matcherFor(today, bundle)

        val cut = today.samples.size / 2
        for (s in today.samples.take(cut)) m.update(s)

        // The GNSS does not stop delivering; it starts delivering rubbish, which
        // is the failure mode that used to be invisible. Active time keeps
        // running while the projected position stands still.
        val degraded = today.samples.drop(cut).take(200).mapIndexed { i, s ->
            m.update(
                s.copy(
                    accuracyM = Tuning.MAX_USABLE_ACCURACY_M + 50,
                    activeMs = s.activeMs + i * 1_000L,
                ),
            )
        }

        // There is a grace period first — a couple of rejected fixes is not an
        // outage — and the comparison is live throughout it. What must not happen
        // is that it keeps moving afterwards.
        val stale = degraded.filter { it.positionStaleMs > Tuning.STALE_POSITION_MS }
        assertTrue(stale.size > 20, "the position never went stale: ${degraded.last().positionStaleMs} ms")
        val frozen = assertNotNull(stale.first().deltaVsMedianS, "no comparison to freeze")
        assertTrue(
            stale.all { abs(assertNotNull(it.deltaVsMedianS) - frozen) < 1e-9 },
            "the delta drifted while the position stood still — the outage was charged to the runner",
        )
        assertTrue(stale.all { it.etaSecondsFromNow == null }, "no arrival time off a standing position")
    }

    @Test
    fun `a pause is not a stale position`() {
        val bundle = learnedBundle()
        val today = Synthetic.record("today", Synthetic.workRoute, day(55), seed = 996)
        val m = matcherFor(today, bundle)

        var last: MatchState? = null
        val cut = today.samples.size / 2
        for (s in today.samples.take(cut)) last = m.update(s)
        assertNotNull(last)

        var paused: MatchState? = null
        for ((i, s) in today.samples.drop(cut).take(120).withIndex()) {
            paused = m.update(s.copy(paused = true, tOffsetMs = s.tOffsetMs + i * 1_000L))
        }
        assertTrue(
            assertNotNull(paused).positionStaleMs <= Tuning.STALE_POSITION_MS,
            "standing at a level crossing is not a GNSS outage",
        )
    }

    /** Sanity: the fixture's samples really are usable, so the test above is
     *  measuring the accuracy rejection and not a broken fixture. */
    @Test
    fun `the fixture's samples are usable to begin with`() {
        val today = Synthetic.record("today", Synthetic.workRoute, day(56), seed = 997)
        val unusable = today.samples.count { !it.usable }
        assertEquals(0, unusable, "fixture already contains unusable samples")
        assertTrue(today.samples.first() is Sample)
    }
}
