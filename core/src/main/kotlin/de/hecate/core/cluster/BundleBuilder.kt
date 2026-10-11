package de.hecate.core.cluster

import de.hecate.core.Tuning
import de.hecate.core.geo.Polyline
import de.hecate.core.geo.Track
import de.hecate.core.matcher.RouteMatcher
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.RouteBundle
import de.hecate.core.model.RouteEntry
import de.hecate.core.model.RouteGeometry
import de.hecate.core.model.RoutePoint
import de.hecate.core.model.RoutePrior
import de.hecate.core.model.Sample
import de.hecate.core.profile.ProfileBuilder

/**
 * Turns a set of session tracks into the routes the phone stores and the bundle
 * the watch consumes: cluster, pick medoids, build profiles, count priors.
 *
 * Lives in `:core` so the replay harness produces exactly the bundle the phone
 * would, without an Android runtime in the loop.
 */
object BundleBuilder {

    /** A route as computed by a clustering pass, before it is reconciled with the
     *  routes already in the database. */
    data class ComputedRoute(
        val memberSessionIds: List<String>,
        val medoidSessionId: String,
        val exerciseType: ExerciseType,
        val geometry: List<RoutePoint>,
        val lengthM: Double,
        val profile: List<Double>,
        val p25: List<Double>,
        val p75: List<Double>,
        val medianHr: List<Double?>,
        val medianAltM: List<Double?>,
        val prior: RoutePrior,
        /** A route the user asked for before the history had one: see
         *  [computeRoutes]'s `temporarySeeds`. Its geometry and profile are its
         *  seed's alone, and [medoidSessionId] is that seed. */
        val temporary: Boolean = false,
    )

    /**
     * Cluster [tracks] and build everything that depends on the result.
     *
     * Route ids are *not* assigned here: identity across re-clustering is the
     * phone's business (it has to preserve user-given names), so this returns
     * clusters in a deterministic order and the caller maps them onto rows.
     *
     * [temporarySeeds] are recordings the user promoted to a temporary route
     * before the history had enough of them to form one. A cluster too small to
     * be a route that holds a seed becomes one anyway, flagged [ComputedRoute.temporary]:
     * every member is filed under it, but its geometry and profile are the
     * earliest seed's alone — that recording *is* the route, the others merely
     * match it — so a cluster never yields two temporary routes. A seed in a
     * cluster big enough to be a route is simply a member of that route: the
     * history has caught up and the promotion has nothing left to do. Which
     * seeds still stand is therefore exactly the medoids of the temporary
     * routes returned, and the phone keeps no other.
     */
    fun computeRoutes(
        tracks: List<Track>,
        hrBySession: Map<String, List<Sample>> = emptyMap(),
        minMembers: Int = Tuning.MIN_MEMBERS,
        temporarySeeds: Set<String> = emptySet(),
    ): List<ComputedRoute> {
        if (tracks.isEmpty()) return emptyList()
        val byId = tracks.associateBy { it.sessionId }
        val result = RouteClusterer.cluster(tracks, minMembers = minMembers)

        return result.clusters.mapNotNull { cluster ->
            // Members are in start-time order, so the first seed is the
            // earliest recording the user promoted.
            val seed = if (cluster.active) {
                null
            } else {
                cluster.memberSessionIds.firstOrNull { it in temporarySeeds } ?: return@mapNotNull null
            }
            val medoidId = seed ?: cluster.medoidSessionId
            val medoid = byId[medoidId] ?: return@mapNotNull null
            val members = cluster.memberSessionIds.mapNotNull { byId[it] }
            if (members.isEmpty()) return@mapNotNull null

            val geometry = medoid.polyline
            if (geometry.size < 2) return@mapNotNull null

            val profile = ProfileBuilder.build(
                routeId = 0L,
                geometry = geometry,
                members = if (seed != null) listOf(medoid) else members,
                hrBySession = hrBySession,
            )

            ComputedRoute(
                memberSessionIds = cluster.memberSessionIds,
                medoidSessionId = medoidId,
                temporary = seed != null,
                exerciseType = medoid.exerciseType,
                geometry = toRoutePoints(geometry),
                lengthM = geometry.lengthM,
                profile = profile.medianCumTimeS,
                p25 = profile.p25S,
                p75 = profile.p75S,
                medianHr = profile.medianHr,
                medianAltM = profile.medianAltM,
                prior = priorOf(members),
            )
        }
    }

    /** Historical frequency of a route, split into the buckets the matcher's
     *  start-of-session prior is conditioned on (§5.6). */
    fun priorOf(members: List<Track>): RoutePrior {
        val counts = HashMap<String, Int>()
        for (m in members) {
            val key = RouteMatcher.contextKeyOf(m.exerciseType, m.startedAtUtcMs, m.tzOffsetMinutes)
            counts.merge(key, 1, Int::plus)
        }
        return RoutePrior(totalCount = members.size, byContext = counts)
    }

    fun toRoutePoints(p: Polyline): List<RoutePoint> =
        (0 until p.size).map { RoutePoint(p.lat[it], p.lon[it], p.cum[it]) }

    /** Kept under this name because the phone calls it; see [Polyline.fromRoutePoints]. */
    fun toPolyline(points: List<RoutePoint>): Polyline = Polyline.fromRoutePoints(points)

    /** Assemble the bundle pushed to the watch. */
    fun bundle(version: Long, builtAtUtcMs: Long, entries: List<RouteEntry>): RouteBundle =
        RouteBundle(version, builtAtUtcMs, entries.sortedBy { it.geometry.routeId })

    /** Convenience for tests and the replay harness: cluster and bundle in one go,
     *  assigning ids 1..n in cluster order. */
    fun bundleFrom(
        tracks: List<Track>,
        hrBySession: Map<String, List<Sample>> = emptyMap(),
        version: Long = 1,
        builtAtUtcMs: Long = 0,
        namer: (Int, ComputedRoute) -> String = { i, _ -> "Route $i" },
    ): RouteBundle {
        val computed = computeRoutes(tracks, hrBySession)
        val entries = computed.mapIndexed { idx, r ->
            val id = (idx + 1).toLong()
            RouteEntry(
                geometry = RouteGeometry(id, namer(idx + 1, r), r.exerciseType, r.geometry),
                profile = de.hecate.core.model.RouteProfile(
                    routeId = id,
                    binM = Tuning.RESAMPLE_M,
                    medianCumTimeS = r.profile,
                    p25S = r.p25,
                    p75S = r.p75,
                    medianHr = r.medianHr,
                    medianAltM = r.medianAltM,
                ),
                prior = r.prior,
            )
        }
        return bundle(version, builtAtUtcMs, entries)
    }
}
