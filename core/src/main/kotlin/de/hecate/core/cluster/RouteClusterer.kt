package de.hecate.core.cluster

import de.hecate.core.Tuning
import de.hecate.core.geo.Track

/**
 * Offline clustering of session tracks into routes (§5.7, US-3).
 *
 * Average-link agglomerative clustering over `1 - coverage`, cut at
 * [Tuning.CLUSTER_CUT]. Recomputed from scratch on every ingest: O(n²) over a few
 * hundred sessions is milliseconds, and starting fresh each time means the result
 * depends only on the session set, never on the order sessions arrived in — which
 * is what US-3's determinism requirement actually asks for.
 */
object RouteClusterer {

    /** One cluster: its members and the member whose geometry represents it. */
    data class Cluster(
        val memberSessionIds: List<String>,
        val medoidSessionId: String,
        /** Whether the cluster has enough members to become an active route. */
        val active: Boolean,
    )

    data class Result(val clusters: List<Cluster>)

    /**
     * Cluster [tracks]. Input order does not matter: tracks are sorted by
     * (start time, session id) first and every tie is broken by that ordering.
     */
    fun cluster(
        tracks: List<Track>,
        cut: Double = Tuning.CLUSTER_CUT,
        minMembers: Int = Tuning.MIN_MEMBERS,
    ): Result {
        val ordered = tracks.sortedWith(compareBy({ it.startedAtUtcMs }, { it.sessionId }))
        val n = ordered.size
        if (n == 0) return Result(emptyList())

        val d = Array(n) { DoubleArray(n) }
        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val dist = CoverageMetric.distance(ordered[i], ordered[j])
                d[i][j] = dist
                d[j][i] = dist
            }
        }

        // Active clusters as index lists; merged clusters become null.
        val groups = ArrayList<MutableList<Int>?>(n)
        for (i in 0 until n) groups.add(mutableListOf(i))

        while (true) {
            var bestA = -1
            var bestB = -1
            var bestD = Double.MAX_VALUE
            for (a in 0 until groups.size) {
                val ga = groups[a] ?: continue
                for (b in a + 1 until groups.size) {
                    val gb = groups[b] ?: continue
                    val dist = averageLink(d, ga, gb)
                    // Strict < keeps the first (lowest-index, therefore earliest)
                    // pair on ties, so the merge order is fully determined.
                    if (dist < bestD) {
                        bestD = dist; bestA = a; bestB = b
                    }
                }
            }
            if (bestA < 0 || bestD > cut) break
            groups[bestA]!!.addAll(groups[bestB]!!)
            groups[bestB] = null
        }

        val clusters = groups.filterNotNull().map { g ->
            val sorted = g.sorted()
            val medoid = medoidOf(d, sorted)
            Cluster(
                memberSessionIds = sorted.map { ordered[it].sessionId },
                medoidSessionId = ordered[medoid].sessionId,
                active = sorted.size >= minMembers,
            )
        }.sortedWith(
            // Largest clusters first, then by earliest member, for a stable listing.
            compareByDescending<Cluster> { it.memberSessionIds.size }
                .thenBy { it.memberSessionIds.first() },
        )

        return Result(clusters)
    }

    private fun averageLink(d: Array<DoubleArray>, a: List<Int>, b: List<Int>): Double {
        var sum = 0.0
        for (i in a) for (j in b) sum += d[i][j]
        return sum / (a.size * b.size)
    }

    /** The member with the smallest total distance to the rest — exact, cheap, and
     *  a real recorded track rather than a synthetic average (§5.7). */
    private fun medoidOf(d: Array<DoubleArray>, members: List<Int>): Int {
        if (members.size == 1) return members[0]
        var best = members[0]
        var bestSum = Double.MAX_VALUE
        for (i in members) {
            var s = 0.0
            for (j in members) if (i != j) s += d[i][j]
            if (s < bestSum - 1e-12) { bestSum = s; best = i }
        }
        return best
    }
}
