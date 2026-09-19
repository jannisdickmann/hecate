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
    /** Median altitude [m] over the members at each bin, null where no member
     *  recorded one. The route's elevation profile: a property of the ground
     *  rather than of anybody's day, which is why it is a median over members
     *  and not taken from the medoid alone. */
    val medianAltM: List<Double?> = emptyList(),
) {
    val totalMedianTimeS: Double get() = medianCumTimeS.lastOrNull() ?: 0.0

    /**
     * Full-scale deflection for an ahead/behind indicator [s]: how far off the
     * median this route's own history says is a lot.
     *
     * The interquartile spread at the finish, doubled, floored by a fraction of
     * the median total time for a route whose members barely differ, and clamped
     * (`Tuning.DELTA_SCALE_*`). Lives here rather than on the watch because it is
     * a property of the route — the same number belongs on any screen that ever
     * draws this comparison.
     */
    fun deltaFullScaleS(): Double {
        val iqr = ((p75S.lastOrNull() ?: 0.0) - (p25S.lastOrNull() ?: 0.0)).coerceAtLeast(0.0)
        return maxOf(
            de.hecate.core.Tuning.DELTA_SCALE_IQR_MULTIPLE * iqr,
            de.hecate.core.Tuning.DELTA_SCALE_MIN_FRACTION * totalMedianTimeS,
        ).coerceIn(de.hecate.core.Tuning.DELTA_SCALE_MIN_S, de.hecate.core.Tuning.DELTA_SCALE_MAX_S)
    }

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
     * The user's own ranking of this route, −2..2, zero being "no opinion".
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
    /**
     * Every exercise type, most-recorded first, so the watch can put the one the
     * user actually does at the top of the start screen.
     *
     * Counted from [RoutePrior.totalCount], which is how often each route has
     * been taken — the only history the watch has, since the bundle is all the
     * phone ever sends it. That makes this an undercount rather than a census:
     * a recording that never matched a route belongs to no route and is counted
     * nowhere. It is the right shape of answer anyway, because a type the user
     * has no routes for is a type they have no history on the watch for either.
     *
     * Always every type, never only the ones with a count: the picker has to
     * offer a first ski tour before there is a ski route to notice. Ties and
     * zeroes keep the enum's own order, which is a stable sort, so a watch with
     * no bundle at all shows exactly the list it always showed.
     */
    fun typesByFrequency(): List<ExerciseType> {
        val counts = routes.groupingBy { it.geometry.exerciseType }
            .fold(0) { total, entry -> total + entry.prior.totalCount }
        return ExerciseType.entries.sortedByDescending { counts[it] ?: 0 }
    }

    companion object {
        val EMPTY = RouteBundle(version = 0, builtAtUtcMs = 0, routes = emptyList())
    }
}
