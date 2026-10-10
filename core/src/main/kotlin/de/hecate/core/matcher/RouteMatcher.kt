package de.hecate.core.matcher

import de.hecate.core.Tuning
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.geo.Polyline
import de.hecate.core.model.ActiveClock
import de.hecate.core.model.Candidate
import de.hecate.core.model.DayBucket
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.MatchEvent
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.MatchPhase
import de.hecate.core.model.MatchState
import de.hecate.core.model.RouteBundle
import de.hecate.core.model.RouteEntry
import de.hecate.core.model.RoutePrior
import de.hecate.core.model.Sample
import de.hecate.core.model.TimeBucket
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * The online route matcher (§5.6).
 *
 * Pure Kotlin and entirely deterministic: the same samples in the same order
 * always produce the same decisions, which is what makes the replay harness of
 * US-10 a meaningful test rather than an approximation. No wall clock, no
 * randomness, no iteration over unordered collections.
 *
 * Two halves. The first follows every route at once: where along it the user
 * is, whether they are following it forwards, and a weight per route plus a
 * null hypothesis ("none of them"). The second names at most one route, and
 * reads everything route-relative off that one ([updateFocus]): the routes
 * that fit equally well are told apart by how much of today they explain,
 * then by the user's own ranking — never by the weights, which on shared
 * ground measure GNSS noise. [MatchState.phase] tells the display what it may
 * honestly claim.
 *
 * Not thread-safe. One instance per session, fed from one thread.
 */
