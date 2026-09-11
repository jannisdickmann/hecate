package de.hecate.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the display is allowed to claim (US-4). */
@Serializable
enum class MatchPhase {
    /** Not enough evidence yet, or several routes still plausible. Shows "Suche…". */
    SEARCHING,

    /** One route dominates and has held. Shows its name. */
    COMMITTED,

    /** The null hypothesis dominates. Shows "Unbekannt". */
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
    val nullWeight: Double = 1.0,
    val candidates: List<Candidate> = emptyList(),
    val travelledM: Double = 0.0,
    /** US-5: elapsed active time minus the pooled median time at this position.
     *  Positive means slower than usual. Available while uncommitted. */
    val deltaVsMedianS: Double? = null,
    /** US-6: only once committed. */
    val remainingM: Double? = null,
    /** US-6: seconds from now until the predicted finish. Only once committed. */
    val etaSecondsFromNow: Double? = null,
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
    DEMOTE,
    NULL_DOMINANT,
    DEVIATION,
    DEVIATION_CLEARED,
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
