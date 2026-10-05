package de.hecate.core

import de.hecate.core.display.HealthKind
import de.hecate.core.display.HealthSeverity
import de.hecate.core.display.RecordingHealthInput
import de.hecate.core.display.RouteBeliefKind
import de.hecate.core.display.recordingHealth
import de.hecate.core.display.routeBelief
import de.hecate.core.model.Candidate
import de.hecate.core.model.MatchPhase
import de.hecate.core.model.MatchState
import de.hecate.core.model.ExerciseType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two display decisions, which are decisions and therefore live in `:core`
 * where they can be tested on a laptop rather than on a wrist in the rain.
 *
 * What is being pinned down here is not formatting. It is the rule that the
 * route line never reports a fault and the health line always reports a real
 * one — the confusion that made a working matcher look broken and a broken
 * receiver look like a slow runner.
 */
class DisplayTest {

    private val names = mapOf(1L to "Weg zur Arbeit", 2L to "Waldrunde", 3L to "Feierabendrunde")
    private fun nameOf(id: Long): String? = names[id]

    private fun state(
        phase: MatchPhase = MatchPhase.SEARCHING,
        committed: Long? = null,
        byPriority: Boolean = false,
        candidates: List<Candidate> = emptyList(),
        contenders: Int = 0,
        leader: Long? = candidates.firstOrNull()?.routeId,
        leaderWeight: Double = candidates.firstOrNull()?.weight ?: 0.0,
        noRoutes: Boolean = false,
        disabled: Boolean = false,
    ) = MatchState(
        tOffsetMs = 0,
        phase = phase,
        committedRouteId = committed,
        committedByPriority = byPriority,
        candidates = candidates,
        leaderRouteId = leader,
        leaderWeight = leaderWeight,
        contenderCount = contenders,
        noRoutesKnown = noRoutes,
        disabled = disabled,
    )

    private fun cand(id: Long, weight: Double) = Candidate(id, weight, 100.0, 5.0)

    // ------------------------------------------------------------ route belief

    @Test
    fun `a commitment from the track is a finding and a commitment from priority is not`() {
        val evidence = routeBelief(state(MatchPhase.COMMITTED, committed = 1L), false, ::nameOf)
        assertEquals(RouteBeliefKind.COMMITTED, evidence.kind)
        assertEquals("Weg zur Arbeit", evidence.name)
        assertFalse(evidence.isProvisional)

        val assumed = routeBelief(
            state(MatchPhase.COMMITTED, committed = 1L, byPriority = true), false, ::nameOf,
        )
        assertEquals(RouteBeliefKind.ASSUMED, assumed.kind)
        assertTrue(assumed.hasRoute, "an assumed route still carries remaining distance and ETA")
    }

    @Test
    fun `a clear leader is named before it is committed to`() {
        val belief = routeBelief(
            state(candidates = listOf(cand(2L, 0.72), cand(1L, 0.2)), contenders = 1), false, ::nameOf,
        )
        assertEquals(RouteBeliefKind.LEADING, belief.kind)
        assertEquals("Waldrunde", belief.name)
        assertTrue(belief.isProvisional, "a leader is a guess and has to say so")
        assertTrue(belief.hasRoute, "which is still enough for a remaining distance")
    }

    @Test
    fun `a leader that shares the ground with another route is never named alone`() {
        // Two routes running over the same street explain every sample equally
        // well. Naming whichever is momentarily ahead on weight would be a coin
        // toss presented as a finding (US-4).
        val belief = routeBelief(
            state(candidates = listOf(cand(1L, 0.52), cand(2L, 0.45)), contenders = 2), false, ::nameOf,
        )
        assertEquals(RouteBeliefKind.CONTENDERS, belief.kind)
        assertEquals("Weg zur Arbeit", belief.name)
        assertEquals("Waldrunde", belief.alternative)
        assertFalse(belief.hasRoute, "no ETA off a coin toss")
    }

    @Test
    fun `nothing decided yet is comparing, not searching`() {
        val belief = routeBelief(
            state(candidates = listOf(cand(1L, 0.3), cand(2L, 0.25)), contenders = 1), false, ::nameOf,
        )
        assertEquals(RouteBeliefKind.COMPARING, belief.kind)
        assertFalse(belief.hasRoute)
    }

