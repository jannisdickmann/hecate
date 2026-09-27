package de.hecate.core

import de.hecate.core.geo.Geo
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.HeartRate
import de.hecate.core.model.MatchEvent
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.MatchPhase
import de.hecate.core.model.Reading
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.Sample
import de.hecate.core.model.SessionEndReason
import de.hecate.core.model.SessionEvent
import de.hecate.core.model.SessionEventType
import de.hecate.core.model.SessionFooter
import de.hecate.core.model.SessionHeader
import de.hecate.core.recorder.AutoStop
import de.hecate.core.recorder.AutoStopReason
import de.hecate.core.recorder.AutoStopVerdict
import de.hecate.core.recorder.AutoStopWatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The three ways an activity ends without anybody saying so, and the one way it
 * must not be ended.
 *
 * Every case here is written as the recording the watch would have written:
 * one sample a second, a cumulative distance that only grows, and accuracies
 * that decide by themselves whether a fix counts. The thresholds are quarters of
 * an hour, which is why this is a unit test and not something anybody was ever
 * going to find on a wrist.
 */
class AutoStopTest {

    // ------------------------------------------------------------- the recording

    /** Builds a session the way the watch writes one: metres east of an origin,
     *  a second at a time. */
    private class Tape {
        val samples = ArrayList<Sample>()
        var tMs = 0L
            private set
        private var east = 0.0
        private var cum = 0.0
        private var wobble = 0

        /** [seconds] of travel at [speedMps], one sample a second. */
        fun move(seconds: Int, speedMps: Double, accuracyM: Double = 8.0) = repeat(seconds) {
            east += speedMps
            cum += speedMps
            add(east, accuracyM)
        }

        /** Standing still, with the few metres of wander a real receiver has. */
        fun still(seconds: Int, accuracyM: Double = 8.0) = repeat(seconds) {
            wobble = (wobble + 1) % 4
            add(east + (wobble - 1.5) * 4.0, accuracyM)
        }

        /** Fixes that arrive and are far too poor to use. */
        fun blind(seconds: Int) = still(seconds, accuracyM = 200.0)

        /** No samples at all: an auto-pause, or a receiver that has stopped
         *  delivering. [travelledM] is ground covered while it lasts, which is
         *  how a car in a valley looks from the outside. */
        fun silence(seconds: Int, travelledM: Double = 0.0) {
            tMs += seconds * 1000L
            east += travelledM
            cum += travelledM
        }

        /** One position, [jumpM] away, back where it was on the next sample. */
        fun jump(jumpM: Double) {
            add(east + jumpM, 8.0)
        }

        private fun add(atEast: Double, accuracyM: Double) {
            val p = Geo.fromLocal(Synthetic.ORIGIN, atEast, 0.0)
            samples.add(
                Sample(
                    tOffsetMs = tMs,
                    activeMs = tMs,
                    lat = p.lat,
                    lon = p.lon,
                    hrBpm = 130,
                    accuracyM = accuracyM,
                    cumDistanceM = cum,
                ),
            )
            tMs += 1000
        }
    }

    /** Feeds a tape to the rules and returns the first verdict, if any. */
    private fun judge(
        tape: Tape,
        type: ExerciseType = ExerciseType.RUNNING,
        pauses: List<Pair<Long, Boolean>> = emptyList(),
        tickTo: Long? = null,
    ): AutoStopVerdict? {
        val watch = AutoStopWatch(type)
        var next = 0
        val ordered = pauses.sortedBy { it.first }
        for (sample in tape.samples) {
            while (next < ordered.size && ordered[next].first <= sample.tOffsetMs) {
                watch.manualPause(ordered[next].second, ordered[next].first)
                next++
            }
            watch.sample(sample)?.let { return it }
        }
        // Time passing with nothing to show for it — the watchdog's half minute.
        if (tickTo != null) {
            var t = tape.tMs
            while (t <= tickTo) {
                watch.tick(t)?.let { return it }
                t += Tuning.AUTO_STOP_TICK_MS
            }
        }
        return null
    }

    private fun minutes(n: Int) = n * 60

    /** The stationary patience in whole minutes, so that the cases below say
     *  "past it" and "within it" rather than a number that moves with it. */
    private val idleMinutes = (Tuning.AUTO_STOP_IDLE_MS / 60_000L).toInt()

    // --------------------------------------------------------------- not a stop

    @Test
    fun `a workout that keeps moving is never stopped`() {
        val tape = Tape().apply { move(minutes(50), 3.0) }
        assertNull(judge(tape), "50 minutes of running is not a forgotten recording")
    }

