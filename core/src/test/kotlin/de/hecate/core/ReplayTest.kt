package de.hecate.core

import de.hecate.core.cluster.BundleBuilder
import de.hecate.core.geo.Track
import de.hecate.core.io.BundleCodec
import de.hecate.core.io.SessionCodec
import de.hecate.core.matcher.RouteMatcher
import de.hecate.core.model.MatchEvent
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.MatchPhase
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.RouteBundle
import de.hecate.core.replay.ReplayMain
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * US-10. The point of the replay harness is that a decision made on the wrist can
 * be reproduced exactly on a laptop; these tests pin that property down.
 */
class ReplayTest {

    private fun day(n: Int) = Synthetic.MONDAY_0730_UTC + n * Synthetic.DAY_MS

    private fun fixture(): Pair<RouteBundle, RecordedSession> {
        val sessions = buildList {
            repeat(3) { add(Synthetic.record("long$it", Synthetic.workRoute, day(it), seed = it + 1)) }
            repeat(3) { add(Synthetic.record("short$it", Synthetic.shortcut, day(10 + it), seed = 20 + it)) }
        }
        val bundle = BundleBuilder.bundleFrom(
            sessions.mapNotNull { Track.of(it) },
            sessions.associate { it.id to it.samples },
        )
        return bundle to Synthetic.record("today", Synthetic.workRoute, day(40), seed = 4242)
    }

    @Test
    fun `replaying a session twice gives byte-identical logs`() {
        val (bundle, session) = fixture()
        val a = ArrayList<MatchLogEntry>()
        val b = ArrayList<MatchLogEntry>()
        ReplayMain.run(session, bundle, a)
        ReplayMain.run(session, bundle, b)
        assertEquals(BundleCodec.encodeMatchLog(a), BundleCodec.encodeMatchLog(b))
        assertTrue(a.isNotEmpty())
    }

    @Test
    fun `a log survives a round trip through its own serialization`() {
        val (bundle, session) = fixture()
        val log = ArrayList<MatchLogEntry>()
        ReplayMain.run(session, bundle, log)
        val text = BundleCodec.encodeMatchLog(log)
        assertEquals(log, BundleCodec.decodeMatchLog(text))
    }

    @Test
    fun `a watch-stored log replays identically off the watch`() {
        // Simulates what the watch does: run the matcher while recording, write
        // the log into the session file, then replay the file offline. Divergence
        // between the two is, by definition, a bug (US-10).
        val (bundle, session) = fixture()
        val onWrist = ArrayList<MatchLogEntry>()
        val matcher = RouteMatcher(
            bundle, session.header.exerciseType,
            session.header.startedAtUtcMs, session.header.tzOffsetMinutes, onWrist::add,
        )
        session.samples.forEach { matcher.update(it) }

        val stored = session.copy(matchLog = onWrist)
        val text = SessionCodec.encodeSession(stored)
        val reread = SessionCodec.decodeSession(text.lineSequence())!!

        val replayed = ArrayList<MatchLogEntry>()
        ReplayMain.run(reread, bundle, replayed)
        assertEquals(reread.matchLog, replayed)
    }

    @Test
    fun `the log records the decisions a human would want to see`() {
        val (bundle, session) = fixture()
        val log = ArrayList<MatchLogEntry>()
        ReplayMain.run(session, bundle, log)
        assertTrue(log.any { it.event == MatchEvent.COMMIT }, "no commit in the log")
        assertTrue(log.any { it.event == MatchEvent.DROP }, "no candidate ever dropped")
        val commit = log.first { it.event == MatchEvent.COMMIT }
        assertEquals(MatchPhase.COMMITTED, commit.phase)
        assertTrue(commit.travelledM >= Tuning.COMMIT_MIN_M)
        assertTrue(commit.weights.isNotEmpty())
    }
}
