package de.hecate.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the display is allowed to claim (US-4).
 *
 * The phase is the *ceiling* on the claim, not the claim itself: what the watch
 * actually says is decided in [de.hecate.core.display.routeBelief], which reads
 * the leader and the contender count as well. That is how `SEARCHING` became a
 * named favourite or a pair of names instead of a spinner (A.27).
 */
@Serializable
enum class MatchPhase {
    /** Not enough evidence yet, or several routes still plausible. May name the
     *  leading route, marked as provisional, but never commits to it. */
    SEARCHING,

    /** One route dominates and has held. Shows its name. */
    COMMITTED,

    /** The null hypothesis dominates: today's track is not one of the learned
     *  routes. Shown as "Neue Strecke" — a finding, not a failure. */
    UNKNOWN,
}

/** A candidate route and where the matcher believes the user is on it. */
@Serializable
data class Candidate(
    val routeId: Long,
    val weight: Double,
    /** Distance along the route [m] of the current projected position. */
    val positionM: Double,
    /** Perpendicular distance [m] from the sample to the route. */
    val offsetM: Double,
)

/**
 * Everything the matcher believes after one sample. Purely descriptive: the
 * matcher never picks a winner internally, the display layer reads [phase]
 * and decides what to show (§5.6).
 *
 * Route-relative numbers are null when they are not meaningful, never zero.
 */
@Serializable
data class MatchState(
    val tOffsetMs: Long,
    val phase: MatchPhase,
    val committedRouteId: Long? = null,
    /** True when the commitment rests on the user's manual route priority rather
     *  than on evidence: the routes coincided, and the user had already said
     *  which one to assume. */
    val committedByPriority: Boolean = false,
    val nullWeight: Double = 1.0,
    val candidates: List<Candidate> = emptyList(),
    val travelledM: Double = 0.0,
    /** US-5: elapsed active time minus the pooled median time at this position.
     *  Positive means slower than usual. Available while uncommitted. */
    val deltaVsMedianS: Double? = null,
    /** US-6: the committed route, or the leading one when [provisional]. */
    val remainingM: Double? = null,
    /** US-6: seconds from now until the predicted finish. Same source as
     *  [remainingM], so the two are always about the same route. */
    val etaSecondsFromNow: Double? = null,
    /**
     * The route currently explaining the samples best, committed or not.
     *
     * The matcher has always known this; it used to keep it to itself until a
     * commitment, which left the display saying "searching" over a belief that
     * had a clear favourite. Reported now so the watch can name it — marked as
     * provisional — rather than claim ignorance (US-4, A.27).
     */
    val leaderRouteId: Long? = null,
    /** [leaderRouteId]'s normalised weight, 0 when there is no leader. */
    val leaderWeight: Double = 0.0,
    /** How many routes are currently indistinguishable from the nearest one.
     *  Two or more means the display should say so instead of picking. */
    val contenderCount: Int = 0,
    /** True when [remainingM], [etaSecondsFromNow] and [progressFraction] come
     *  from [leaderRouteId] rather than from a commitment. They are then a best
     *  guess and the display must show them as one. */
    val provisional: Boolean = false,
    /** How far along the route the user is, 0..1, from the same route as
     *  [remainingM]. Null when no route is being followed. */
    val progressFraction: Double? = null,
    /** Full-scale deflection for an ahead/behind indicator [s]: one fixed
     *  number for the sport ([de.hecate.core.Tuning.deltaFullScaleS]), so the
     *  bar never rescales underneath the user. */
    val deltaScaleS: Double? = null,
    /** The bundle holds no route of this exercise type at all. Different from
     *  [MatchPhase.UNKNOWN], which means the user is off every route there is —
     *  the first is a fact about the phone, the second about today. */
    val noRoutesKnown: Boolean = false,
    /** How long the projected position has gone unrefreshed, in session time.
     *  Past [Tuning.STALE_POSITION_MS][de.hecate.core.Tuning.STALE_POSITION_MS]
     *  everything route-relative here is the last honest value, not a current
     *  one. */
    val positionStaleMs: Long = 0,
    /** True on the single sample at which the deviation alert fires (US-7). */
    val deviationAlert: Boolean = false,
    /** True while the user is considered off-route. */
    val offRoute: Boolean = false,
    /** Set once the matcher has given up (repeated exceptions upstream). */
    val disabled: Boolean = false,
) {
    val top: Candidate? get() = candidates.maxByOrNull { it.weight }

    companion object {
        fun searching(tOffsetMs: Long) = MatchState(tOffsetMs, MatchPhase.SEARCHING)
    }
}

/** Why a log entry was written. */
@Serializable
enum class MatchEvent {
    SAMPLE,
    ADMIT,
    DROP,
    COMMIT,
    /** Committed because the user ranked this route above the others it was
     *  indistinguishable from, not because the evidence chose it. */
    COMMIT_BY_PRIORITY,
    DEMOTE,
    NULL_DOMINANT,
    DEVIATION,
    DEVIATION_CLEARED,
    ;

    /** True for both ways of arriving at a route, so that a reader asking "did
     *  this session ever settle on something" cannot answer it for one of them
     *  and forget the other. */
    val isCommit: Boolean get() = this == COMMIT || this == COMMIT_BY_PRIORITY
}

/**
 * US-10: one line of the per-session match log. The replay harness reproduces
 * exactly this sequence offline; any divergence from what the watch logged is a
 * bug, by definition.
 */
@Serializable
data class MatchLogEntry(
    @SerialName("t") val tOffsetMs: Long,
    @SerialName("e") val event: MatchEvent,
    @SerialName("p") val phase: MatchPhase,
    @SerialName("n") val nullWeight: Double,
    @SerialName("w") val weights: Map<Long, Double> = emptyMap(),
    @SerialName("c") val committedRouteId: Long? = null,
    @SerialName("d") val travelledM: Double = 0.0,
    @SerialName("r") val routeId: Long? = null,
    @SerialName("m") val note: String? = null,
)
