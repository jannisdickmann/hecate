package de.hecate.core.archive

import de.hecate.core.Tuning
import de.hecate.core.cluster.BundleBuilder
import de.hecate.core.matcher.RouteMatcher
import de.hecate.core.model.RouteBundle
import de.hecate.core.model.RouteEntry
import de.hecate.core.model.RouteGeometry
import de.hecate.core.model.RoutePoint
import de.hecate.core.model.RoutePrior
import de.hecate.core.model.RouteProfile

/**
 * The bundle a backup's phone would have pushed to the watch, rebuilt from the
 * backup's own tables — the same rows, read the same way, as the phone's
 * `ClusterWorker.publishBundle`.
 *
 * This is what lets a replay answer "what did the watch do on that ride" from
 * nothing but the backup the user sent: no separate bundle file, no guessing at
 * priorities, which only the database records.
 */
object ArchiveBundle {

    fun of(db: Archive.Database): RouteBundle {
        val sessions = db.sessions.associateBy { it.id }
        val points = db.routePoints.groupBy { it.routeId }
        val bins = db.routeProfile.groupBy { it.routeId }
        val members = db.routeMembers.groupBy({ it.routeId }, { it.sessionId })
        val version = db.meta.firstOrNull { it.key == "bundle_version" }?.value?.toLongOrNull() ?: 0L

        val entries = db.routes.filter { it.active }.mapNotNull { route ->
            val pts = points[route.id].orEmpty().sortedBy { it.seq }
            if (pts.size < 2) return@mapNotNull null
            val profile = bins[route.id].orEmpty().sortedBy { it.bin }
            val ids = members[route.id].orEmpty()
            val priors = HashMap<String, Int>()
            for (id in ids) {
                val s = sessions[id] ?: continue
                val key = RouteMatcher.contextKeyOf(s.exerciseType, s.startedAtUtc, s.tzOffsetMinutes)
                priors[key] = (priors[key] ?: 0) + 1
            }
            RouteEntry(
                geometry = RouteGeometry(
                    routeId = route.id,
                    name = route.name,
                    exerciseType = route.exerciseType,
                    points = pts.map { RoutePoint(it.lat, it.lon, it.cumDistM) },
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
                prior = RoutePrior(ids.size, priors),
                priority = route.priority,
            )
        }
        return BundleBuilder.bundle(version, 0L, entries)
    }
}
