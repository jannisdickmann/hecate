package de.hecate.core.matcher

import de.hecate.core.Tuning
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.geo.Polyline
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
 * The matcher holds a weight per plausible route plus a null hypothesis, and
 * never "chooses" internally. [MatchState.phase] tells the display what it may
 * honestly claim; everything else is advisory.
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
        /** Smoothed advance along this route per metre actually travelled.
         *  About 1 while the route is being followed, about -1 for its reverse. */
        var trackRatio: Double = 1.0,
        var dropped: Boolean = false,
    ) {
        val routeId: Long get() = entry.geometry.routeId
    }

    /** Candidates by route id, iterated in ascending id order for determinism. */
    private val cands = LinkedHashMap<Long, Cand>()
    private val orderedIds: List<Long>

    private var nullLogW: Double
    private var travelledM = 0.0
    private var prev: Sample? = null
    private var lastActiveMs = 0L

    private var commitHold = 0
    private var committedRouteId: Long? = null
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
    fun update(sample: Sample): MatchState {
        if (disabled) return MatchState(sample.tOffsetMs, phase, committedRouteId, disabled = true)
        lastActiveMs = sample.activeMs
        if (!sample.usable) {
            if (sample.paused) prev = null // do not measure travel across a pause
            return buildState(sample.tOffsetMs, deviationFired = false)
        }
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

        // A dropout invalidates dead reckoning: every candidate must re-localise
        // over its whole geometry rather than inside a window around a stale guess.
        if (dropout) {
            for (id in orderedIds) {
                val c = cands[id] ?: continue
                if (!c.dropped) c.positionM = -1.0
            }
        }

        admitNearby(here)

        val sigma = max(Tuning.GPS_SIGMA_M, sample.accuracyM ?: 0.0)
        val twoSigmaSq = 2.0 * sigma * sigma

        for (id in orderedIds) {
            val c = cands[id] ?: continue
            if (c.dropped) continue

            val previousPos = c.positionM
            val hit = if (previousPos < 0.0) {
                c.poly.projectAll(here)
            } else {
                // Asymmetric by design: a long reach forwards for uncertainty in
                // the travelled distance, almost none backwards.
                c.poly.projectWindow(
                    here,
                    previousPos - Tuning.BACKTRACK_SLACK_M,
                    previousPos + step + Tuning.SEARCH_WINDOW_M,
                )
            }
            c.positionM = hit.positionM
            c.offsetM = hit.distanceM

            if (previousPos >= 0.0 && step > 0.5 && p != null && !dropout) {
                val ratio = ((hit.positionM - previousPos) / step).coerceIn(-2.0, 2.0)
                c.trackRatio = Tuning.TRACK_RATIO_SMOOTHING * c.trackRatio +
                    (1 - Tuning.TRACK_RATIO_SMOOTHING) * ratio
            }
            val onRoute = hit.distanceM <= Tuning.MATCH_RADIUS_M &&
                c.trackRatio >= Tuning.MIN_TRACKING_RATIO
            c.unseenM = if (onRoute) 0.0 else c.unseenM + step

            // Emission: how well the sample sits on this route's geometry.
            var ll = -(hit.distanceM * hit.distanceM) / twoSigmaSq
            // Transition: whether moving along this route by that much is
            // consistent with how far the user demonstrably went. Skipped when
            // there is no usable previous position — after a dropout or a pause
            // the two are not comparable.
            if (previousPos >= 0.0 && p != null && !dropout) {
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

        val fired = updateCommitment()
        val devFired = updateDeviation(here)

        val st = buildState(sample.tOffsetMs, devFired)
        logEntry(if (fired != null) fired else if (devFired) MatchEvent.DEVIATION else MatchEvent.SAMPLE, st)
        return st
    }

    // ------------------------------------------------------------------ internals

    private fun clampLL(x: Double): Double = max(-Tuning.MAX_ABS_LOG_LIK, x)

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
            val hit = c.poly.projectWindow(here, from, c.poly.lengthM)
            if (hit.distanceM > Tuning.MATCH_RADIUS_M) continue

            // Re-admit at a deliberately small weight: rejoining a route after a
            // detour must be possible, but must not disturb a settled belief.
            c.dropped = false
            c.unseenM = 0.0
            c.positionM = hit.positionM
            c.offsetM = hit.distanceM
            c.trackRatio = 1.0
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
                c.logW = Double.NEGATIVE_INFINITY
                if (committedRouteId == id) {
                    committedRouteId = null
                    commitHold = 0
                    phase = MatchPhase.SEARCHING
                }
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

    private fun topCandidate(): Cand? {
        var best: Cand? = null
        for (id in orderedIds) {
            val c = cands[id] ?: continue
            if (c.dropped) continue
            if (best == null || c.logW > best.logW) best = c
        }
        return best
    }

    /** Returns the event to log if commitment state changed. */
    private fun updateCommitment(): MatchEvent? {
        val top = topCandidate()
        val topW = top?.let { weight(it) } ?: 0.0

        if (nullWeight >= Tuning.NULL_DOMINANT_WEIGHT) {
            val was = phase
            if (committedRouteId != null) {
                committedRouteId = null
                commitHold = 0
                phase = MatchPhase.UNKNOWN
                return MatchEvent.DEMOTE
            }
            phase = MatchPhase.UNKNOWN
            return if (was != MatchPhase.UNKNOWN) MatchEvent.NULL_DOMINANT else null
        }

        if (committedRouteId != null) {
            if (topW < Tuning.DEMOTE_WEIGHT || top == null || top.routeId != committedRouteId) {
                committedRouteId = null
                commitHold = 0
                phase = MatchPhase.SEARCHING
                // The detector stays armed on purpose: a demotion is very often
                // the first symptom of the departure it is watching for.
                return MatchEvent.DEMOTE
            }
            phase = MatchPhase.COMMITTED
            return null
        }

        phase = MatchPhase.SEARCHING
        if (top != null && topW >= Tuning.COMMIT_WEIGHT && isSeparated(top)) {
            commitHold++
            if (commitHold >= Tuning.COMMIT_HOLD_SAMPLES && travelledM >= Tuning.COMMIT_MIN_M) {
                committedRouteId = top.routeId
                phase = MatchPhase.COMMITTED
                if (armedRouteId != top.routeId) armDeviation(top.routeId)
                return MatchEvent.COMMIT
            }
        } else {
            commitHold = 0
        }
        return null
    }

    /**
     * True when no other live candidate is close enough to the current position to
     * be an equally good explanation of it (US-4).
     *
     * Weights cannot answer this question. Two routes sharing a street fit every
     * sample equally well, and whichever is a few metres nearer today wins on
     * correlated noise alone. Geometry can: while a rival is still hugging the
     * same ground, nothing is decided.
     */
    private fun isSeparated(top: Cand): Boolean {
        for (id in orderedIds) {
            val c = cands[id] ?: continue
            if (c.dropped || c === top) continue
            // Not tracking means not a rival: a route whose geometry happens to
            // pass under the user, but which they are demonstrably not following,
            // says nothing about whether the leader is ambiguous.
            if (c.trackRatio < Tuning.MIN_TRACKING_RATIO) continue
            if (c.offsetM <= top.offsetM + Tuning.COMMIT_SEPARATION_M) return false
        }
        return true
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
        // The distance to the route itself, not to wherever the matcher currently
        // believes the user is on it. Once off route those two are different
        // questions, and this is the one the user is asking.
        val offset = poly.projectAll(here).distanceM

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

        val elapsedS = lastActiveMs / 1000.0
        val pooledMedian = pooledMedianTimeAtPosition()
        val delta = if (phase == MatchPhase.UNKNOWN || pooledMedian == null) null
        else elapsedS - pooledMedian

        var remaining: Double? = null
        var eta: Double? = null
        if (phase == MatchPhase.COMMITTED) {
            val c = committedRouteId?.let { cands[it] }
            if (c != null && c.positionM >= 0.0) {
                remaining = (c.entry.geometry.lengthM - c.positionM).coerceAtLeast(0.0)
                eta = estimateEta(c, elapsedS)
            }
        }

        return MatchState(
            tOffsetMs = tOffsetMs,
            phase = phase,
            committedRouteId = committedRouteId,
            nullWeight = nullWeight,
            candidates = live,
            travelledM = travelledM,
            deltaVsMedianS = delta,
            remainingM = remaining,
            etaSecondsFromNow = eta,
            deviationAlert = deviationFired,
            offRoute = offRoute,
            disabled = disabled,
        )
    }

    /**
     * US-5: the pooled median time at the current position, averaged over every
     * still-plausible candidate. Candidates that share the traversed prefix share
     * its history, so this is meaningful long before the route is identified.
     */
    private fun pooledMedianTimeAtPosition(): Double? {
        var wSum = 0.0
        var acc = 0.0
        for (id in orderedIds) {
            val c = cands[id] ?: continue
            if (c.dropped || c.positionM < 0.0) continue
            val w = weight(c)
            if (w < Tuning.LIVE_CANDIDATE_MIN_WEIGHT) continue
            val m = c.entry.profile.medianTimeAt(c.positionM)
            if (m <= 0.0) continue
            acc += w * m
            wSum += w
        }
        return if (wSum <= 0.0) null else acc / wSum
    }

    /**
     * US-6: remaining median time scaled by today's performance, smoothed so the
     * arrival time does not jitter from sample to sample.
     */
    private fun estimateEta(c: Cand, elapsedS: Double): Double? {
        val atPos = c.entry.profile.medianTimeAt(c.positionM)
        val total = c.entry.profile.totalMedianTimeS
        if (atPos <= 0.0 || total <= atPos) return null
        val kRaw = (elapsedS / atPos).coerceIn(Tuning.ETA_K_MIN, Tuning.ETA_K_MAX)
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