    @Test
    fun `a break shorter than the threshold is not a stop`() {
        // Half an hour at a summit, with the descent still to come. The real
        // history has exactly this — and a fifteen-minute threshold ended it
        // there, with two hours of walking left unrecorded.
        val tape = Tape().apply {
            move(minutes(20), 1.4)
            still(minutes(30))
            move(minutes(20), 1.4)
        }
        assertNull(judge(tape, ExerciseType.HIKING))
    }

    @Test
    fun `an auto-paused break shorter than the threshold is not a stop`() {
        // The same rest with Health Services auto-pausing through it, so that
        // nothing arrives at all. The blind timer is the shorter of the two, and
        // it must not end a break it has no evidence about.
        val tape = Tape().apply {
            move(minutes(20), 1.4)
            silence(minutes(30))
        }
        assertNull(judge(tape, ExerciseType.HIKING, tickTo = tape.tMs))
    }

    @Test
    fun `nothing is stopped before the activity has moved`() {
        // Started early, waiting for someone to lace up. Ending here would be the
        // worst of the failures: the run that follows is not recorded at all.
        val tape = Tape().apply { still(minutes(40)) }
        assertNull(judge(tape, tickTo = tape.tMs + minutes(20) * 1000L))
    }

    @Test
    fun `a session that never had a usable fix is left alone`() {
        // A stopwatch with a heart rate monitor. The watch already says so on the
        // live page; throwing the recording away because of it would be a bug.
        val tape = Tape().apply { blind(minutes(45)) }
        assertNull(judge(tape, tickTo = tape.tMs + minutes(30) * 1000L))
    }

    // --------------------------------------------------------------- Stillstand

    @Test
    fun `standing still past the threshold ends the recording`() {
        val tape = Tape().apply {
            move(minutes(30), 3.0)
            still(minutes(idleMinutes + 5))
        }
        val arrivedAtMs = minutes(30) * 1000L

        val verdict = assertNotNull(judge(tape))
        assertEquals(AutoStopReason.STATIONARY, verdict.reason)
        // Decided the threshold after arriving, give or take the last
        // anchor before the user stopped — which is within one radius of walking.
        val decidedAfterArrival = verdict.decidedAtMs - arrivedAtMs
        assertTrue(
            decidedAfterArrival in Tuning.AUTO_STOP_IDLE_MS - 30_000..Tuning.AUTO_STOP_IDLE_MS + 30_000,
            "decided ${decidedAfterArrival}ms after arriving",
        )
        // And cut back to the moment the movement stopped, not to the moment the
        // rule fired: the time on the sofa is not part of the run.
        assertTrue(
            kotlin.math.abs(verdict.keepThrough.tOffsetMs - arrivedAtMs) <= 30_000,
            "cut at ${verdict.keepThrough.tOffsetMs}, arrived at $arrivedAtMs",
        )
    }

    @Test
    fun `an auto-pause is not a shield`() {
        // Health Services auto-pauses and writes nothing at all, so the only
        // evidence that anything is happening is that nothing is. This is the
        // watch on the kitchen table, and the rule it exists for.
        val tape = Tape().apply {
            move(minutes(25), 3.0)
            silence(minutes(idleMinutes + 5))
        }
        val verdict = assertNotNull(judge(tape, tickTo = tape.tMs))
        assertEquals(AutoStopReason.STATIONARY, verdict.reason)
        // The last anchor before the silence, which at 3 m/s is within a radius
        // — ten seconds — of the last sample there was.
        val lastSampleMs = minutes(25) * 1000L - 1000L
        assertTrue(
            lastSampleMs - verdict.keepThrough.tOffsetMs in 0..15_000,
            "cut at ${verdict.keepThrough.tOffsetMs}, last sample at $lastSampleMs",
        )
    }

    @Test
    fun `a manual pause shields the recording`() {
        // The user said they would be back. Nothing here may disagree with that,
        // however long they take.
        val pausedAtMs = minutes(20) * 1000L
        val tape = Tape().apply {
            move(minutes(20), 3.0)
            still(minutes(40))
        }
        assertNull(
            judge(tape, pauses = listOf(pausedAtMs to true), tickTo = tape.tMs + minutes(30) * 1000L),
        )
    }

