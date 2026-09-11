package de.hecate.core

import de.hecate.core.cluster.CoverageMetric
import de.hecate.core.geo.Track
import kotlin.test.Test
import kotlin.test.assertTrue

class CoverageMetricTest {

    private fun track(id: String, points: List<de.hecate.core.geo.LatLon>, seed: Int = 1, jitter: Double = 3.0): Track =
        Track.of(Synthetic.record(id, points, Synthetic.MONDAY_0730_UTC, seed = seed, jitterM = jitter))!!

    @Test
    fun `the same route recorded twice covers itself`() {
        val a = track("a", Synthetic.workRoute, seed = 1)
        val b = track("b", Synthetic.workRoute, seed = 2)
        val cov = CoverageMetric.coverage(a, b)
        assertTrue(cov > 0.95, "coverage=$cov")
        assertTrue(CoverageMetric.distance(a, b) < Tuning.CLUSTER_CUT)
    }

    @Test
    fun `a shortcut is not the same route as the long version`() {
        val long = track("long", Synthetic.workRoute, seed = 3)
        val short = track("short", Synthetic.shortcut, seed = 4)
        val cov = CoverageMetric.coverage(long, short)
        // The shortcut shares its first 2 km, but the min() of the two directions
        // is what decides — and the long route is far from covered.
        assertTrue(cov < 0.75, "coverage=$cov")
        assertTrue(CoverageMetric.distance(long, short) > Tuning.CLUSTER_CUT)
    }

    @Test
    fun `a five degree divergence is detected`() {
        val a = track("a", Synthetic.workRoute, seed = 5)
        val b = track("b", Synthetic.fiveDegreeDivergence, seed = 6)
        val cov = CoverageMetric.coverage(a, b)
        assertTrue(cov < 0.9, "coverage=$cov")
    }

    @Test
    fun `the reverse of a route is a different route`() {
        val fwd = track("fwd", Synthetic.workRoute, seed = 7)
        val rev = track("rev", Synthetic.reverseRoute, seed = 8)
        // Geometrically identical...
        assertTrue(CoverageMetric.coverage(fwd, rev) > 0.95)
        // ...but never merged (US-3).
        assertTrue(!CoverageMetric.sameDirection(fwd, rev))
        assertTrue(CoverageMetric.distance(fwd, rev) >= 1.0)
    }

    @Test
    fun `a novel path shares nothing`() {
        val a = track("a", Synthetic.workRoute, seed = 9)
        val n = track("n", Synthetic.novelRoute, seed = 10)
        assertTrue(CoverageMetric.coverage(a, n) < 0.15, "coverage=${CoverageMetric.coverage(a, n)}")
    }

    @Test
    fun `injected jitter does not break the metric`() {
        val a = track("a", Synthetic.workRoute, seed = 11, jitter = 2.0)
        val b = track("b", Synthetic.workRoute, seed = 12, jitter = 12.0)
        assertTrue(CoverageMetric.coverage(a, b) > 0.85, "coverage=${CoverageMetric.coverage(a, b)}")
    }

    @Test
    fun `different exercise types never match`() {
        val run = Track.of(
            Synthetic.record("r", Synthetic.workRoute, Synthetic.MONDAY_0730_UTC, type = de.hecate.core.model.ExerciseType.RUNNING),
        )!!
        val bike = Track.of(
            Synthetic.record("b", Synthetic.workRoute, Synthetic.MONDAY_0730_UTC, type = de.hecate.core.model.ExerciseType.BIKING),
        )!!
        assertTrue(CoverageMetric.distance(run, bike) >= 1.0)
    }
}
