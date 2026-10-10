package de.hecate.core.recorder

import de.hecate.core.Tuning
import de.hecate.core.geo.Geo
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.Sample

/**
 * Why a recording ended itself.
 *
 * Three situations, and all three are the same mistake seen from different
 * sides: the activity is over and the watch has not been told. The [label] is
 * what the wrist and the session's own event log say it was — German, like every
 * other word the user reads (§4).
 */
enum class AutoStopReason(val label: String) {
    /** The user has not left a [Tuning.AUTO_STOP_IDLE_RADIUS_M] circle for
     *  [Tuning.AUTO_STOP_IDLE_MS]. Arrived home, sat down, forgot. */
    STATIONARY("Stillstand"),

    /** Not one usable fix for [Tuning.AUTO_STOP_NO_FIX_MS], after the session had
     *  had some. Walked indoors with the watch still running. */
    NO_FIX("Kein GPS"),

    /** Moving faster than the sport can, for long enough that it cannot be noise.
     *  Got in the car. */
    IMPLAUSIBLE_SPEED("Zu schnell"),
}

/**
 * The decision, and the cut that goes with it.
 *
 * [keepThrough] is the last sample that is still part of the activity: the
 * moment the user stopped moving, the last fix the receiver actually had, or the
 * last position reached at a speed the sport can manage. Everything after it is
 * the drive home or the sofa, and is discarded — see [AutoStop.trim].
 */
data class AutoStopVerdict(
    val reason: AutoStopReason,
    val keepThrough: Sample,
    /** Session offset at which the decision was taken [ms]. Later than
     *  [keepThrough] by roughly the threshold that was crossed. */
    val decidedAtMs: Long,
    /** One line for the session's event log and for the watch. */
    val note: String,
)

/**
 * Ending a recording nobody is doing any more, and throwing away what it
 * recorded instead of the activity.
 *
 * A workout that is not stopped keeps recording: the walk home from the trail,
 * the twenty minutes in the kitchen, the drive back from the ski resort. All
 * three end up in the track, in the distance, in Health Connect, and — worse
 * than any of that — in the route clusterer, which learns from what it is given
 * and cannot tell a commute from a drive along the same road.
 *
 * So three rules watch the recording, and the first one to be sure ends it. They
 * are deliberately different in kind, because the three ways an activity ends
 * without being stopped look nothing like each other:
 *
 *  - **Stillstand.** The user has not left a small circle for three quarters
 *    of an hour ([Tuning.AUTO_STOP_IDLE_MS]) — longer than any break the real
 *    history has in it. The cut goes back to the moment they arrived at it.
 *  - **Kein GPS.** A quarter of an hour ([Tuning.AUTO_STOP_NO_FIX_MS]) of fixes
 *    that arrive and are useless, which is what walking indoors looks like. The
 *    cut goes back to the last fix the receiver actually had. See [check] for
 *    which of the two gets to name the ending, and why that is worth deciding.
 *  - **Zu schnell.** A speed above what the sport can do, sustained for
 *    [Tuning.AUTO_STOP_OVERSPEED_MS]. The cut goes back to the last position
 *    reached at a plausible speed, which is where the activity actually ended
 *    and the vehicle began.
 *
 * Two things it deliberately does not do. It never ends an activity that has not
 * yet travelled [Tuning.AUTO_STOP_ARM_M] — a watch started early, before the
 * warm-up, must not stop itself while its owner is still tying their laces. And
 * a **manual** pause suspends every rule: pausing is an explicit "I will be
 * back", and the user who pressed it is the one authority this code has. An
 * auto-pause is not: it means Health Services noticed the standing still that
 * these rules are about, and it writes no samples at all, so the timers run on
 * through it exactly as they would if the receiver had simply gone quiet.
 *
 * Clock-free and Android-free, like the matcher and for the same reason (NFR-4):
 * every decision is a function of the sample offsets, so a replay of a stored
 * session reaches the same verdict at the same sample as the wrist did, and a
 * unit test can put fifteen minutes into two lines.
 */
