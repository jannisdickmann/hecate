package de.hecate.phone.work

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.hecate.core.Tuning
import de.hecate.core.cluster.BundleBuilder
import de.hecate.core.cluster.CoverageMetric
import de.hecate.core.cluster.RouteNamer
import de.hecate.core.geo.LatLon
import de.hecate.core.geo.Track
import de.hecate.core.heat.Heatmap
import de.hecate.core.heat.HeatmapCodec
import de.hecate.core.heat.HeatmapGrid
import de.hecate.core.heat.RouteTrack
import de.hecate.core.heat.SessionTrack
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.RouteEntry
import de.hecate.core.model.RouteGeometry
import de.hecate.core.model.RoutePoint
import de.hecate.core.model.RouteProfile
import de.hecate.core.model.Sample
import de.hecate.core.model.SessionEndReason
import de.hecate.core.model.SessionFooter
import de.hecate.core.model.SessionHeader
import de.hecate.phone.AppGraph
import de.hecate.phone.db.HeatmapEntity
import de.hecate.phone.db.RouteEntity
import de.hecate.phone.db.RouteMemberEntity
import de.hecate.phone.db.RoutePointEntity
import de.hecate.phone.db.RouteProfileEntity
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * §5.4 / §5.7: after each ingest, re-run the clusterer over every session,
 * rebuild profiles, rebuild the bundle and push it to the watch.
 *
 * Recomputed from scratch each time. O(n²) over a few hundred sessions is
 * milliseconds, and the alternative — incremental clustering — is deferred (§8)
 * precisely because it would trade that for a class of bugs nobody can see.
 *
 * The one thing that is *not* recomputed is route identity. A route the user has
 * named "Weg zur Arbeit" must stay that route across re-clustering, so freshly
 * computed clusters are matched onto existing rows by member overlap before
 * anything is written.
 */
class ClusterWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        AppGraph.init(applicationContext)
        return runCatching { recluster() }
            .onFailure { Log.e(TAG, "Clustering fehlgeschlagen", it) }
            .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
    }

    private suspend fun recluster() {
        val db = AppGraph.db
        val sessionDao = db.sessions()
        val routeDao = db.routes()

        val sessions = sessionDao.all()
        if (sessions.isEmpty()) return

        val tracks = ArrayList<Track>(sessions.size)
        val hrBySession = HashMap<String, List<Sample>>(sessions.size)
        val localHourBySession = HashMap<String, Int>(sessions.size)

        for (s in sessions) {
            val samples = sessionDao.samples(s.id).map {
                Sample(
                    tOffsetMs = it.tOffsetMs,
                    activeMs = it.activeMs,
                    lat = it.lat,
                    lon = it.lon,
                    altM = it.altM,
                    hrBpm = it.hrBpm,
                    accuracyM = it.accuracyM,
                    paused = it.paused,
                )
            }
            if (samples.size < 2) continue
            val recorded = RecordedSession(
                header = SessionHeader(
                    sessionId = s.id,
                    startedAtUtcMs = s.startedAtUtc,
                    tzOffsetMinutes = s.tzOffsetMinutes,
                    exerciseType = s.exerciseType,
                    source = s.source,
                ),
                samples = samples,
                footer = SessionFooter(
                    endedAtUtcMs = s.startedAtUtc + s.activeDurationMs,
                    activeDurationMs = s.activeDurationMs,
                    totalDistanceM = s.totalDistanceM,
                    reason = SessionEndReason.USER_STOPPED,
                ),
            )
            Track.of(recorded)?.let {
                tracks.add(it)
                // The same repaired clock the track was built on: the profile
                // looks a member's heart rate up *by time*, so a lookup list on
                // the recorded clock and a track on the derived one would slide
                // the heart rates along the route. See `ActiveClock`.
                hrBySession[s.id] = recorded.timedSamples
                localHourBySession[s.id] =
                    (((s.startedAtUtc + s.tzOffsetMinutes * 60_000L) % 86_400_000L) / 3_600_000L).toInt()
            }
        }
        if (tracks.isEmpty()) return

        val trackById = tracks.associateBy { it.sessionId }
        val computed = BundleBuilder.computeRoutes(tracks, hrBySession)
        Log.i(TAG, "${tracks.size} Sitzungen -> ${computed.size} aktive Routen")

        val existing = routeDao.all()
        val existingMembers = routeDao.allMembers().groupBy({ it.routeId }, { it.sessionId })
        val assignment = RouteReconciler.assign(computed, existing, existingMembers)

        db.withTransaction {
            // Routes that no longer correspond to any cluster are deactivated
            // rather than deleted: their name and their history of having existed
            // are worth more than the row is worth reclaiming.
            val keptIds = assignment.values.toSet()
            for (r in existing) {
                if (r.id !in keptIds && r.active) {
                    routeDao.upsert(r.copy(active = false))
                    // Sessions must not keep pointing at a route that no longer
                    // describes them; they will be re-matched below if they
                    // landed in some other cluster.
                    sessionDao.clearMatchesFor(r.id)
                }
            }

            computed.forEachIndexed { index, cluster ->
                val existingId = assignment[index]
                val prior = existing.firstOrNull { it.id == existingId }
                val hours = cluster.memberSessionIds.mapNotNull { localHourBySession[it] }
                val ordinal = existingId ?: (existing.maxOfOrNull { it.id } ?: 0L) + index + 1

                val row = RouteEntity(
                    id = existingId ?: 0L,
                    name = if (prior?.nameIsCustom == true) {
                        prior.name
                    } else {
                        RouteNamer.autoName(ordinal, cluster.exerciseType, hours, cluster.lengthM)
                    },
                    nameIsCustom = prior?.nameIsCustom ?: false,
                    exerciseType = cluster.exerciseType,
                    lengthM = cluster.lengthM,
                    memberCount = cluster.memberSessionIds.size,
                    medoidSessionId = cluster.medoidSessionId,
                    createdAt = prior?.createdAt ?: System.currentTimeMillis(),
                    active = true,
                    // Like the name, the user's ranking is theirs and survives
                    // every re-clustering pass.
                    priority = prior?.priority ?: Tuning.DEFAULT_ROUTE_PRIORITY,
                )
                val routeId = if (existingId != null) {
                    routeDao.upsert(row)
                    existingId
                } else {
                    routeDao.insert(row)
                }

                routeDao.clearPoints(routeId)
                routeDao.insertPoints(
                    cluster.geometry.mapIndexed { i, p ->
                        RoutePointEntity(routeId, i, p.lat, p.lon, p.cumDistM)
                    },
                )
                routeDao.clearProfile(routeId)
                routeDao.insertProfile(
                    cluster.profile.indices.map { i ->
                        RouteProfileEntity(
                            routeId = routeId,
                            bin = i,
                            medianCumTimeS = cluster.profile[i],
                            p25S = cluster.p25.getOrNull(i),
                            p75S = cluster.p75.getOrNull(i),
                            medianHr = cluster.medianHr.getOrNull(i),
                            medianAltM = cluster.medianAltM.getOrNull(i),
                        )
                    },
                )
                routeDao.clearMembers(routeId)
                routeDao.insertMembers(cluster.memberSessionIds.map { RouteMemberEntity(routeId, it) })

                // US-8 wants a coverage score per session. It is the same metric
                // the clusterer decided membership with, measured against the
                // route's final geometry rather than against another member.
                val geometryPoly = BundleBuilder.toPolyline(cluster.geometry)
                for (sessionId in cluster.memberSessionIds) {
                    val coverage = trackById[sessionId]
                        ?.let { CoverageMetric.fracWithin(it.points, geometryPoly) }
                    sessionDao.setMatch(sessionId, routeId, coverage)
                }
            }
            // Last, once every member has been written: what is left over
            // belongs to no route, whatever the watch committed to on the way.
            sessionDao.clearMatchesOfNonMembers()
        }

        publishBundle()
        // Last, and deliberately so: a failure while drawing must not cost the
        // watch its routes.
        rebuildHeatmap(tracks)
    }

    /** Rebuild the bundle from the database and hand it to the pusher. */
    private suspend fun publishBundle() {
        val db = AppGraph.db
        val routeDao = db.routes()
        val version = (db.meta().get(META_BUNDLE_VERSION)?.toLongOrNull() ?: 0L) + 1

        val entries = ArrayList<RouteEntry>()
        for (route in routeDao.all()) {
            if (!route.active) continue
            val points = routeDao.points(route.id)
            if (points.size < 2) continue
            val profile = routeDao.profile(route.id)
            val members = routeDao.members(route.id).map { it.sessionId }

            val priors = HashMap<String, Int>()
            for (sessionId in members) {
                val s = db.sessions().byId(sessionId) ?: continue
                val key = de.hecate.core.matcher.RouteMatcher.contextKeyOf(
                    s.exerciseType, s.startedAtUtc, s.tzOffsetMinutes,
                )
                priors[key] = (priors[key] ?: 0) + 1
            }

            entries.add(
                RouteEntry(
                    geometry = RouteGeometry(
                        routeId = route.id,
                        name = route.name,
                        exerciseType = route.exerciseType,
                        points = points.map { RoutePoint(it.lat, it.lon, it.cumDistM) },
                    ),
                    profile = RouteProfile(
                        routeId = route.id,
                        binM = Tuning.RESAMPLE_M,
                        medianCumTimeS = profile.map { it.medianCumTimeS },
                        p25S = profile.map { it.p25S ?: it.medianCumTimeS },
                        p75S = profile.map { it.p75S ?: it.medianCumTimeS },
                        medianHr = profile.map { it.medianHr },
                        medianAltM = profile.map { it.medianAltM },
                    ),
                    prior = de.hecate.core.model.RoutePrior(members.size, priors),
                    priority = route.priority,
                ),
            )
        }

        val bundle = BundleBuilder.bundle(version, System.currentTimeMillis(), entries)
        db.meta().put(de.hecate.phone.db.MetaEntity(META_BUNDLE_VERSION, version.toString()))
        BundlePusher(applicationContext).push(bundle)
    }

    /**
     * Rasterise every recording over the routes' own extent (US-8, Appendix A.14).
     *
     * Here rather than in the UI because it is O(samples) over the whole history
     * — the same work the clustering just did, on the same data — and the routes
     * screen must not do it on every visit. It runs after the bundle is pushed
     * so that a failure here can never cost the watch its routes: the picture is
     * the least important thing this worker produces.
     *
     * [tracks] is reused rather than re-read: the sessions were already loaded
     * and resampled above, and reading them again would double the worker's
     * cost for a second copy of the same coordinates.
     */
    private suspend fun rebuildHeatmap(tracks: List<Track>) {
        val db = AppGraph.db
        val routeDao = db.routes()

        val routes = routeDao.all().filter { it.active }.map { r ->
            RouteTrack(r.id, routeDao.points(r.id).map { LatLon(it.lat, it.lon) })
        }
        val sessions = tracks.map { SessionTrack(it.sessionId, it.points) }

        // Both pictures, from one pass over the same coordinates: the card's
        // ground and the pannable view's ground are different extents of the
        // same history (`Heatmap.Extent`), and computing the second one when the
        // user opens the dialog would be seconds of work on the frame they are
        // waiting for.
        val card = build(routes, sessions, Heatmap.Extent.ROUTES, HeatmapEntity.CARD, "Karte")
        if (card == null) {
            Log.i(TAG, "Keine Heatmap: keine Route innerhalb des Radius")
            return
        }
        build(routes, sessions, Heatmap.Extent.EVERYTHING, HeatmapEntity.FULL, "Vollbild")
    }

    /** One raster, stored under [id]. Returns null when there was nothing to
     *  draw, which for both extents means the same thing: no route survived the
     *  radius filter, so there is no centre to draw around. */
    private suspend fun build(
        routes: List<RouteTrack>,
        sessions: List<SessionTrack>,
        extent: Heatmap.Extent,
        id: Int,
        label: String,
    ): HeatmapGrid? {
        val grid = Heatmap.build(routes, sessions, extent = extent) ?: return null
        val payload = ByteArrayOutputStream().also { HeatmapCodec.writeGzip(grid, it) }.toByteArray()
        AppGraph.db.heatmap().put(
            HeatmapEntity(id = id, payload = payload, computedAtUtc = System.currentTimeMillis()),
        )
        Log.i(
            TAG,
            "Heatmap ($label): ${grid.routeIds.size} Routen, ${grid.sessionsUsed} Aufzeichnungen, " +
                "${grid.width}x${grid.height} Zellen à ${grid.cellM.toInt()} m, " +
                "max ${grid.maxCount}, ${payload.size} B" +
                if (grid.routesDiscarded > 0 || grid.sessionsDiscarded > 0) {
                    " (${grid.routesDiscarded} Routen / ${grid.sessionsDiscarded} Aufzeichnungen außerhalb des Radius)"
                } else {
                    ""
                } +
                // The other way of not being in the picture, and the one that
                // has no error behind it: inside the radius, off the ground the
                // extent covers. It is the first number to want when somebody
                // says a ride is missing — and on the full picture it should
                // never be anything but zero.
                if (grid.sessionsOutsideExtent > 0) {
                    " (${grid.sessionsOutsideExtent} Aufzeichnungen außerhalb des Ausschnitts)"
                } else {
                    ""
                },
        )
        return grid
    }

    companion object {
        private const val TAG = "ClusterWorker"
        const val META_BUNDLE_VERSION = "bundle_version"
    }
}

