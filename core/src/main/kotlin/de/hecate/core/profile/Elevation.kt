package de.hecate.core.profile

import de.hecate.core.Tuning
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.model.Altitudes
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.Sample

/**
 * Altitude against distance, and the handful of numbers worth reading off it.
 *
 * Two things use this: a recording, whose profile comes straight from its own
 * samples, and a route, whose profile is the per-bin median altitude over its
 * members and therefore already computed by [ProfileBuilder]. Both end up as the
 * same [Point] series so that one chart and one set of statistics serve both.
 *
 * Everything here is arithmetic over a series, which is why it lives in `:core`
 * where a laptop can check it rather than in the screen that draws it.
 */
object Elevation {

    /**
     * One point of the profile: how far along, how high, and how hard.
     *
     * [hrBpm] is along for the ride rather than part of the arithmetic — nothing
     * here reads it — and it is here so that thinning the series for a chart
     * cannot separate a point from the effort that was being made at it. The
     * screen colours the profile by heart rate (A.21), and a parallel list would
     * have to be thinned in lock-step with this one by every caller.
     */
    data class Point(val distanceM: Double, val altM: Double, val hrBpm: Double? = null)

    /**
     * What the detail screens show beside the chart.
     *
     * [ascentM] and [descentM] are both positive. They do not have to cancel:
     * a route that ends where it started still climbs and descends the same
     * amount, but a commute that ends 200 m above its start does not.
     */
    data class Stats(
        val ascentM: Double,
        val descentM: Double,
        val minM: Double,
        val maxM: Double,
        val startM: Double,
        val endM: Double,
    ) {
        /** Lowest to highest — how much of a hill this is at all. */
        val rangeM: Double get() = maxM - minM

        /** Where it ends against where it began. Negative for a net descent. */
        val netM: Double get() = endM - startM
    }

    /**
     * Total climb and drop, with the hysteresis [Tuning.ELEVATION_THRESHOLD_M]
     * describes.
     *
     * The rule is one line: keep an anchor, and only when the current altitude
     * has moved more than the threshold away from it does that move count — and
     * the anchor jumps to where we now are. Noise smaller than the threshold
     * never moves the anchor and so never accumulates, while a real climb is
     * counted in full, in pieces, no matter how gently it is taken.
     *
     * Null for a series too short or too empty to say anything.
     */
    fun stats(points: List<Point>, thresholdM: Double = Tuning.ELEVATION_THRESHOLD_M): Stats? {
        val alts = points.map { it.altM }.filter { it.isFinite() }
        if (alts.size < 2) return null

        var ascent = 0.0
        var descent = 0.0
        var anchor = alts.first()
        for (alt in alts) {
            val delta = alt - anchor
            if (delta >= thresholdM) {
                ascent += delta
                anchor = alt
            } else if (delta <= -thresholdM) {
                descent -= delta
                anchor = alt
            }
        }
        return Stats(
            ascentM = ascent,
            descentM = descent,
            minM = alts.min(),
            maxM = alts.max(),
            startM = alts.first(),
            endM = alts.last(),
        )
    }

    /**
     * A recording's own profile: altitude against distance travelled.
     *
     * Straight from the samples rather than from the resampled [de.hecate.core.geo.Track],
     * because a recording is shown as itself — the track exists to be compared
     * with other tracks, and the even spacing it imposes is of no use to a chart.
     *
     * Paused samples are skipped and so is anything without a plausible altitude;
     * distance still advances across a dropout, for the same reason it does in
     * `Track`: the ground was covered even though the shape of it is unknown.
     */
    fun ofSession(session: RecordedSession): List<Point> =
        of(Altitudes.merge(session.samples, session.altitudes))