class AutoStopWatch(private val type: ExerciseType) {

    // The recorder asks from more than one thread — the sample path and the
    // watchdog both run on Dispatchers.Default — and a verdict is decided once,
    // so every entry point is synchronised: two racing calls must not both find
    // no verdict and each decide one.

    /** Null for a sport the speed rule is not applied to. */
    private val ceilingKmh: Double? = AutoStop.ceilingKmh(type)

    /** Where the activity began, and how far from it it has been. Until it has
     *  been [Tuning.AUTO_STOP_ARM_M] away, nothing here may end anything. */
    private var origin: Sample? = null
    private var armed = false

    /** The last fix good enough to use, and the spot the user has not left. */
    private var lastUsable: Sample? = null
    private var anchor: Sample? = null

    /** Fixes that have arrived since the last usable one and were too poor to be
     *  any. Nothing decides on this — it decides which of the two timers gets to
     *  put its name on the ending. */
    private var unusableSinceFix = 0

    /** The last position reached at a plausible speed, and when the current run
     *  of impossible ones began. Null when the last segment was plausible. */
    private var lastPlausible: Sample? = null
    private var overspeedSinceMs: Long? = null

    /** A pause the user asked for. Nothing is decided while it lasts, and both
     *  timers count from the resume rather than through it. */
    private var manuallyPaused = false
    private var timerFloorMs = 0L
    private var pauseSpanned = false

    /** Decided once. A second verdict would be a second ending, and there is
     *  only ever one. */
    private var verdict: AutoStopVerdict? = null

    /** What has been decided, if anything. */
    val decision: AutoStopVerdict? get() = verdict

    /**
     * One sample, in the order it was recorded.
     *
     * Returns the verdict the moment there is one, and null every time
     * afterwards: the caller ends the session once.
     */
    @Synchronized
    fun sample(sample: Sample): AutoStopVerdict? {
        if (verdict != null || manuallyPaused) return null
        if (sample.usable) {
            speed(sample)?.let { return decide(it, lastPlausible ?: sample, sample.tOffsetMs) }
            move(sample)
            lastUsable = sample
            unusableSinceFix = 0
        } else if (!sample.paused) {
            unusableSinceFix++
        }
        return check(sample.tOffsetMs)
    }

    /**
     * Time passing with nothing to show for it.
     *
     * Both timers can expire without a sample to carry them — an auto-pause
     * writes none at all, and a receiver that has given up writes none that are
     * usable — so the recorder also asks on a timer ([Tuning.AUTO_STOP_TICK_MS]).
     * [tOffsetMs] is the session's own monotonic offset, the same clock the
     * samples are stamped with.
     */
    @Synchronized
    fun tick(tOffsetMs: Long): AutoStopVerdict? {
        if (verdict != null || manuallyPaused) return null
        return check(tOffsetMs)
    }

    /**
     * The user's own pause button.
     *
     * While it is held every rule is suspended, and resuming restarts both
     * timers from that moment rather than resuming them: a recording that waited
     * out a train with the user's knowledge has not been standing still
     * unwatched, and must not be ended a minute after it starts moving again.
     */
    @Synchronized
    fun manualPause(paused: Boolean, tOffsetMs: Long) {
        if (manuallyPaused == paused) return
        manuallyPaused = paused
        if (!paused) {
            // The positions stay what they were — the anchor is still the spot
            // the user is standing on, and the last usable fix is still the last
            // one the receiver had. Only the clocks move up.
            timerFloorMs = tOffsetMs
            overspeedSinceMs = null
            pauseSpanned = true
        }
    }

    // ------------------------------------------------------------------ rules

