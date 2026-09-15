package de.hecate.core.profile

import de.hecate.core.Tuning
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.model.Sample

/**
 * Speed and heart rate against elapsed time, for a recording or for a route.
 *
 * The third and fourth charts in the app, and deliberately the same shape as the
 * first two: one [Point] series, coloured by heart rate, thinned by
 * [Series.thin], drawn by the one chart composable. What differs between a
 * recording and a route is only where the numbers come from — a recording has
 * its own samples, a route has the per-bin medians over its members — and that
 * difference is confined to the two pairs of builders below.
 *
 * Time is **active** time, not wall-clock: it is what the rest of the screen
 * means by "Dauer", and a chart whose x axis included a twenty-minute café stop
 * would spend a fifth of its width saying nothing happened. A pause therefore
 * closes up rather than leaving a gap.
 *
 * Everything here is arithmetic over a series, so it lives in `:core` where a
 * laptop can check it rather than in the screen that draws it.
 */
object Timeline {

    /**
     * One point: how far into the effort, how much of whatever is being
     * plotted, and how hard.
     *
     * [hrBpm] rides along exactly as it does in [Elevation.Point], and for the
     * same reason: the chart is coloured by it, and a parallel list would have
     * to be thinned in lock-step by every caller.
     */
    data class Point(val tMs: Long, val value: Double, val hrBpm: Double? = null)

    /** What the line under a chart says: the range the series covers.
     *
     *  A range rather than an average, because both screens already carry an
     *  average of their own — a recording's distance over its active time, a
     *  route's median duration — and a second number called "Ø", computed
     *  differently and disagreeing slightly, would be worse than no number. */
    data class Stats(val min: Double, val max: Double)

    /** Null for a series with nothing in it to describe. */
    fun stats(points: List<Point>): Stats? {
        if (points.isEmpty()) return null
        val values = points.map { it.value }.filter { it.isFinite() }
        if (values.isEmpty()) return null
        return Stats(min = values.min(), max = values.max())
    }

    /**
     * A recording's speed, in km/h, against its active time.
     *
     * Speed is a derivative, which is why this needs more care than reading a
     * column: one second of GNSS noise at 15 m is 54 km/h of imaginary sprint.
     * Three things keep it honest.
     *
     * - It is measured over the *travelled* distance between consecutive
     *   samples, not over a projection onto anything, so it says what the
     *   receiver saw.
     * - A dropout is not a speed. Where the gap between two samples exceeds
     *   [Tuning.MAX_INTERPOLATION_GAP_MS] or [Tuning.MAX_INTERPOLATION_GAP_M],
     *   the straight line between them is not a path anybody took and its
     *   average is not a speed anybody rode; the point becomes NaN and is
     *   dropped rather than invented.
     * - The series then goes through the same rolling median everything else
     *   does ([Tuning.SPEED_MEDIAN_WINDOW]), which removes excursions shorter
     *   than half the window and leaves a real acceleration — a monotonic run —
     *   exactly alone.
     *
     * Paused samples are expected to have been dropped by the caller, as they
     * are for the altitude profile, so that every series on the screen indexes
     * the same samples and the colours cannot slide along the line.
     */
    fun speedOfSamples(samples: List<Sample>, window: Int = Tuning.SPEED_MEDIAN_WINDOW): List<Point> {
        val usable = samples.filter { !it.paused && it.lat.isFinite() && it.lon.isFinite() }
        if (usable.size < 2) return emptyList()

        val raw = DoubleArray(usable.size) { Double.NaN }
        for (i in 1 until usable.size) {
            val a = usable[i - 1]
            val b = usable[i]
            val dtMs = b.activeMs - a.activeMs
            if (dtMs <= 0) continue
            val distance = Geo.haversine(LatLon(a.lat, a.lon), LatLon(b.lat, b.lon))
            if (dtMs > Tuning.MAX_INTERPOLATION_GAP_MS || distance > Tuning.MAX_INTERPOLATION_GAP_M) continue
            raw[i] = (distance / 1000.0) / (dtMs / 3_600_000.0)
        }
        // The first sample has no interval behind it and therefore no speed;
        // giving it the second one's would draw a ride that began at pace.
        raw[0] = Double.NaN
        val smoothed = Series.rollingMedian(raw, window)

        val out = ArrayList<Point>(usable.size)
        for (i in usable.indices) {
            val v = smoothed[i]
            if (v.isNaN()) continue
            out.add(Point(usable[i].activeMs, v, usable[i].hrBpm?.takeIf { it > 0 }?.toDouble()))
        }
        return out
    }

