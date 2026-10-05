package de.hecate.core

import de.hecate.core.cluster.BundleBuilder
import de.hecate.core.geo.Track
import de.hecate.core.profile.ProfileBuilder
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ProfileTest {

    @Test
    fun `median cumulative time tracks the pace it was recorded at`() {
        // Three members at 3 m/s: the median time at 1500 m must be ~500 s.
        val tracks = (0 until 3).map {
            Track.of(
                Synthetic.record("m$it", Synthetic.workRoute, Synthetic.MONDAY_0730_UTC + it * Synthetic.DAY_MS, speedMps = 3.0, seed = it + 1),
            )!!
        }
        val routes = BundleBuilder.computeRoutes(tracks)
        val p = de.hecate.core.model.RouteProfile(1, Tuning.RESAMPLE_M, routes[0].profile)
        val t = p.medianTimeAt(1500.0)
        assertTrue(abs(t - 500.0) < 40.0, "median at 1500 m = $t s")
    }

    @Test
    fun `a faster member pulls the median but does not dominate it`() {
        val slow = (0 until 3).map {
            Track.of(Synthetic.record("s$it", Synthetic.workRoute, Synthetic.MONDAY_0730_UTC + it * Synthetic.DAY_MS, speedMps = 2.5, seed = it + 1))!!
        }
        val fast = Track.of(Synthetic.record("f", Synthetic.workRoute, Synthetic.MONDAY_0730_UTC + 9 * Synthetic.DAY_MS, speedMps = 5.0, seed = 77))!!
        val routes = BundleBuilder.computeRoutes(slow + fast)
        val p = de.hecate.core.model.RouteProfile(1, Tuning.RESAMPLE_M, routes[0].profile)
        val t = p.medianTimeAt(1000.0)
        // Median of {400, 400, 400, 200} ~ 400, not the mean of 350.
        assertTrue(t > 360.0, "median at 1000 m = $t s")
    }

    @Test
    fun `the profile is monotone`() {
        val tracks = (0 until 3).map {
            Track.of(Synthetic.record("m$it", Synthetic.workRoute, Synthetic.MONDAY_0730_UTC + it * Synthetic.DAY_MS, seed = it + 1))!!
        }
        val prof = BundleBuilder.computeRoutes(tracks)[0].profile
        for (i in 1 until prof.size) {
            assertTrue(prof[i] >= prof[i - 1], "profile decreases at bin $i")
        }
    }

    @Test
    fun `a member missing a stretch does not put a step into the median`() {
        // The slowest of three has a minute-long dropout. Over that stretch the
        // median used to be taken over the other two only — a faster pair — so
        // it dipped, was held flat by the monotone pass, and came back as a step
        // the size of the gap between the riders: the shape that made
        // ahead/behind jump by half a minute on a real commute.
        val day = Synthetic.DAY_MS
        val tracks = listOf(
            Track.of(Synthetic.record("slow", Synthetic.workRoute, Synthetic.MONDAY_0730_UTC, speedMps = 2.5, seed = 1, dropoutAfterS = 500.0, dropoutDurationS = 60.0))!!,
            Track.of(Synthetic.record("mid", Synthetic.workRoute, Synthetic.MONDAY_0730_UTC + day, speedMps = 3.0, seed = 2))!!,
            Track.of(Synthetic.record("fast", Synthetic.workRoute, Synthetic.MONDAY_0730_UTC + 2 * day, speedMps = 4.0, seed = 3))!!,
        )
        val prof = BundleBuilder.computeRoutes(tracks, minMembers = 3)[0].profile
        // At the median rider's 3 m/s a 10 m bin takes 3.3 s.
        val worst = (1 until prof.size).maxOf { prof[it] - prof[it - 1] }
        assertTrue(worst < 10.0, "the median steps by $worst s within one bin")
    }

    @Test
    fun `gaps are interpolated`() {
        val v = doubleArrayOf(Double.NaN, 10.0, Double.NaN, Double.NaN, 40.0, Double.NaN)
        ProfileBuilder.fillGaps(v)
        assertTrue(abs(v[0] - 10.0) < 1e-9)
        assertTrue(abs(v[2] - 20.0) < 1e-9, "got ${v[2]}")
        assertTrue(abs(v[3] - 30.0) < 1e-9, "got ${v[3]}")
        assertTrue(abs(v[5] - 40.0) < 1e-9)
    }

    @Test
    fun `quantiles interpolate`() {
        val s = doubleArrayOf(1.0, 2.0, 3.0, 4.0)
        assertTrue(abs(ProfileBuilder.quantile(s, 0.5) - 2.5) < 1e-9)
        assertTrue(abs(ProfileBuilder.quantile(s, 0.25) - 1.75) < 1e-9)
    }

    @Test
    fun `heart rate is carried into the profile`() {
        val sessions = (0 until 3).map {
            Synthetic.record("m$it", Synthetic.workRoute, Synthetic.MONDAY_0730_UTC + it * Synthetic.DAY_MS, seed = it + 1, hrBase = 150)
        }
        val tracks = sessions.mapNotNull { Track.of(it) }
        val hr = sessions.associate { it.id to it.samples }
        val routes = BundleBuilder.computeRoutes(tracks, hr)
        val median = routes[0].medianHr.filterNotNull()
        assertTrue(median.isNotEmpty(), "no HR in profile")
        assertTrue(median.all { it in 130.0..170.0 }, "implausible HR: ${median.take(5)}")
    }
}