    /**
     * The two timers, against whatever the current session offset is.
     *
     * They are anchored to the same kind of moment — the last usable fix, and
     * the last usable fix that was somewhere *new* — so the idle clock is never
     * behind the blind one. The blind timer is the shorter of the two, and it
     * only counts when fixes are arriving and useless: a resting user, or an
     * auto-pause that delivers nothing at all, leaves it running without
     * evidence, and only the idle threshold may end that.
     *
     * What is genuinely two decisions is the *name*, and it is worth getting
     * right: "Stillstand" and "Kein GPS" send the user to look at completely
     * different things, and they cut in different places. The receiver is blamed
     * only when it is arriving and useless — the last minute of fixes was too
     * poor to use ([Tuning.FIX_LOST_MS], the same threshold the live page calls
     * a lost fix by) — which is what walking indoors looks like from here. Then
     * the cut goes to the last fix the receiver actually had, which is the door.
     * Anything else is standing still: fixes that are fine and a user who is not
     * moving, or an auto-pause that says nothing at all. Then the cut goes back
     * to the spot, which is where they arrived.
     */
    private fun check(nowMs: Long): AutoStopVerdict? {
        if (!armed) return null
        val fix = lastUsable ?: return null
        val spot = anchor ?: return null
        val blind = nowMs - maxOf(fix.tOffsetMs, timerFloorMs)
        val idle = nowMs - maxOf(spot.tOffsetMs, timerFloorMs)
        val receiverFailing = unusableSinceFix > 0 && blind >= Tuning.FIX_LOST_MS
        val idleExpired = idle >= Tuning.AUTO_STOP_IDLE_MS
        val blindExpired = receiverFailing && blind >= Tuning.AUTO_STOP_NO_FIX_MS
        if (!idleExpired && !blindExpired) return null
        return if (receiverFailing) {
            decide(AutoStopReason.NO_FIX, fix, nowMs, minutes(blind))
        } else {
            decide(AutoStopReason.STATIONARY, spot, nowMs, minutes(idle))
        }
    }

    /**
     * Where the user is, and whether they have gone anywhere.
     *
     * The anchor is the last place they were seen to *arrive* at: a sample
     * farther than [Tuning.AUTO_STOP_IDLE_RADIUS_M] from it replaces it, so the
     * time on the anchor is how long they have been where they are. Everything
     * closer leaves it alone, which is what lets the idle clock accumulate.
     */
    private fun move(sample: Sample) {
        val start = origin
        if (start == null) {
            origin = sample
            anchor = sample
            lastPlausible = sample
            return
        }
        if (!armed && distance(start, sample) > Tuning.AUTO_STOP_ARM_M) armed = true
        val a = anchor
        if (a == null || distance(a, sample) > Tuning.AUTO_STOP_IDLE_RADIUS_M) anchor = sample
    }

    /**
     * Speed over the ground since the last usable fix, against the sport's
     * ceiling.
     *
     * Measured between consecutive usable fixes however far apart they are,
     * including across a dropout: crossing three kilometres of ground during a
     * five-minute hole is not a bike ride whatever happened to the receiver, and
     * that hole is exactly what a car in a valley looks like. What keeps noise
     * out is not a gap rule but the ninety seconds of *continuous* evidence the
     * verdict needs — a bad pair of fixes is one second long, and the segment
     * after it starts the run over.
     */
    private fun speed(sample: Sample): AutoStopReason? {
        val ceiling = ceilingKmh ?: return null
        val previous = lastUsable ?: return null
        val dtMs = sample.tOffsetMs - previous.tOffsetMs
        if (dtMs <= 0) return null
        // The first segment after a resume spans the pause, and what happened
        // during a pause is by definition not part of the activity — a user who
        // pauses at the station and resumes at the other end has not cycled
        // there. Neither evidence nor a reset; the next segment decides.
        if (pauseSpanned) {
            pauseSpanned = false
            lastPlausible = sample
            return null
        }
        val kmh = Geo.kmh(distance(previous, sample), dtMs)
        if (kmh <= ceiling) {
            overspeedSinceMs = null
            lastPlausible = sample
            return null
        }
        val since = overspeedSinceMs ?: previous.tOffsetMs.also { overspeedSinceMs = it }
        if (sample.tOffsetMs - since < Tuning.AUTO_STOP_OVERSPEED_MS) return null
        return AutoStopReason.IMPLAUSIBLE_SPEED
    }