    @Test
    fun `resuming starts the patience over`() {
        // Half an hour standing at a station, then resumed and immediately still
        // again: the patience counts from the resume, so the recording survives
        // the first ten minutes of it and not the time past the threshold.
        val pausedAtMs = minutes(20) * 1000L
        val resumedAtMs = minutes(50) * 1000L
        val tape = Tape().apply {
            move(minutes(20), 3.0)
            // Samples until ten minutes after the resume; ticks carry it on.
            still(minutes(40))
        }
        val pauses = listOf(pausedAtMs to true, resumedAtMs to false)

        assertNull(
            judge(tape, pauses = pauses, tickTo = resumedAtMs + minutes(10) * 1000L),
            "ten minutes after the resume is still within the patience",
        )
        val verdict = assertNotNull(
            judge(tape, pauses = pauses, tickTo = resumedAtMs + minutes(idleMinutes + 5) * 1000L),
        )
        assertEquals(AutoStopReason.STATIONARY, verdict.reason)
        assertTrue(verdict.decidedAtMs >= resumedAtMs + Tuning.AUTO_STOP_IDLE_MS)
    }

    // ------------------------------------------------------------------ Kein GPS

    @Test
    fun `a receiver that goes blind ends the recording at its last fix`() {
        // Walked indoors. The fixes do not stop, they degrade — which is why the
        // stationary rule cannot see this: a 200 m error wanders far enough to
        // keep resetting the anchor for ever.
        val tape = Tape().apply {
            move(minutes(30), 1.4)
            blind(minutes(20))
        }
        val lastFixMs = minutes(30) * 1000L - 1000L

        val verdict = assertNotNull(judge(tape, ExerciseType.WALKING))
        assertEquals(AutoStopReason.NO_FIX, verdict.reason)
        assertEquals(lastFixMs, verdict.keepThrough.tOffsetMs)
    }

    @Test
    fun `a dropout shorter than the threshold survives`() {
        val tape = Tape().apply {
            move(minutes(20), 3.0)
            blind(minutes(10))
            move(minutes(20), 3.0)
        }
        assertNull(judge(tape))
    }

    // ----------------------------------------------------------------- Zu schnell

    @Test
    fun `a drive home is cut where the car started`() {
        val tape = Tape().apply {
            move(minutes(30), 3.0)
            // 25 m/s is 90 km/h: a car, and three times what the running ceiling
            // allows. Three minutes of it, which is twice the evidence needed.
            move(minutes(3), 25.0)
        }
        val gotInMs = minutes(30) * 1000L - 1000L

        val verdict = assertNotNull(judge(tape))
        assertEquals(AutoStopReason.IMPLAUSIBLE_SPEED, verdict.reason)
        assertEquals(gotInMs, verdict.keepThrough.tOffsetMs)
        // And it is decided as soon as there is enough of it, not at the end of
        // the drive.
        assertTrue(
            verdict.decidedAtMs - gotInMs <= Tuning.AUTO_STOP_OVERSPEED_MS + 5_000,
            "decided ${(verdict.decidedAtMs - gotInMs) / 1000}s into the drive",
        )
    }

    @Test
    fun `a single jump is noise and not a car`() {
        // One second of GNSS noise at 500 m is 1800 km/h. It is also one second
        // long, and the segment after it is ordinary again.
        val tape = Tape().apply {
            move(minutes(10), 3.0)
            jump(500.0)
            move(minutes(10), 3.0)
        }
        assertNull(judge(tape))
    }

    @Test
    fun `a fast descent is still cycling`() {
        // 18 m/s is 65 km/h downhill, under the 80 the rule allows a bike.
        val tape = Tape().apply {
            move(minutes(20), 7.0)
            move(minutes(5), 18.0)
            move(minutes(20), 7.0)
        }
        assertNull(judge(tape, ExerciseType.BIKING))
    }

    @Test
    fun `skiing has no speed ceiling`() {
        // A lift is part of the activity and moves like a road vehicle; a descent
        // moves faster than most of them. The rule is switched off rather than
        // made unreliable — and the other two still watch.
        assertNull(AutoStop.ceilingKmh(ExerciseType.SKIING))
        val tape = Tape().apply {
            move(minutes(20), 5.0)
            move(minutes(5), 25.0)
        }
        assertNull(judge(tape, ExerciseType.SKIING))
        for (type in ExerciseType.entries - ExerciseType.SKIING) {
            assertNotNull(AutoStop.ceilingKmh(type), "$type needs a ceiling")
        }
    }

    @Test
    fun `a car after a dropout is still a car`() {
        // The receiver loses the sky in a valley and finds it again five minutes
        // and six kilometres later. Whatever happened to the fixes, nobody ran
        // that.
        val tape = Tape().apply {
            move(minutes(20), 3.0)
            silence(minutes(5), travelledM = 6_000.0)
            move(minutes(2), 20.0)
        }
        // The first sample after the silence is 6 km along at an implied
        // 72 km/h — over the ceiling, and five minutes of it in one segment.
        val verdict = assertNotNull(judge(tape))
        assertEquals(AutoStopReason.IMPLAUSIBLE_SPEED, verdict.reason)
        assertEquals(minutes(20) * 1000L - 1000L, verdict.keepThrough.tOffsetMs)
    }