    @Test
    fun `the null hypothesis winning is a new route, and an empty bundle is not the same thing`() {
        assertEquals(RouteBeliefKind.NEW_ROUTE, routeBelief(state(MatchPhase.UNKNOWN), false, ::nameOf).kind)
        assertEquals(
            RouteBeliefKind.NO_ROUTES,
            routeBelief(state(MatchPhase.UNKNOWN, noRoutes = true), false, ::nameOf).kind,
            "no routes of this type at all is a fact about the phone, not about today",
        )
    }

    @Test
    fun `a disabled matcher withdraws the route entirely`() {
        val belief = routeBelief(state(MatchPhase.COMMITTED, committed = 1L), true, ::nameOf)
        assertEquals(RouteBeliefKind.MATCHER_OFF, belief.kind)
        assertFalse(
            belief.hasRoute,
            "the route numbers used to stay on screen, frozen, with an arrival time that kept drifting",
        )
    }

    @Test
    fun `a committed route with no name degrades instead of showing a blank line`() {
        val belief = routeBelief(state(MatchPhase.COMMITTED, committed = 99L), false, ::nameOf)
        assertEquals(RouteBeliefKind.COMPARING, belief.kind)
    }

    // --------------------------------------------------------- recording health

    @Test
    fun `a healthy recording is a state that gets drawn`() {
        val h = recordingHealth(RecordingHealthInput(fixAgeMs = 1_000, sinceLastWriteMs = 1_000))
        assertEquals(HealthKind.RECORDING, h.kind)
        assertEquals(HealthSeverity.OK, h.severity)
    }

    @Test
    fun `nothing reaching the file outranks everything else`() {
        val h = recordingHealth(
            RecordingHealthInput(
                fixAgeMs = 5_000,
                sinceLastWriteMs = Tuning.NO_WRITE_MS + 1,
                offRoute = true,
                matcherDisabled = true,
            ),
        )
        assertEquals(HealthKind.NOT_WRITING, h.kind)
        assertEquals(HealthSeverity.FAILED, h.severity)
    }

    @Test
    fun `a lost fix is reported with its age`() {
        val h = recordingHealth(RecordingHealthInput(fixAgeMs = 80_000, sinceLastWriteMs = 1_000))
        assertEquals(HealthKind.NO_FIX, h.kind)
        assertEquals(HealthSeverity.FAILED, h.severity)
        assertEquals(80_000, h.fixAgeMs, "how long is the whole question")
    }

    @Test
    fun `an inaccurate fix is degraded, not silent`() {
        // The failure that used to show up only as the ahead-behind figure
        // drifting worse: samples rejected for accuracy, the matcher's position
        // frozen, and nothing on screen about it.
        val h = recordingHealth(
            RecordingHealthInput(
                fixAgeMs = 2_000,
                sinceLastWriteMs = 1_000,
                accuracyM = Tuning.MAX_USABLE_ACCURACY_M + 10,
            ),
        )
        assertEquals(HealthKind.POOR_FIX, h.kind)
        assertEquals(HealthSeverity.DEGRADED, h.severity)
    }

    @Test
    fun `a missing location permission explains itself instead of blaming the receiver`() {
        val h = recordingHealth(
            RecordingHealthInput(hasLocationPermission = false, fixAgeMs = null, sinceLastWriteMs = 1_000),
        )
        assertEquals(HealthKind.NO_LOCATION_PERMISSION, h.kind)
    }

    @Test
    fun `warming up neither complains about the fix it does not have yet nor about an empty file`() {
        val h = recordingHealth(
            RecordingHealthInput(preparing = true, fixAgeMs = null, sinceLastWriteMs = Tuning.NO_WRITE_MS + 1),
        )
        assertEquals(HealthKind.PREPARING, h.kind)
    }

    @Test
    fun `a pause is not an empty recording`() {
        val h = recordingHealth(
            RecordingHealthInput(paused = true, autoPaused = true, fixAgeMs = 2_000, sinceLastWriteMs = 600_000),
        )
        assertEquals(HealthKind.AUTO_PAUSED, h.kind)
    }