    private fun decide(
        reason: AutoStopReason,
        keepThrough: Sample,
        nowMs: Long,
        forMinutes: Long? = null,
    ): AutoStopVerdict {
        val note = when (reason) {
            AutoStopReason.IMPLAUSIBLE_SPEED ->
                "Zu schnell für ${type.label} — beendet, ${minutes(nowMs - keepThrough.tOffsetMs)} min verworfen"
            else ->
                "${reason.label} seit ${forMinutes ?: minutes(nowMs - keepThrough.tOffsetMs)} min — beendet"
        }
        val v = AutoStopVerdict(reason, keepThrough, nowMs, note)
        verdict = v
        return v
    }

    private fun minutes(ms: Long): Long = ms / 60_000L

    private fun distance(a: Sample, b: Sample): Double = Geo.haversine(a.lat, a.lon, b.lat, b.lon)
}

/**
 * The two things about auto-stop that are not the state machine: which sports
 * have a speed ceiling, and what a session looks like once the cut is applied.
 */
object AutoStop {

    /**
     * The speed above which this sport is not what is happening [km/h], or null
     * where the question cannot be answered by speed at all.
     *
     * Skifahren is the null: a lift is part of the activity and moves like a
     * road vehicle, and a descent moves faster than most of them. The rule is
     * switched off there rather than made unreliable, and the other two rules
     * still watch the recording.
     */
    fun ceilingKmh(type: ExerciseType): Double? = when (type) {
        ExerciseType.RUNNING -> Tuning.AUTO_STOP_MAX_KMH_RUNNING
        ExerciseType.WALKING -> Tuning.AUTO_STOP_MAX_KMH_WALKING
        ExerciseType.HIKING -> Tuning.AUTO_STOP_MAX_KMH_HIKING
        ExerciseType.BIKING -> Tuning.AUTO_STOP_MAX_KMH_BIKING
        ExerciseType.SKIING -> null
    }

    /**
     * The session as it actually happened: everything after the cut removed.
     *
     * The watch records the cut in the footer
     * ([SessionFooter.trimmedAfterTOffsetMs][de.hecate.core.model.SessionFooter.trimmedAfterTOffsetMs])
     * rather than rewriting the file, because the file is append-only and that
     * is the whole of NFR-1 — a recorder that goes back and edits what it has
     * already written is a recorder that can lose a workout. The phone applies
     * the cut once, on the way in, so the drive home never reaches the database,
     * Health Connect, the clusterer or a backup.
     *
     * Positions, heart rates, the sensors' own series and the matcher's own
     * log are all cut; the events are not. The `AUTO_STOP` event sits *after*
     * the cut by definition — it is the record of the decision — and it is the
     * only thing that explains why the recording ends where it does.
     *
     * A session with no cut in its footer comes back untouched, which is every
     * session recorded before this existed and every one that was stopped by
     * hand.
     */
    fun trim(session: RecordedSession): RecordedSession {
        val cut = session.footer?.trimmedAfterTOffsetMs ?: return session
        if (session.samples.none { it.tOffsetMs > cut } &&
            session.heartRates.none { it.tOffsetMs > cut } &&
            session.altitudes.none { it.tOffsetMs > cut } &&
            session.speeds.none { it.tOffsetMs > cut } &&
            session.matchLog.none { it.tOffsetMs > cut }
        ) {
            return session
        }
        return session.copy(
            samples = session.samples.filter { it.tOffsetMs <= cut },
            heartRates = session.heartRates.filter { it.tOffsetMs <= cut },
            altitudes = session.altitudes.filter { it.tOffsetMs <= cut },
            speeds = session.speeds.filter { it.tOffsetMs <= cut },
            matchLog = session.matchLog.filter { it.tOffsetMs <= cut },
        )
    }

    /** How many positions [trim] would drop. For the log line that says so. */
    fun discardedSamples(session: RecordedSession): Int {
        val cut = session.footer?.trimmedAfterTOffsetMs ?: return 0
        return session.samples.count { it.tOffsetMs > cut }
    }
}
