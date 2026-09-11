package de.hecate.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A point on a resampled polyline: position plus distance from the start. */
@Serializable
data class RoutePoint(
    @SerialName("lat") val lat: Double,
    @SerialName("lon") val lon: Double,
    @SerialName("d") val cumDistM: Double,
)

/**
 * The geometry of a learned route: the medoid member's track, resampled to
 * [de.hecate.core.Tuning.RESAMPLE_M]. Not an average of its members — averaging
 * polylines under GNSS noise is the fiddliest piece of the design and buys
 * nothing the matcher needs (§5.7).
 */
@Serializable
data class RouteGeometry(
    val routeId: Long,
    val name: String,
    val exerciseType: ExerciseType,
    val points: List<RoutePoint>,
) {
    val lengthM: Double get() = points.lastOrNull()?.cumDistM ?: 0.0
    val size: Int get() = points.size
}

/**
 * Per-position statistics over a route's members, binned every
 * [de.hecate.core.Tuning.RESAMPLE_M] along the route.
 *
 * [medianCumTimeS] is the median *active* elapsed time at which members reached
 * bin i. p25/p75 are carried for later use (band display, confidence) and are not
 * consumed by v1's matcher.
 */
@Serializable
data class RouteProfile(
    val routeId: Long,
    val binM: Double,
    val medianCumTimeS: List<Double>,
    val p25S: List<Double> = emptyList(),
    val p75S: List<Double> = emptyList(),
    val medianHr: List<Double?> = emptyList(),
) {
    val totalMedianTimeS: Double get() = medianCumTimeS.lastOrNull() ?: 0.0

    /** Median cumulative time at an arbitrary distance along the route, linearly
     *  interpolated between bins and clamped at both ends. */
    fun medianTimeAt(distM: Double): Double {
        if (medianCumTimeS.isEmpty()) return 0.0
        if (distM <= 0.0) return medianCumTimeS.first()
        val x = distM / binM
        val i = x.toInt()
        if (i >= medianCumTimeS.lastIndex) return medianCumTimeS.last()
        val f = x - i
        return medianCumTimeS[i] * (1 - f) + medianCumTimeS[i + 1] * f
    }
}

/** Coarse buckets the start-of-session prior is conditioned on (§5.6). */
@Serializable
enum class DayBucket { WEEKDAY, WEEKEND }

@Serializable
enum class TimeBucket { NIGHT, EARLY, MORNING, MIDDAY, AFTERNOON, EVENING;
    companion object {
        /** Local hour of day (0..23) to bucket. */
        fun of(hour: Int): TimeBucket = when (hour) {
            in 0..4 -> NIGHT
            in 5..7 -> EARLY
            in 8..10 -> MORNING
            in 11..13 -> MIDDAY
            in 14..17 -> AFTERNOON
            else -> EVENING
        }
    }
}

/** How often a route was taken, split by context, for the start-of-session prior. */
@Serializable
data class RoutePrior(
    val totalCount: Int,
    val byContext: Map<String, Int> = emptyMap(),
) {
    companion object {
        fun key(type: ExerciseType, day: DayBucket, time: TimeBucket): String =
            "${type.name}|${day.name}|${time.name}"
    }
}

/** One route as shipped to the watch. */
@Serializable
data class RouteEntry(
    val geometry: RouteGeometry,
    val profile: RouteProfile,
    val prior: RoutePrior,
    /**
     * The user's own ranking of this route, 1..10.
     *
     * Purely a tie-breaker, and only where the evidence cannot break the tie
     * itself: while two routes share the same ground they explain every sample
     * equally well, so the matcher will not choose between them. If exactly one
     * of them outranks the rest, the user has already answered the question and
     * the matcher takes their word for it.
     *
     * It does not touch the probabilistic weights. A high priority cannot make
     * the matcher believe you are on a route you are demonstrably not on; it only
     * decides which of several equally good explanations to show.
     */
    val priority: Int = de.hecate.core.Tuning.DEFAULT_ROUTE_PRIORITY,
)

/**
 * The read-only snapshot of everything the watch knows about routes. Built on the
 * phone after each ingest, pushed over the Data Layer, and cached on the watch.
 * The watch never modifies it (§5.1).
 */
@Serializable
data class RouteBundle(
    val version: Long,
    val builtAtUtcMs: Long,
    val routes: List<RouteEntry>,
) {
    companion object {
        val EMPTY = RouteBundle(version = 0, builtAtUtcMs = 0, routes = emptyList())
    }
}