    // ------------------------------------------------------------------ the once

    @Test
    fun `a recording is only ended once`() {
        val watch = AutoStopWatch(ExerciseType.RUNNING)
        val tape = Tape().apply {
            move(minutes(20), 3.0)
            still(minutes(idleMinutes + 5))
        }
        var verdicts = 0
        for (sample in tape.samples) if (watch.sample(sample) != null) verdicts++
        var t = tape.tMs
        repeat(20) {
            if (watch.tick(t) != null) verdicts++
            t += Tuning.AUTO_STOP_TICK_MS
        }
        assertEquals(1, verdicts, "one ending, whatever arrives after it")
        assertNotNull(watch.decision)
    }

    // -------------------------------------------------------------------- the cut

    @Test
    fun `the cut drops positions, rates, sensor series and the match log but keeps the events`() {
        val session = sessionWithCut(cutAtMs = 60_000L)
        val trimmed = AutoStop.trim(session)

        assertEquals(61, trimmed.samples.size, "one sample a second through the cut, inclusive")
        assertTrue(trimmed.samples.all { it.tOffsetMs <= 60_000L })
        assertTrue(trimmed.heartRates.all { it.tOffsetMs <= 60_000L })
        assertTrue(trimmed.matchLog.all { it.tOffsetMs <= 60_000L })
        // The barometer and speed series are sampled on their own schedule, and
        // the drive home's readings must not survive in them either.
        assertEquals(3, trimmed.altitudes.size)
        assertTrue(trimmed.altitudes.all { it.tOffsetMs <= 60_000L })
        assertEquals(3, trimmed.speeds.size)
        assertTrue(trimmed.speeds.all { it.tOffsetMs <= 60_000L })
        // The AUTO_STOP event is by definition later than the cut it describes.
        // It is the only record of why the session ends where it does.
        assertEquals(session.events, trimmed.events)
        assertTrue(trimmed.events.any { it.type == SessionEventType.AUTO_STOP })
        assertEquals(59, AutoStop.discardedSamples(session))
    }

    @Test
    fun `a session with no cut comes back untouched`() {
        val session = sessionWithCut(cutAtMs = null)
        assertTrue(AutoStop.trim(session) === session, "identity, not a copy")
        assertEquals(0, AutoStop.discardedSamples(session))
    }

    @Test
    fun `a cut past the end of the recording changes nothing`() {
        val session = sessionWithCut(cutAtMs = 999_000L)
        assertTrue(AutoStop.trim(session) === session)
    }

    private fun sessionWithCut(cutAtMs: Long?): RecordedSession {
        val samples = List(120) { i ->
            Sample(
                tOffsetMs = i * 1000L,
                activeMs = i * 1000L,
                lat = 50.1 + i * 1e-5,
                lon = 8.9,
                accuracyM = 8.0,
                cumDistanceM = i * 3.0,
            )
        }
        return RecordedSession(
            header = SessionHeader(
                sessionId = "s-cut",
                startedAtUtcMs = 1_700_000_000_000L,
                tzOffsetMinutes = 120,
                exerciseType = ExerciseType.RUNNING,
            ),
            samples = samples,
            events = listOf(
                SessionEvent(tOffsetMs = 0, type = SessionEventType.GPS_ACQUIRED),
                SessionEvent(
                    tOffsetMs = 119_000,
                    type = SessionEventType.AUTO_STOP,
                    note = "Stillstand seit 15 min — beendet",
                ),
            ),
            footer = SessionFooter(
                endedAtUtcMs = 1_700_000_120_000L,
                activeDurationMs = cutAtMs ?: 119_000L,
                totalDistanceM = 360.0,
                reason = if (cutAtMs == null) SessionEndReason.USER_STOPPED else SessionEndReason.AUTO_STOPPED,
                trimmedAfterTOffsetMs = cutAtMs,
            ),
            matchLog = List(120) { i ->
                MatchLogEntry(
                    tOffsetMs = i * 1000L,
                    event = MatchEvent.SAMPLE,
                    phase = MatchPhase.SEARCHING,
                    nullWeight = 0.5,
                )
            },
            heartRates = List(120) { i -> HeartRate(tOffsetMs = i * 1000L, bpm = 130) },
            altitudes = List(6) { i -> Reading(tOffsetMs = i * 30_000L, value = 260.0 + i) },
            speeds = List(6) { i -> Reading(tOffsetMs = i * 30_000L, value = 3.0) },
        )
    }
}