    @Test
    fun `a long auto-pause is not a lost fix`() {
        // Health Services stops delivering locations while auto-paused, so the
        // fix ages with nothing wrong with the receiver.
        val h = recordingHealth(
            RecordingHealthInput(
                paused = true, autoPaused = true,
                fixAgeMs = Tuning.FIX_LOST_MS * 5, sinceLastWriteMs = 600_000,
            ),
        )
        assertEquals(HealthKind.AUTO_PAUSED, h.kind)
    }

    @Test
    fun `an auto-pause still reports a fix the receiver says is gone`() {
        val h = recordingHealth(
            RecordingHealthInput(
                paused = true, autoPaused = true, gpsSignalLost = true,
                fixAgeMs = Tuning.FIX_LOST_MS + 1, sinceLastWriteMs = 600_000,
            ),
        )
        assertEquals(HealthKind.NO_FIX, h.kind)
    }

    @Test
    fun `a long manual pause is not a lost fix`() {
        val h = recordingHealth(
            RecordingHealthInput(
                paused = true,
                fixAgeMs = Tuning.FIX_LOST_MS * 5, sinceLastWriteMs = 600_000,
            ),
        )
        assertEquals(HealthKind.PAUSED, h.kind)
    }

    @Test
    fun `a manual pause still reports a fix the receiver says is gone`() {
        val h = recordingHealth(
            RecordingHealthInput(
                paused = true, gpsSignalLost = true,
                fixAgeMs = Tuning.FIX_LOST_MS + 1, sinceLastWriteMs = 600_000,
            ),
        )
        assertEquals(HealthKind.NO_FIX, h.kind)
    }

    @Test
    fun `just after resuming, the pause does not count as time without a fix`() {
        val h = recordingHealth(
            RecordingHealthInput(
                fixAgeMs = Tuning.FIX_LOST_MS * 5, sinceResumeMs = 2_000, sinceLastWriteMs = 1_000,
            ),
        )
        assertEquals(HealthKind.RECORDING, h.kind)
        assertEquals(2_000, h.fixAgeMs)
    }

    @Test
    fun `no fix for long after resuming is still reported, counted from the resume`() {
        val h = recordingHealth(
            RecordingHealthInput(
                fixAgeMs = Tuning.FIX_LOST_MS * 5, sinceResumeMs = Tuning.FIX_LOST_MS + 1,
                sinceLastWriteMs = 1_000,
            ),
        )
        assertEquals(HealthKind.NO_FIX, h.kind)
        assertEquals(Tuning.FIX_LOST_MS + 1, h.fixAgeMs)
    }

    @Test
    fun `a fix the receiver lost before the pause is not forgiven by resuming`() {
        val h = recordingHealth(
            RecordingHealthInput(
                fixAgeMs = Tuning.FIX_LOST_MS * 5, sinceResumeMs = 2_000, gpsSignalLost = true,
                sinceLastWriteMs = 1_000,
            ),
        )
        assertEquals(HealthKind.NO_FIX, h.kind)
    }

    @Test
    fun `a message the user has to see outranks the states it usually explains`() {
        val h = recordingHealth(
            RecordingHealthInput(
                preparing = true,
                fixAgeMs = null,
                sinceLastWriteMs = 1_000,
                message = "GPS-Vorbereitung fehlgeschlagen",
            ),
        )
        assertEquals(HealthKind.MESSAGE, h.kind)
        assertEquals("GPS-Vorbereitung fehlgeschlagen", h.message)
    }

    @Test
    fun `a dead matcher ranks last because every metric except the route survives it`() {
        val h = recordingHealth(
            RecordingHealthInput(fixAgeMs = 2_000, sinceLastWriteMs = 1_000, matcherDisabled = true),
        )
        assertEquals(HealthKind.MATCHER_OFF, h.kind)
        assertEquals(HealthSeverity.DEGRADED, h.severity)
    }

    // ------------------------------------------------------------- bar scaling

    @Test
    fun `the bar has one scale per sport, whatever the route`() {
        val committed = ExerciseType.entries.associateWith { Tuning.deltaFullScaleS(it) }
        assertTrue(committed.values.all { it in 60.0..900.0 }, "$committed")
        // A minute matters more on a run than on a ride.
        assertTrue(Tuning.deltaFullScaleS(ExerciseType.RUNNING) < Tuning.deltaFullScaleS(ExerciseType.BIKING))
    }
}