    /**
     * As [ofSession], for a caller holding the samples rather than the session.
     *
     * Reads [Sample.altM] and nothing else. A watch recording's barometer series
     * is put onto that column by [Altitudes.merge] on the way in, exactly as
     * [de.hecate.core.model.HeartRates] does for the rate — so by the time
     * anything draws a profile, the two sources of altitude look the same and
     * only one of them has to be understood here.
     */
    fun of(samples: List<Sample>): List<Point> {
        // Distance first, altitude second, so that the median runs over a series
        // with the same indices as the samples it came from.
        val usable = samples.filter { !it.paused && it.lat.isFinite() && it.lon.isFinite() }
        if (usable.isEmpty()) return emptyList()

        val raw = DoubleArray(usable.size) { i ->
            val alt = usable[i].altM
            if (alt != null && alt.isFinite() && alt >= Tuning.MIN_ALTITUDE_M && alt <= Tuning.MAX_ALTITUDE_M) {
                alt
            } else {
                Double.NaN
            }
        }
        val cleaned = despike(raw)

        val out = ArrayList<Point>(usable.size)
        var travelled = 0.0
        var previous: LatLon? = null
        for (i in usable.indices) {
            val here = LatLon(usable[i].lat, usable[i].lon)
            previous?.let { travelled += Geo.haversine(it, here) }
            previous = here
            val alt = cleaned[i]
            if (alt.isNaN()) continue
            out.add(Point(travelled, alt, usable[i].hrBpm?.takeIf { it > 0 }?.toDouble()))
        }
        return out
    }

    /**
     * A rolling median over an altitude series, in place, ignoring NaN.
     *
     * The one piece of post-processing between what the watch recorded and every
     * number this app shows about height. Its job is impulses — the altimeter
     * settling over the first seconds of a recording, and the odd sample that
     * wanders later — and its virtue is what it leaves alone: the median of a
     * monotonic run is that run's middle value, so climbs and descents survive
     * it exactly. See [Tuning.ELEVATION_MEDIAN_WINDOW].
     *
     * Raw altitude is still what gets written to the session file and to the
     * database. This is applied on the way out, every time, so that the stored
     * data stays the thing the watch actually said and the fix can be changed
     * later without a migration.
     *
     * At the ends the window is slid inward rather than truncated, and that
     * choice is the whole reason the opening spike goes: a window truncated at
     * sample 0 contains only the settling samples, so its median is settling too,
     * while a full window reaching forward contains the good values that outvote
     * them.
     *
     * It is not free, and the cost falls exactly on the first and last half-window.
     * There the filter no longer has symmetric context, so those samples follow
     * their neighbourhood instead of themselves: a series already changing fast at
     * sample 0 is flattened towards what comes next. Measured on ramps, the error
     * is 1–2 m at the vertical rates a bike or a pair of boots manages, and 32 m
     * for a ski descent at 8 m/s — which cannot happen at a recording's first
     * sample, because that is four seconds after somebody pressed start and
     * nobody is at 8 m/s by then. In the interior the error is not small but
     * zero: a monotonic run passes through a centred median unchanged.
     *
     * The other rounding worth knowing about is a sharp apex, which a median
     * clips by about half a window's worth of climb — 3 m on a 300 m hill.
     */
    fun despike(alts: DoubleArray, window: Int = Tuning.ELEVATION_MEDIAN_WINDOW): DoubleArray =
        Series.rollingMedian(alts, window)

    /**
     * A route's profile from the per-bin medians [ProfileBuilder] produced.
     *
     * Bins nobody recorded an altitude for are dropped rather than interpolated:
     * the chart draws what is known, and a gap in the middle of a route is a fact
     * about the data worth seeing.
     */
    fun ofBins(
        medianAltM: List<Double?>,
        binM: Double,
        medianHr: List<Double?> = emptyList(),
    ): List<Point> =
        medianAltM.mapIndexedNotNull { i, alt ->
            alt?.takeIf { it.isFinite() }?.let {
                Point(i * binM, it, medianHr.getOrNull(i)?.takeIf { hr -> hr.isFinite() && hr > 0 })
            }
        }

    /**
     * Thin a series down to at most [max] points for drawing.
     *
     * [Series.thin] does the work, and does it for every chart in the app; this
     * only says that the thing being plotted is the altitude and the thing that
     * orders it is the distance.
     */
    fun simplify(points: List<Point>, max: Int): List<Point> =
        Series.thin(points, max, { it.altM }, { it.distanceM })
}
