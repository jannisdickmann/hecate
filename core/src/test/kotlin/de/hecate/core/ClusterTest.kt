package de.hecate.core

import de.hecate.core.cluster.BundleBuilder
import de.hecate.core.cluster.CoverageMetric
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
    fun `a long detour of a route is its own route, even when coverage would merge it`() {
        val tracks = buildList {
            repeat(3) { add(track("w$it", Synthetic.workRoute, it, it + 1)) }
            add(track("detour", Synthetic.detour(500.0), 9, 99))
        }
        val detour = tracks.last()
        // The case the detour test exists for: 1 - coverage is inside the cut.
        assertTrue(CoverageMetric.coverage(tracks[0], detour) > 1 - Tuning.CLUSTER_CUT)
        val res = RouteClusterer.cluster(tracks)
        val own = res.clusters.first { "detour" in it.memberSessionIds }
        assertEquals(listOf("detour"), own.memberSessionIds)
        assertTrue(!own.active)
    }

    @Test
    fun `a short detour stays a member of its route`() {
        val tracks = buildList {
            repeat(3) { add(track("w$it", Synthetic.workRoute, it, it + 1)) }
            add(track("detour", Synthetic.detour(100.0), 9, 99))
        }
        val res = RouteClusterer.cluster(tracks)
        assertEquals(1, res.clusters.size, "clusters: ${res.clusters.map { it.memberSessionIds }}")
    }

    @Test
    fun `three walks of the same detour become a route of their own`() {
        val tracks = buildList {
            repeat(3) { add(track("w$it", Synthetic.workRoute, it, it + 1)) }
            repeat(3) { add(track("d$it", Synthetic.detour(500.0), 10 + it, 50 + it)) }
        }
        val res = RouteClusterer.cluster(tracks)
        assertEquals(2, res.clusters.size)
        assertTrue(res.clusters.all { it.active && it.memberSessionIds.map { id -> id[0] }.toSet().size == 1 })
    }

    // ------------------------------------------------------ temporary routes

    @Test
    fun `a promoted recording is a temporary route on its own`() {
        val tracks = listOf(
            track("w0", Synthetic.workRoute, 0, 1),
            track("n0", Synthetic.novelRoute, 1, 2),
        )
        val routes = BundleBuilder.computeRoutes(tracks, temporarySeeds = setOf("n0"))
        val r = routes.single()
        assertTrue(r.temporary)
        assertEquals("n0", r.medoidSessionId)
        assertEquals(listOf("n0"), r.memberSessionIds)
        assertTrue(r.profile.isNotEmpty())
    }

    @Test
    fun `a recording that matches a temporary route is filed under it, which stays the seed's`() {
        val tracks = listOf(
            track("n0", Synthetic.novelRoute, 0, 1),
            track("n1", Synthetic.novelRoute, 1, 2),
        )
        val seedOnly = BundleBuilder.computeRoutes(tracks.take(1), temporarySeeds = setOf("n0")).single()
        val r = BundleBuilder.computeRoutes(tracks, temporarySeeds = setOf("n0")).single()
        assertTrue(r.temporary)
        assertEquals(listOf("n0", "n1"), r.memberSessionIds)
        assertEquals("n0", r.medoidSessionId)
        // Only the first is the route: geometry and timing are the seed's alone.
        assertEquals(seedOnly.geometry, r.geometry)
        assertEquals(seedOnly.profile, r.profile)
        assertEquals(2, r.prior.totalCount)
    }

    @Test
    fun `two promoted recordings that match each other are one temporary route`() {
        val tracks = listOf(
            track("n1", Synthetic.novelRoute, 1, 2),
            track("n0", Synthetic.novelRoute, 0, 1),
        )
        val routes = BundleBuilder.computeRoutes(tracks, temporarySeeds = setOf("n0", "n1"))
        val r = routes.single()
        assertEquals("n0", r.medoidSessionId, "the earlier recording is the route")
    }

    @Test
    fun `the third match makes it a real route and the promotion has nothing left to do`() {
        val tracks = (0 until 3).map { track("n$it", Synthetic.novelRoute, it, it + 1) }
        val r = BundleBuilder.computeRoutes(tracks, temporarySeeds = setOf("n0")).single()
        assertTrue(!r.temporary)
        assertEquals(3, r.memberSessionIds.size)
        assertEquals(r, BundleBuilder.computeRoutes(tracks).single())
    }

    @Test
    fun `a recording that does not match the temporary route is not filed under it`() {
        val tracks = listOf(
            track("n0", Synthetic.novelRoute, 0, 1),
            track("w0", Synthetic.workRoute, 1, 2),
        )
        val r = BundleBuilder.computeRoutes(tracks, temporarySeeds = setOf("n0")).single()
        assertEquals(listOf("n0"), r.memberSessionIds)
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
