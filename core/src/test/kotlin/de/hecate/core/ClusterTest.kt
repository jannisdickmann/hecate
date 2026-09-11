package de.hecate.core

import de.hecate.core.cluster.BundleBuilder
import de.hecate.core.cluster.RouteClusterer
import de.hecate.core.geo.LatLon
import de.hecate.core.geo.Track
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClusterTest {

    private fun track(id: String, points: List<LatLon>, day: Int, seed: Int): Track =
        Track.of(
            Synthetic.record(id, points, Synthetic.MONDAY_0730_UTC + day * Synthetic.DAY_MS, seed = seed),
        )!!

    @Test
    fun `three similar recordings become one active route`() {
        val tracks = listOf(
            track("w1", Synthetic.workRoute, 0, 1),
            track("w2", Synthetic.workRoute, 1, 2),
            track("w3", Synthetic.workRoute, 2, 3),
        )
        val res = RouteClusterer.cluster(tracks)
        assertEquals(1, res.clusters.size)
        assertTrue(res.clusters[0].active)
        assertEquals(3, res.clusters[0].memberSessionIds.size)
    }

    @Test
    fun `two recordings are not yet a route`() {
        val tracks = listOf(
            track("w1", Synthetic.workRoute, 0, 1),
            track("w2", Synthetic.workRoute, 1, 2),
        )
        val res = RouteClusterer.cluster(tracks)
        assertEquals(1, res.clusters.size)
        assertTrue(!res.clusters[0].active, "two members must not make an active route")
        assertTrue(BundleBuilder.computeRoutes(tracks).isEmpty())
    }

    @Test
    fun `a shortcut forms its own cluster, not a sub-variant`() {
        val tracks = buildList {
            repeat(3) { add(track("long$it", Synthetic.workRoute, it, it + 1)) }
            repeat(3) { add(track("short$it", Synthetic.shortcut, 10 + it, 20 + it)) }
        }
        val res = RouteClusterer.cluster(tracks)
        assertEquals(2, res.clusters.size, "clusters: ${res.clusters.map { it.memberSessionIds }}")
        assertTrue(res.clusters.all { it.active })
        // No cluster mixes the two shapes (§8.1).
        for (c in res.clusters) {
            val prefixes = c.memberSessionIds.map { it.takeWhile { ch -> !ch.isDigit() } }.toSet()
            assertEquals(1, prefixes.size, "mixed cluster: ${c.memberSessionIds}")
        }
    }

    @Test
    fun `the reverse direction is its own route`() {
        val tracks = buildList {
            repeat(3) { add(track("fwd$it", Synthetic.workRoute, it, it + 1)) }
            repeat(3) { add(track("rev$it", Synthetic.reverseRoute, 10 + it, 30 + it)) }
        }
        val res = RouteClusterer.cluster(tracks)
        assertEquals(2, res.clusters.size)
        for (c in res.clusters) {
            val prefixes = c.memberSessionIds.map { it.take(3) }.toSet()
            assertEquals(1, prefixes.size, "direction reversal merged: ${c.memberSessionIds}")
        }
    }

    @Test
    fun `a single novel path stays its own inactive cluster`() {
        val tracks = buildList {
            repeat(3) { add(track("w$it", Synthetic.workRoute, it, it + 1)) }
            add(track("novel", Synthetic.novelRoute, 9, 99))
        }
        val res = RouteClusterer.cluster(tracks)
        assertEquals(2, res.clusters.size)
        val novel = res.clusters.first { it.memberSessionIds.contains("novel") }
        assertTrue(!novel.active)
        assertEquals(1, BundleBuilder.computeRoutes(tracks).size)
    }

    @Test
    fun `clustering is deterministic regardless of input order`() {
        val tracks = buildList {
            repeat(3) { add(track("w$it", Synthetic.workRoute, it, it + 1)) }
            repeat(3) { add(track("s$it", Synthetic.shortcut, 10 + it, 20 + it)) }
            repeat(3) { add(track("n$it", Synthetic.novelRoute, 20 + it, 40 + it)) }
        }
        val a = RouteClusterer.cluster(tracks).clusters
        val b = RouteClusterer.cluster(tracks.reversed()).clusters
        val c = RouteClusterer.cluster(tracks.shuffled(kotlin.random.Random(7))).clusters
        assertEquals(a, b)
        assertEquals(a, c)
    }

    @Test
    fun `the medoid is an actual member`() {
        val tracks = (0 until 5).map { track("w$it", Synthetic.workRoute, it, it + 1) }
        val routes = BundleBuilder.computeRoutes(tracks)
        assertEquals(1, routes.size)
        assertTrue(routes[0].medoidSessionId in routes[0].memberSessionIds)
        assertTrue(routes[0].geometry.size > 400, "geometry too short: ${routes[0].geometry.size}")
        assertTrue(routes[0].lengthM > 4000 && routes[0].lengthM < 5200, "length=${routes[0].lengthM}")
    }

    @Test
    fun `priors are counted per context bucket`() {
        val tracks = (0 until 3).map { track("w$it", Synthetic.workRoute, it, it + 1) }
        val prior = BundleBuilder.priorOf(tracks)
        assertEquals(3, prior.totalCount)
        assertEquals(1, prior.byContext.size, "all three are the same weekday morning bucket")
    }
}