    /**
     * A recording's heart rate against its active time.
     *
     * Straight from the samples, and expected to have been smoothed by the
     * caller in the same pass that smooths it for every other drawing on the
     * screen — this chart must be the same numbers in the same order as the
     * colours on the track beside it, or the two contradict each other in a way
     * nobody can explain.
     *
     * A sample with no reading is a gap, not a zero: the line stops and resumes.
     */
    fun heartRateOfSamples(samples: List<Sample>): List<Point> =
        samples.asSequence()
            .filter { !it.paused }
            .mapNotNull { s ->
                s.hrBpm?.takeIf { it > 0 }?.let { Point(s.activeMs, it.toDouble(), it.toDouble()) }
            }
            .toList()

    /**
     * A route's speed against its median elapsed time.
     *
     * The route profile holds, per bin of [binM] metres, the median cumulative
     * time its members took to get there, so a difference of two of those over
     * the distance between them is the speed this route is usually ridden at — a
     * *typical* ride rather than any particular one, which is what every other
     * number on the route screen means too.
     *
     * The difference is taken across [window] bins rather than between
     * neighbours, and that is the whole care this function needs. Each bin's
     * median is taken independently of its neighbours', so the sequence is
     * monotone only on average: two adjacent bins can come out a hundredth of a
     * second apart, and over ten metres that is four thousand kilometres an
     * hour. It showed up on the first real route it was pointed at, as a chart
     * with one spike and a flat line under it. A baseline of
     * [Tuning.ROUTE_SPEED_WINDOW_BINS] bins is tens of seconds of riding, which
     * is long against the medians' own scatter and short against a hill.
     *
     * A bin whose window still does not advance in time is dropped rather than
     * divided by: that is a route doubling back through its own bins, or too few
     * members reaching that far for a median to mean anything.
     *
     * What the window cannot fix, the same rolling median as everywhere else
     * does. A window is an average, and an average carries a large enough single
     * jump — a bin where the members bunch up, at a turn the resampled geometry
     * passes twice — into every speed it is part of, as a narrow spike. A median
     * over the finished series removes anything narrower than half its width and
     * leaves a descent, which is a monotone run, exactly alone.
     */
    fun speedOfBins(
        medianCumTimeS: List<Double>,
        binM: Double,
        medianHr: List<Double?> = emptyList(),
        window: Int = Tuning.ROUTE_SPEED_WINDOW_BINS,
    ): List<Point> {
        val n = medianCumTimeS.size
        if (n < 2) return emptyList()
        // Centred where there is room, slid inward at the ends, always the same
        // width — as [Series.rollingMedian] does, and for the same reason: a
        // truncated window at the ends is a different measurement.
        val w = window.coerceIn(1, n - 1)
        val raw = DoubleArray(n) { i ->
            val lo = (i - w / 2).coerceIn(0, n - 1 - w)
            val dt = medianCumTimeS[lo + w] - medianCumTimeS[lo]
            if (dt.isFinite() && dt > 0) (w * binM / 1000.0) / (dt / 3600.0) else Double.NaN
        }
        val smoothed = Series.rollingMedian(raw, window)

        val out = ArrayList<Point>(n)
        for (i in 0 until n) {
            val kmh = smoothed[i]
            if (kmh.isNaN()) continue
            val t = medianCumTimeS[i]
            if (!t.isFinite()) continue
            out.add(
                Point(
                    tMs = (t * 1000).toLong(),
                    value = kmh,
                    hrBpm = medianHr.getOrNull(i)?.takeIf { it.isFinite() && it > 0 },
                ),
            )
        }
        return out
    }

    /** A route's per-bin median heart rate against its median elapsed time. */
    fun heartRateOfBins(medianCumTimeS: List<Double>, medianHr: List<Double?>): List<Point> {
        val out = ArrayList<Point>(medianHr.size)
        for (i in medianHr.indices) {
            val hr = medianHr[i]?.takeIf { it.isFinite() && it > 0 } ?: continue
            val t = medianCumTimeS.getOrNull(i)?.takeIf { it.isFinite() } ?: continue
            out.add(Point((t * 1000).toLong(), hr, hr))
        }
        return out
    }

    /** As [Elevation.simplify], for a series against time. */
    fun simplify(points: List<Point>, max: Int): List<Point> =
        Series.thin(points, max, { it.value }, { it.tMs.toDouble() })
}