/**
 * Maps freshly computed clusters onto the routes already in the database.
 *
 * Without this, every re-clustering would produce new ids and the user's names
 * would evaporate. Matching is by Jaccard overlap of member sets: a route that
 * gains a member is still that route, a cluster that splits keeps the id on the
 * side that inherited most of the history.
 */
object RouteReconciler {

    /** @return cluster index -> existing route id, for those that matched. */
    fun assign(
        computed: List<BundleBuilder.ComputedRoute>,
        existing: List<RouteEntity>,
        existingMembers: Map<Long, List<String>>,
    ): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        val taken = HashSet<Long>()

        data class Pair2(val clusterIndex: Int, val routeId: Long, val overlap: Double)

        val scored = ArrayList<Pair2>()
        computed.forEachIndexed { i, cluster ->
            val a = cluster.memberSessionIds.toSet()
            for (route in existing) {
                val b = existingMembers[route.id]?.toSet() ?: emptySet()
                if (b.isEmpty()) continue
                val inter = a.count { it in b }
                val union = max(1, a.size + b.size - inter)
                val j = inter.toDouble() / union
                if (j >= Tuning.ROUTE_REIDENTIFY_OVERLAP) scored.add(Pair2(i, route.id, j))
            }
        }
        // Greedy by descending overlap, deterministic on ties.
        scored.sortWith(compareByDescending<Pair2> { it.overlap }.thenBy { it.clusterIndex }.thenBy { it.routeId })
        for (p in scored) {
            if (out.containsKey(p.clusterIndex) || p.routeId in taken) continue
            out[p.clusterIndex] = p.routeId
            taken.add(p.routeId)
        }
        return out
    }
}