class RouteMatcher(
    bundle: RouteBundle,
    private val exerciseType: ExerciseType,
    startedAtUtcMs: Long,
    tzOffsetMinutes: Int,
    /** When non-null, every match log entry is handed here as it is produced
     *  (US-10). The replay harness collects them in a list; the watch streams
     *  them straight into the session file. */
    private val log: ((MatchLogEntry) -> Unit)? = null,
) {

    private class Cand(
        val entry: RouteEntry,
        val poly: Polyline,
        var logW: Double,
        /** Distance along the route of the last projection; < 0 means "not localised". */
        var positionM: Double = -1.0,
        var offsetM: Double = Double.MAX_VALUE,
        /** Travel accumulated while this route was neither near nor tracking. */
        var unseenM: Double = 0.0,
        /** Advance along this route per metre actually covered, over the last
         *  [Tuning.TRACK_RATIO_WINDOW_M] of travel ([trail]). About 1 while the
         *  route is being followed, about -1 for its reverse. */
        var trackRatio: Double = 1.0,
        /** Recent (travelled, position, place) triples the ratio is read off. */
        val trail: ArrayDeque<TrailPoint> = ArrayDeque(),
        /** How far the position has run backwards along the route since it last
         *  stood at its furthest. See [Tuning.BRANCH_SWITCH_RETREAT_M]. */
        var retreatM: Double = 0.0,
        /** Where along the route the user joined it; < 0 until they have been
         *  found on it, following it. See [join]. */
        var joinPosM: Double = -1.0,
        /** The session's travelled distance when it was joined: how much of
         *  today's ride it explains, and the first thing a choice between
         *  routes asks ([choose]). */
        var joinTravelM: Double = 0.0,
        /** Set off on at the start of the session and followed ever since. */
        var fromStart: Boolean = false,
        /** Ahead/behind on this route is `elapsed − (usual time here − anchorS)`.
         *  Set when the route is joined, and again when the display hands over
         *  to it from another route ([handOver]). */
        var anchorS: Double = 0.0,
        /** Active time [s] at which the comparison [anchorS] describes began;
         *  today's pace for the arrival time is read from here. */
        var originS: Double = 0.0,
        /** Travel [m] since this route, while named, last lay within
         *  [Tuning.COMMIT_SEPARATION_M] of the nearest route. */
        var outOfBandM: Double = 0.0,
        var dropped: Boolean = false,
    ) {
        val routeId: Long get() = entry.geometry.routeId

        /** Distance [m] followed along the route since joining it. */
        val followedM: Double get() = if (joinPosM < 0.0) -1.0 else positionM - joinPosM

        /** Found under the user and being followed forwards, right now. */
        val following: Boolean
            get() = !dropped && positionM >= 0.0 &&
                offsetM <= Tuning.MATCH_RADIUS_M && trackRatio >= Tuning.MIN_TRACKING_RATIO
    }

    private class TrailPoint(val travelledM: Double, val positionM: Double, val at: LatLon)

    /** Candidates by route id, iterated in ascending id order for determinism. */
    private val cands = LinkedHashMap<Long, Cand>()
    private val orderedIds: List<Long>

    private var nullLogW: Double
    private var travelledM = 0.0
    private var prev: Sample? = null
    /** Active time on [ActiveClock]'s rule, not the watch's own figure: the
     *  route profiles this is compared against were measured on that clock, and
     *  the watch's keeps running through samples it has flagged as paused. */
    private var lastActiveMs = 0L
    private var lastAnySample: Sample? = null

    /** Session time of the last sample that carried evidence, and the last delta
     *  computed from one. Together they are how the display learns that what it
     *  is showing is the last honest answer rather than a current one: while no
     *  usable sample arrives the projected position stands still, and a delta
     *  recomputed against a standing position simply counts the outage as
     *  lateness. */
    private var lastUsableTOffsetMs = -1L
    private var lastDelta: Double? = null

    /**
     * The one route everything route-relative is read off — the name on the
     * wrist, ahead/behind, the distance left and the arrival time — or null.
     * Committed once it has been followed [Tuning.COMMIT_MIN_M]; until then a
     * guess, and said to be one. See [updateFocus].
     */
    private var focusId: Long? = null
    private var committedRouteId: Long? = null
    /** True while the commitment is one of several routes over the same ground,
     *  chosen by [choose] rather than by the evidence. Reported so the display
     *  and the match log can say which. */
    private var committedByPriority = false
    private var phase = MatchPhase.SEARCHING

    /**
     * The route the deviation detector is watching (US-7).
     *
     * Armed by commitment and then held independently of the match state, because
     * the two cannot be coupled: leaving a route is precisely what destroys the
     * commitment to it. Gating the alert on the *current* phase means the alert is
     * cancelled by the event it exists to report. So commitment arms the detector,
     * and the detector then answers one question directly — how far is the user
     * from that route — until they are back on it or a different route commits.
     */
    private var armedRouteId: Long? = null
    private var armedPoly: Polyline? = null
    private var deviationCount = 0
    private var deviationArmed = true
    private var offRoute = false

    private val kWindow = ArrayDeque<Double>()
    private var kSum = 0.0

    /** Set by the host after repeated failures; the matcher then reports nothing. */
    var disabled: Boolean = false
        private set

    private val grid: GridIndex

    init {
        val usable = bundle.routes
            .filter { it.geometry.exerciseType == exerciseType && it.geometry.points.size >= 2 }
            .sortedBy { it.geometry.routeId }

        val priors = initialPriors(usable, startedAtUtcMs, tzOffsetMinutes)
        for (e in usable) {
            val pl = toPolyline(e)
            val w = priors.getValue(e.geometry.routeId)
            cands[e.geometry.routeId] = Cand(e, pl, ln(max(w, 1e-12)))
        }
        orderedIds = cands.keys.sorted()
        nullLogW = ln(max(priors[NULL_KEY] ?: Tuning.NULL_PRIOR_FLOOR, 1e-12))

        grid = GridIndex.build(cands.mapValues { it.value.poly })
        normalise()
    }

    fun disable() {
        disabled = true
    }

    /** Current belief without consuming a sample. */
    fun state(tOffsetMs: Long): MatchState = buildState(tOffsetMs, deviationFired = false)

    /**
     * Feed samples that arrived in a burst (screen off, §5.6 "Batching"). Sorting
     * by timestamp and replaying one at a time is what makes the batched result
     * identical to the streamed one.
     */
    fun updateAll(samples: List<Sample>): MatchState? {
        var last: MatchState? = null
        for (s in samples.sortedBy { it.tOffsetMs }) last = update(s)
        return last
    }

    /**
     * Consume one sample and return the new belief.
     *
     * Unusable samples (paused, or accuracy beyond [Tuning.MAX_USABLE_ACCURACY_M])
     * carry no evidence: the belief is returned unchanged rather than decayed, so
     * that standing at a red light never erodes a correct match.
     */
    fun update(delivered: Sample): MatchState {
        if (disabled) return MatchState(delivered.tOffsetMs, phase, committedRouteId, disabled = true)
        // A position flagged paused only because of the delivery it came in, as
        // the profiles' clock reads it too. See [ActiveClock.unpause].
        val sample = ActiveClock.unpause(lastAnySample, delivered)
        // Late, and therefore no longer evidence about where the user is.
        // Positions reached the matcher after newer ones had already been
        // handed over — on a walk of 5 Oct, 34 times, by 3 to 21 s, most likely
        // the recorder working on two deliveries at once (see its `lane`).
        // Consumed in arrival order, each one moved the user backwards along the
        // route: the position retreated, the route was demoted and re-committed
        // a dozen times, the travelled distance came out 60 % long, and
        // ahead/behind swung with every one. The sample is already in the session
        // file; the matcher only answers "where is the user now", which a
        // position from twenty seconds ago does not.
        lastAnySample?.let { if (sample.tOffsetMs <= it.tOffsetMs) return state(it.tOffsetMs) }
        lastAnySample?.let { lastActiveMs += activeBetween(it, sample) }
        lastAnySample = sample
        if (!sample.usable) {
            if (sample.paused) {
                prev = null // do not measure travel across a pause
                // A pause is not a stale position: nothing is expected to move,
                // and active time is not running either, so the belief is as
                // current as it ever was. Only a sample rejected for *accuracy*
                // leaves the projection behind the user.
                lastUsableTOffsetMs = sample.tOffsetMs
            }
            return buildState(sample.tOffsetMs, deviationFired = false)
        }
        lastUsableTOffsetMs = sample.tOffsetMs
        if (cands.isEmpty()) {
            // No bundle, or no route of this exercise type: honestly unknown.
            phase = MatchPhase.UNKNOWN
            return buildState(sample.tOffsetMs, deviationFired = false)
        }

        val here = LatLon(sample.lat, sample.lon)
        val p = prev
        var step = 0.0
        var dropout = false
        if (p != null) {
            step = Geo.haversine(p.lat, p.lon, sample.lat, sample.lon)
            if (step > Tuning.MAX_INTERPOLATION_GAP_M ||
                sample.tOffsetMs - p.tOffsetMs > Tuning.MAX_INTERPOLATION_GAP_MS
            ) {
                dropout = true
            }
        }
        prev = sample
        travelledM += step

        admitNearby(here)

        val sigma = max(Tuning.GPS_SIGMA_M, sample.accuracyM ?: 0.0)
        val twoSigmaSq = 2.0 * sigma * sigma

        for (id in orderedIds) {
            val c = cands[id] ?: continue
            if (c.dropped) continue

            val previousPos = c.positionM
            var hit = when {
                // Nothing to go on yet: the start of the route, where workouts
                // start, wins any tie — on a closed loop the finish is just as near.
                previousPos < 0.0 -> c.poly.localise(here, 0.0, Tuning.LOCALISE_TIE_M)
                // A dropout invalidates dead reckoning, so the whole geometry is
                // searched rather than a window around a stale guess — but where
                // the route passes this spot twice, the pass that continues from
                // where the user was last seen is the one they are on.
                dropout -> c.poly.localise(
                    here,
                    previousPos - Tuning.BACKTRACK_SLACK_M,
                    Tuning.LOCALISE_TIE_M,
                )
                // Asymmetric by design: a long reach forwards for uncertainty in
                // the travelled distance, almost none backwards.
                else -> c.poly.projectWindow(
                    here,
                    previousPos - Tuning.BACKTRACK_SLACK_M,
                    previousPos + step + Tuning.SEARCH_WINDOW_M,
                )
            }

            var switched = false
            if (previousPos >= 0.0 && p != null && !dropout) {
                updateTrackRatio(c, hit.positionM, here)
                c.retreatM = (c.retreatM - (hit.positionM - previousPos)).coerceAtLeast(0.0)
                // Both: far enough back, and going backwards over the whole
                // ratio window rather than once. A GNSS spike forwards and back
                // leaves a drawdown of its own size but covers no distance.
                if (c.retreatM >= Tuning.BRANCH_SWITCH_RETREAT_M &&
                    c.trackRatio < Tuning.BRANCH_SWITCH_MAX_RATIO
                ) {
                    otherLeg(c.poly, here, hit)?.let {
                        hit = it
                        switched = true
                    }
                }
            } else {
                c.retreatM = 0.0
            }
            if (switched) {
                c.trackRatio = 1.0
                c.retreatM = 0.0
            }
            // A position that did not come from following the route — the
            // first, one after a dropout, one on the other leg — starts the
            // trail afresh: progress measured across it would be the jump.
            if (previousPos < 0.0 || dropout || switched) {
                c.trail.clear()
                c.trail.addLast(TrailPoint(travelledM, hit.positionM, here))
            }
            c.positionM = hit.positionM
            c.offsetM = hit.distanceM
            val onRoute = c.following
            c.unseenM = if (onRoute) 0.0 else c.unseenM + step
            if (onRoute && c.joinPosM < 0.0) join(c)

            // Emission: how well the sample sits on this route's geometry.
            var ll = -(hit.distanceM * hit.distanceM) / twoSigmaSq
            // Transition: whether moving along this route by that much is
            // consistent with how far the user demonstrably went. Skipped when
            // there is no usable previous position — after a dropout or a pause
            // the two are not comparable.
            if (previousPos >= 0.0 && p != null && !dropout && !switched) {
                val advance = hit.positionM - previousPos
                val sigmaAdv = max(Tuning.ADVANCE_SIGMA_M, Tuning.ADVANCE_SIGMA_FRACTION * step)
                val err = advance - step
                ll -= (err * err) / (2.0 * sigmaAdv * sigmaAdv)
            }
            c.logW = Tuning.WEIGHT_DECAY * c.logW + clampLL(ll)
        }
        nullLogW = Tuning.WEIGHT_DECAY * nullLogW +
            clampLL(-(Tuning.NULL_DISTANCE_M * Tuning.NULL_DISTANCE_M) / twoSigmaSq)

        normalise()
        dropStale()
        normalise()

        val fired = updateFocus(step)
        val devFired = updateDeviation(here)

        val st = buildState(sample.tOffsetMs, devFired)
        logEntry(if (fired != null) fired else if (devFired) MatchEvent.DEVIATION else MatchEvent.SAMPLE, st)
        return st
    }

    // ------------------------------------------------------------------ internals

    private fun clampLL(x: Double): Double = max(-Tuning.MAX_ABS_LOG_LIK, x)

    /**
     * Whether the user is following this route forwards: progress along it over
     * the last [Tuning.TRACK_RATIO_WINDOW_M] of travel, divided by the straight
     * distance actually covered in that time.
     *
     * It used to be progress per step, smoothed — progress divided by the path
     * the fixes traced. Both halves of that broke on real walks. Round a U-turn
     * the projection snaps a few metres ahead to the far side of the bend and
     * then creeps back while the user walks the bend, and five such steps put a
     * route the user never left below [Tuning.MIN_TRACKING_RATIO]. A GNSS spike
     * out and back adds fifty metres of path and no progress at all. Over a
     * window both come out in the wash: the bend is progress, and a spike that
     * returns covers no distance. A route walked the wrong way is still -1.
     *
     * Standing still, or nearly, the distance covered is too small to divide by
     * ([Tuning.TRACK_RATIO_MIN_DISPLACEMENT_M]), and the last reading stands;
     * likewise at the finish ([Tuning.FINISH_HOLD_M]).
     */
    private fun updateTrackRatio(c: Cand, positionM: Double, here: LatLon) {
        // At the finish there is nowhere further along to go: the position
        // stands at the route's end while the user walks the last metres to
        // the door, and read as progress that is "not following". Every walk
        // used to be demoted in its final seconds that way.
        if (positionM >= c.poly.lengthM - Tuning.FINISH_HOLD_M) return
        val trail = c.trail
        trail.addLast(TrailPoint(travelledM, positionM, here))
        while (trail.size > 2 && travelledM - trail[1].travelledM >= Tuning.TRACK_RATIO_WINDOW_M) {
            trail.removeFirst()
        }
        val from = trail.first()
        if (travelledM - from.travelledM < Tuning.TRACK_RATIO_WINDOW_M) return
        val covered = Geo.haversine(from.at, here)
        if (covered < Tuning.TRACK_RATIO_MIN_DISPLACEMENT_M) return
        c.trackRatio = ((positionM - from.positionM) / covered).coerceIn(-2.0, 2.0)
    }

    /**
     * Active time between two consecutive samples the matcher consumed.
     *
     * [ActiveClock]'s rule, which is what the route profiles were measured on —
     * except across a hole longer than it trusts. In a stored session such a
     * hole is an auto-pause or a dropout. Here it can also be a hole the
     * matcher made itself, by refusing positions that arrived late: the
     * samples that would have filled it exist, they just came after newer ones.
     * Counting nothing for it put a walk 72 s further ahead than it was. So
     * across a long hole the watch's own active time decides: an auto-pause
     * adds none, a stretch of late arrivals adds what it lasted.
     *
     * Only when that clock is believable, though — no more active time than
     * wall time, and never backwards. Recordings made before the watch asked
     * for the active time per position carry one checkpoint stamped across a
     * whole delivery; on a ride of 15 Sep it read 381 s on one side of a
     * minute's auto-pause and 675 s on the other, and clamping that to the
     * wall time counted the pause as riding and put the rider 50 s behind.
     */
    private fun activeBetween(previous: Sample, next: Sample): Long {
        val rule = ActiveClock.elapsed(previous, next)
        val dt = next.tOffsetMs - previous.tOffsetMs
        if (rule > 0L || previous.paused || next.paused || dt <= Tuning.MAX_INTERPOLATION_GAP_MS) return rule
        val watch = next.activeMs - previous.activeMs
        return if (watch in 0L..dt) watch else 0L
    }

    /**
     * The user has been found on this route, following it, for the first time.
     *
     * Ahead/behind is measured from here. Set off on at the session start — in
     * its first half, within [Tuning.JOIN_AT_START_M] of travel — the route is
     * compared from the start of the workout: the usual time to the joining
     * point, less whatever the user spent getting there, is set aside, so a
     * recording started a hundred metres in is not a minute ahead before the
     * first step, and a slow start still counts as today's time.
     *
     * Joined anywhere else, the route is compared from the joining point on,
     * which is the only comparison the user's day can bear. Before, it was
     * compared from the route's start: a two-kilometre ride of 18 May, begun on
     * the last stretch of "Arbeit zurück Baiersdorf", read 42 minutes ahead
     * from its first minute, the route's usual time to that point counted as
     * time saved — and rides that met the route somewhere further on read the
     * time spent elsewhere as time lost.
     *
     * A detour shorter than [Tuning.CANDIDATE_DROP_M] does not unjoin the
     * route, so the comparison goes on and the detour is what the number then
     * says. A route left for longer is dropped, and joined afresh when the user
     * is found following it again ([admitNearby]).
     */
    private fun join(c: Cand) {
        val elapsedS = lastActiveMs / 1000.0
        val usual = c.entry.profile.medianTimeAt(c.positionM)
        c.joinPosM = c.positionM
        c.joinTravelM = travelledM
        if (travelledM <= Tuning.JOIN_AT_START_M &&
            c.positionM <= Tuning.JOIN_ANCHOR_MAX_FRACTION * c.poly.lengthM
        ) {
            c.fromStart = true
            c.anchorS = (usual - elapsedS).coerceAtLeast(0.0)
            c.originS = 0.0
        } else {
            c.anchorS = usual - elapsedS
            c.originS = elapsedS
        }
    }

    /**
     * The other place this route passes [here], when the candidate has been
     * caught running backwards along the one it is on.
     *
     * Ground a route covers twice — the stem of a lollipop, an out-and-back, the
     * first metres of a closed loop — gives the same fix two positions, and the
     * windowed projection will faithfully follow whichever it was given, in
     * reverse if need be. Localisation prefers the right one, but a candidate
     * that ends up on the wrong leg anyway would otherwise go on retreating until
     * it is dropped, and a dropped route is only ever re-admitted further along,
     * which is exactly where the wrong leg already is.
     *
     * Only a leg at least as close as a tie allows qualifies. A route's own
     * reverse, run the wrong way round, has no second leg and so is never
     * rescued by this: it is rejected by its direction exactly as before.
     */
    private fun otherLeg(poly: Polyline, here: LatLon, current: Polyline.Hit): Polyline.Hit? {
        val gap = Tuning.SEARCH_WINDOW_M
        val limit = minOf(current.distanceM + Tuning.LOCALISE_TIE_M, Tuning.MATCH_RADIUS_M)
        val ahead = if (current.positionM + gap < poly.lengthM) {
            poly.localise(here, current.positionM + gap, Tuning.LOCALISE_TIE_M, minM = current.positionM + gap)
        } else {
            null
        }
        val behind = if (current.positionM - gap > 0.0) {
            poly.projectWindow(here, 0.0, current.positionM - gap)
        } else {
            null
        }
        return listOfNotNull(ahead, behind)
            .filter { it.distanceM <= limit && kotlin.math.abs(it.positionM - current.positionM) > gap }
            .minByOrNull { it.distanceM }
    }

    /**
     * Bring back a candidate the user has come close to again.
     *
     * The subtlety is that "close to the geometry" is not the same as "back on
     * the route". A route's own reverse occupies exactly the same ground, so a
     * naive re-admission would resurrect it once a second for the whole workout,
     * each time relocated to wherever the user happens to be — and a rival
     * permanently sitting on top of the user is a rival that can never be
     * separated from.
     *
     * So a dropped candidate is re-localised *forwards only*, from where it was
     * last seen. Rejoining a route after a detour means being further along it
     * than when you left it; being further back means you are not on it.
     */
    private fun admitNearby(here: LatLon) {
        if (grid.isEmpty) return
        val near = grid.candidatesNear(here)
        if (near.isEmpty()) return
        var admitted = false
        for (id in orderedIds) {
            if (id !in near) continue
            val c = cands[id] ?: continue
            if (!c.dropped) continue

            val from = c.positionM.coerceAtLeast(0.0)
            // Forwards from where it was, and the first pass rather than the
            // nearest: a lollipop's way home lies forwards too.
            val hit = c.poly.localise(here, from, Tuning.LOCALISE_TIE_M, minM = from)
            if (hit.distanceM > Tuning.MATCH_RADIUS_M) continue

            // Re-admit at a deliberately small weight: rejoining a route after a
            // detour must be possible, but must not disturb a settled belief.
            c.dropped = false
            c.unseenM = 0.0
            c.positionM = hit.positionM
            c.offsetM = hit.distanceM
            c.trackRatio = 1.0
            c.retreatM = 0.0
            c.trail.clear()
            c.trail.addLast(TrailPoint(travelledM, hit.positionM, here))
            c.outOfBandM = 0.0
            // Joined afresh once it is followed again, and compared from there:
            // a route left for longer than [Tuning.CANDIDATE_DROP_M] was a
            // different ride in between. A ride of 25 Jul left from the front
            // door, went thirteen kilometres elsewhere and came home over the
            // first metres of the commute, which then read an hour behind.
            c.joinPosM = -1.0
            c.logW = ln(Tuning.REENTRY_WEIGHT)
            admitted = true
            logRaw(MatchEvent.ADMIT, id)
        }
        if (admitted) normalise()
    }

    private fun dropStale() {
        for (id in orderedIds) {
            val c = cands[id] ?: continue
            if (c.dropped) continue
            if (c.unseenM > Tuning.CANDIDATE_DROP_M) {
                c.dropped = true
                // Left, demonstrably: whatever it is when it comes back, it is
                // no longer the route the user has been on since the start.
                c.fromStart = false
                c.logW = Double.NEGATIVE_INFINITY
                // The focus goes in [updateFocus], which logs the demotion.
                // Note: the deviation detector is deliberately not disarmed here.
                // A committed route being dropped means the user has gone a long
                // way from it, which is the alert's whole subject.
                logRaw(MatchEvent.DROP, id)
            }
        }
    }

    /** Log-sum-exp normalisation across live candidates and the null hypothesis. */
    private fun normalise() {
        var maxLog = nullLogW
        for (id in orderedIds) {
            val c = cands[id] ?: continue
            if (!c.dropped && c.logW > maxLog) maxLog = c.logW
        }
        if (!maxLog.isFinite()) {
            nullLogW = 0.0
            return
        }
        var sum = exp(nullLogW - maxLog)
        for (id in orderedIds) {
            val c = cands[id] ?: continue
            if (!c.dropped) sum += exp(c.logW - maxLog)
        }
        val logSum = maxLog + ln(sum)
        nullLogW -= logSum
        for (id in orderedIds) {
            val c = cands[id] ?: continue
            if (!c.dropped) c.logW -= logSum
        }
    }

    private fun weight(c: Cand): Double = if (c.dropped) 0.0 else exp(c.logW)

    private val nullWeight: Double get() = exp(nullLogW)

    /**
     * Decide which route, if any, the display is about. Returns the event to
     * log when the commitment changed.
     *
     * In order:
     *
     *  1. Nothing is followed, or the null hypothesis dominates: the user is
     *     not on a known route, and nothing is named.
     *  2. The named route stays named while it is followed and stays among the
     *     contenders, allowing [Tuning.FOCUS_RELEASE_M] outside them.
     *  3. Otherwise — and also when a contender outranks it — the best of the
     *     contenders by [rank] is named. Committed if it has been followed
     *     [Tuning.COMMIT_MIN_M] since it was joined; if none has, a route set
     *     off on at the start is named as a guess once it has been followed
     *     [Tuning.PROVISIONAL_MIN_M].
     *
     * Where routes run over the same ground this always names one of them, the
     * way the user has ranked them: their ahead/behind is the same to within
     * what anybody could read off a watch, and the name is the user's own
     * answer to which of them it should be. Where they part, the one the user
     * is not on stops being a contender and the other one is named.
     */
    private fun updateFocus(step: Double): MatchEvent? {
        val current = focusId?.let { cands[it] }
        val band = contenders()
        val before = committedRouteId

        if (current != null) {
            current.outOfBandM = if (band.any { it === current }) 0.0 else current.outOfBandM + step
        }
        // Held through a few seconds of not fitting — a GNSS excursion, a
        // medoid a street's width off — rather than flickering to "new route"
        // and back: the same allowance away from the route as off the band.
        val keep = current != null && !current.dropped &&
            current.unseenM <= Tuning.FOCUS_RELEASE_M && current.outOfBandM <= Tuning.FOCUS_RELEASE_M

        if (nullWeight >= Tuning.NULL_DOMINANT_WEIGHT || (!keep && cands.values.none { it.following })) {
            val was = phase
            setFocus(null, committed = false, byPriority = false)
            phase = MatchPhase.UNKNOWN
            return when {
                before != null -> MatchEvent.DEMOTE
                was != MatchPhase.UNKNOWN -> MatchEvent.NULL_DOMINANT
                else -> null
            }
        }

        // Committed routes first; a guess only when nothing has earned more —
        // chosen among every route set off on, and only then asked whether it
        // has been followed far enough to name, so that the route the user
        // ranked first is the first name shown rather than whichever route's
        // geometry happened to reach the distance a second earlier.
        val eligible = band.filter { it.followedM >= Tuning.COMMIT_MIN_M }
        val held = if (keep) current else null
        val next = if (eligible.isNotEmpty()) {
            choose(eligible, held)
        } else {
            choose(band.filter { it.fromStart }, held)?.takeIf { it.followedM >= Tuning.PROVISIONAL_MIN_M }
        }
        val committed = next != null && next.followedM >= Tuning.COMMIT_MIN_M
        val byPriority = committed && eligible.any { it !== next }
        setFocus(next, committed, byPriority)

        phase = if (committed) MatchPhase.COMMITTED else MatchPhase.SEARCHING
        return when {
            committed && committedRouteId != before -> {
                if (armedRouteId != next!!.routeId) armDeviation(next.routeId)
                if (byPriority) MatchEvent.COMMIT_BY_PRIORITY else MatchEvent.COMMIT
            }
            // The deviation detector stays armed on purpose: a demotion is very
            // often the first symptom of the departure it watches for.
            !committed && before != null -> MatchEvent.DEMOTE
            else -> null
        }
    }

    private fun setFocus(next: Cand?, committed: Boolean, byPriority: Boolean) {
        val previous = focusId?.let { cands[it] }
        if (next !== previous) {
            next?.let { handOver(previous, it) }
            next?.outOfBandM = 0.0
        }
        focusId = next?.routeId
        committedRouteId = if (committed) next?.routeId else null
        committedByPriority = committed && byPriority
    }

    /**
     * Carry ahead/behind across from the route that was named to the one that
     * now is, so that the number goes on from where it was.
     *
     * Two routes over the same street have different usual times for it — they
     * got there by different ways, or are medians of different days — and
     * reading the delta off the new one from scratch made it jump by the
     * difference at every junction. The user's time has not changed there, so
     * neither may the number: it continues from its last value, and from here
     * on moves with the new route's profile.
     */
    private fun handOver(from: Cand?, to: Cand) {
        if (from == null || from.dropped || from.positionM < 0.0) return
        val delta = lastDelta ?: return
        val elapsedS = lastActiveMs / 1000.0
        to.anchorS = delta - elapsedS + to.entry.profile.medianTimeAt(to.positionM)
        to.originS = from.originS
    }

    /**
     * Which of [pool] to name, given the route named now if it is still to be
     * kept.
     *
     * Seniority first: the routes joined earliest — within
     * [Tuning.COMMIT_MIN_M] of the earliest — are the ones that explain the
     * most of today's ride, and only they are considered. The commute home
     * that left "Arbeit zurück Baiersdorf" for six kilometres along the canal
     * and met it again after them is "Arbeit zurück Kanal" all the way, however
     * highly the user ranked the other: naming it there would claim a ride
     * that did not happen.
     *
     * Among those, the user's priority, then the route already named, then the
     * weight, then the lowest id. The named route is only ever replaced by one
     * the user ranked higher, never by a better weight, which on shared ground
     * is GNSS noise — or because it is no longer among the senior ones.
     */
    private fun choose(pool: List<Cand>, current: Cand?): Cand? {
        // Held through a misfit, it competes as if it still fitted.
        val all = if (current != null && current !in pool) pool + current else pool
        if (all.isEmpty()) return null
        val earliest = all.minOf { it.joinTravelM }
        val senior = all.filter { it.joinTravelM <= earliest + Tuning.COMMIT_MIN_M }
        val best = senior.maxWith(
            compareBy<Cand>({ it.entry.priority }, { it === current }, { it.logW })
                .thenByDescending { it.routeId },
        )
        return when {
            current == null || best === current -> best
            current !in senior -> best
            best.entry.priority > current.entry.priority -> best
            else -> current
        }
    }

    /**
     * The routes that currently explain the user's position equally well (US-4).
     *
     * Weights cannot identify these. Two routes sharing a street fit every sample
     * alike, and whichever is a few metres nearer today is nearer because of
     * correlated GNSS bias, not because the user is on it. Geometry can: anchored
     * on whichever followed route the user is closest to, every followed route
     * within [Tuning.COMMIT_SEPARATION_M] of that is indistinguishable from it.
     *
     * Only routes being followed: a route whose geometry happens to pass under
     * the user, but which they are demonstrably not following — the way home at
     * its finish beside the front door, a route walked the wrong way round — is
     * not a rival explanation of the journey.
     *
     * Returned in ascending route id order, so every decision taken from this set
     * is deterministic.
     */
    private fun contenders(): List<Cand> {
        var best = Double.MAX_VALUE
        for (id in orderedIds) {
            val c = cands[id] ?: continue
            if (c.following && c.offsetM < best) best = c.offsetM
        }
        if (best == Double.MAX_VALUE) return emptyList()
        val limit = best + Tuning.COMMIT_SEPARATION_M
        val out = ArrayList<Cand>(4)
        for (id in orderedIds) {
            val c = cands[id] ?: continue
            if (c.following && c.offsetM <= limit) out.add(c)
        }
        return out
    }

    /** Point the detector at a route and clear whatever it was doing before. */
    private fun armDeviation(routeId: Long?) {
        armedRouteId = routeId
        armedPoly = routeId?.let { cands[it]?.poly }
        deviationCount = 0
        deviationArmed = true
        offRoute = false
    }

    /** True on the sample at which the alert fires. */
    private fun updateDeviation(here: LatLon): Boolean {
        val poly = armedPoly ?: return false
        // The distance to the armed route itself, not to wherever the matcher
        // currently believes the user is on it. Once off route those two are
        // different questions, and this is the one the user is asking.
        var offset = poly.projectAll(here).distanceM

        // ...but the alert is about leaving the known route network, not about
        // leaving one particular route, which is why §5.6 takes the minimum
        // across live candidates. Without that, running a learned shortcut off a
        // learned long route buzzes at you for the twenty seconds it takes the
        // shortcut to commit — an alarm for doing something entirely familiar.
        for (id in orderedIds) {
            val c = cands[id] ?: continue
            if (c.dropped || c.routeId == armedRouteId) continue
            if (c.trackRatio < Tuning.MIN_TRACKING_RATIO) continue
            if (c.offsetM < offset) offset = c.offsetM
        }

        if (offset > Tuning.DEVIATION_M) {
            deviationCount++
            if (deviationCount >= Tuning.DEVIATION_HOLD_SAMPLES) {
                offRoute = true
                if (deviationArmed) {
                    deviationArmed = false
                    logRaw(MatchEvent.DEVIATION, armedRouteId)
                    return true
                }
            }
        } else {
            deviationCount = 0
            if (offset < Tuning.DEVIATION_CLEAR_M) {
                if (offRoute) logRaw(MatchEvent.DEVIATION_CLEARED, armedRouteId)
                offRoute = false
                // Re-arm only once genuinely back on the route, so a single
                // wobble at the threshold cannot produce a second alert.
                deviationArmed = true
            }
        }
        return false
    }

    private fun buildState(tOffsetMs: Long, deviationFired: Boolean): MatchState {
        val live = ArrayList<Candidate>()
        for (id in orderedIds) {
            val c = cands[id] ?: continue
            if (c.dropped) continue
            val w = weight(c)
            if (w < 1e-4) continue
            live.add(Candidate(c.routeId, w, c.positionM.coerceAtLeast(0.0), c.offsetM))
        }
        live.sortByDescending { it.weight }

        // How long the projection has stood still. Every number below that
        // depends on *where* the user is depends on this being small.
        val staleMs = if (lastUsableTOffsetMs < 0) 0L
        else (tOffsetMs - lastUsableTOffsetMs).coerceAtLeast(0L)
        val stale = staleMs > Tuning.STALE_POSITION_MS

        val focus = focusId?.let { cands[it] }?.takeIf { !it.dropped && it.positionM >= 0.0 }
        val elapsedS = lastActiveMs / 1000.0
        val delta = when {
            // Frozen, not recomputed: with the position standing still, elapsed
            // time alone would make the user look steadily slower for as long as
            // the GNSS outage lasts. The last value computed against a real
            // position is the honest one, and [positionStaleMs] says so.
            stale -> lastDelta
            focus == null -> null
            else -> elapsedS - (focus.entry.profile.medianTimeAt(focus.positionM) - focus.anchorS)
        }
        lastDelta = delta

        var remaining: Double? = null
        var eta: Double? = null
        var progress: Double? = null
        if (focus != null) {
            val lengthM = focus.entry.geometry.lengthM
            remaining = (lengthM - focus.positionM).coerceAtLeast(0.0)
            if (lengthM > 0.0) progress = (focus.positionM / lengthM).coerceIn(0.0, 1.0)
            // No arrival time off a standing position: an ETA is a claim about
            // the next few minutes, and there is nothing current to make it from.
            if (!stale && delta != null) eta = estimateEta(focus, elapsedS, delta)
        }
        val leader = focus?.let { f -> live.firstOrNull { it.routeId == f.routeId } } ?: live.firstOrNull()

        return MatchState(
            tOffsetMs = tOffsetMs,
            phase = phase,
            committedRouteId = committedRouteId,
            committedByPriority = committedByPriority,
            nullWeight = nullWeight,
            candidates = live,
            travelledM = travelledM,
            deltaVsMedianS = delta,
            remainingM = remaining,
            etaSecondsFromNow = eta,
            leaderRouteId = focus?.routeId ?: leader?.routeId,
            leaderWeight = leader?.weight ?: 0.0,
            contenderCount = contenders().size,
            provisional = committedRouteId == null && focus != null,
            progressFraction = progress,
            deltaScaleS = Tuning.deltaFullScaleS(exerciseType),
            noRoutesKnown = cands.isEmpty(),
            positionStaleMs = staleMs,
            deviationAlert = deviationFired,
            offRoute = offRoute,
            disabled = disabled,
        )
    }

    /**
     * US-6: the route's usual time for what is left, scaled by today's pace,
     * smoothed so the arrival time does not jitter from sample to sample.
     *
     * Today's pace is the user's time over the route's usual time for the same
     * stretch, from where the comparison began ([Cand.originS]) — the start of
     * the workout, or where the route was joined.
     */
    private fun estimateEta(c: Cand, elapsedS: Double, deltaS: Double): Double? {
        val atPos = c.entry.profile.medianTimeAt(c.positionM)
        val total = c.entry.profile.totalMedianTimeS
        val today = elapsedS - c.originS
        val usual = today - deltaS
        if (usual <= 0.0 || total <= atPos) return null
        val kRaw = (today / usual).coerceIn(Tuning.ETA_K_MIN, Tuning.ETA_K_MAX)
        kWindow.addLast(kRaw)
        kSum += kRaw
        while (kWindow.size > Tuning.ETA_SMOOTH_SAMPLES) kSum -= kWindow.removeFirst()
        val k = kSum / kWindow.size
        return (total - atPos) * k
    }

    // ---------------------------------------------------------------------- log

    private fun logEntry(event: MatchEvent, st: MatchState) {
        val l = log ?: return
        l(
            MatchLogEntry(
                tOffsetMs = st.tOffsetMs,
                event = event,
                phase = st.phase,
                nullWeight = round6(st.nullWeight),
                weights = st.candidates.associate { it.routeId to round6(it.weight) },
                committedRouteId = st.committedRouteId,
                travelledM = round2(st.travelledM),
                routeId = st.top?.routeId,
            ),
        )
    }

    private fun logRaw(event: MatchEvent, routeId: Long?) {
        val l = log ?: return
        l(
            MatchLogEntry(
                tOffsetMs = prev?.tOffsetMs ?: 0L,
                event = event,
                phase = phase,
                nullWeight = round6(nullWeight),
                committedRouteId = committedRouteId,
                travelledM = round2(travelledM),
                routeId = routeId,
            ),
        )
    }

    private fun round6(x: Double) = Math.round(x * 1e6) / 1e6
    private fun round2(x: Double) = Math.round(x * 1e2) / 1e2

    // ------------------------------------------------------------------- priors

    companion object {
        private const val NULL_KEY = -1L

        internal fun toPolyline(e: RouteEntry): Polyline {
            val pts = e.geometry.points
            val la = DoubleArray(pts.size)
            val lo = DoubleArray(pts.size)
            val cu = DoubleArray(pts.size)
            for (i in pts.indices) {
                la[i] = pts[i].lat; lo[i] = pts[i].lon; cu[i] = pts[i].cumDistM
            }
            return Polyline(la, lo, cu)
        }

        /**
         * Start-of-session priors from historical frequency by (exercise type,
         * weekday bucket, time-of-day bucket), floored so nothing starts at zero
         * and with [Tuning.NULL_PRIOR_FLOOR] reserved for "unknown" (§5.6).
         */
        internal fun initialPriors(
            entries: List<RouteEntry>,
            startedAtUtcMs: Long,
            tzOffsetMinutes: Int,
        ): Map<Long, Double> {
            val out = HashMap<Long, Double>(entries.size + 1)
            out[NULL_KEY] = Tuning.NULL_PRIOR_FLOOR
            if (entries.isEmpty()) return out

            val ctx = contextKeyOf(entries.first().geometry.exerciseType, startedAtUtcMs, tzOffsetMinutes)
            val scores = entries.map { e ->
                val inCtx = e.prior.byContext[ctx] ?: 0
                inCtx + Tuning.PRIOR_CONTEXT_BLEND * e.prior.totalCount
            }
            val sum = scores.sum()
            val floors = Tuning.ROUTE_PRIOR_FLOOR * entries.size
            val spread = (1.0 - Tuning.NULL_PRIOR_FLOOR - floors).coerceAtLeast(0.0)
            for (i in entries.indices) {
                val share = if (sum <= 0.0) 1.0 / entries.size else scores[i] / sum
                out[entries[i].geometry.routeId] = Tuning.ROUTE_PRIOR_FLOOR + spread * share
            }
            return out
        }

        /** Context key for [RoutePrior.byContext], derived without java.time so the
         *  same arithmetic runs unchanged on the watch. */
        fun contextKeyOf(type: ExerciseType, startedAtUtcMs: Long, tzOffsetMinutes: Int): String {
            val localMs = startedAtUtcMs + tzOffsetMinutes * 60_000L
            val days = Math.floorDiv(localMs, 86_400_000L)
            val msOfDay = Math.floorMod(localMs, 86_400_000L)
            // 1970-01-01 was a Thursday; 0 = Monday.
            val dow = Math.floorMod(days + 3L, 7L).toInt()
            val day = if (dow >= 5) DayBucket.WEEKEND else DayBucket.WEEKDAY
            val time = TimeBucket.of((msOfDay / 3_600_000L).toInt())
            return RoutePrior.key(type, day, time)
        }
    }
}
