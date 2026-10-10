package de.hecate.core.display

import de.hecate.core.Tuning
import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The geometry of the watch's map page: a picture of the route and of the
 * user's own recent track, centred on where they are and turned so that the
 * way they are going points up.
 *
 * Lives in `:core` for the reason [routeBelief] does: it is arithmetic with
 * opinions in it — how far back a direction is read over, when there is no
 * direction to read at all — and those are tested on a laptop, not by walking
 * round the block with an emulator.
 */
object HeadingUp {

    /**
     * Which way the user is going [degrees clockwise from north], or null when
     * the track does not say.
     *
     * Read over the last [lookbackM] of path rather than between the last two
     * fixes: a single step is a metre or two of GNSS noise at walking pace, and a
     * map that turned with every one of them would be unreadable. Null while
     * the straight distance across that stretch is under half of it — standing
     * still, or turning on the spot — so the caller keeps the last direction it
     * had instead of spinning the picture to whatever the jitter suggests.
     */
    fun heading(trail: List<LatLon>, lookbackM: Double): Double? {
        if (trail.size < 2) return null
        val last = trail.last()
        var path = 0.0
        var from = trail.size - 1
        while (from > 0 && path < lookbackM) {
            path += Geo.haversine(trail[from - 1], trail[from])
            from--
        }
        if (path < lookbackM) return null
        val start = trail[from]
        if (Geo.haversine(start, last) < lookbackM * 0.5) return null
        return bearing(start, last)
    }

    /** Initial bearing from [a] to [b], degrees clockwise from north, 0..360. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val l = Geo.toLocal(a, b)
        val deg = atan2(l[0], l[1]) * 180.0 / PI
        return (deg + 360.0) % 360.0
    }

    /**
     * Move [previous] towards [next] by [factor] of the way, round the short side
     * of the circle: 350° and 10° are twenty degrees apart, not three hundred
     * and forty, and a map turning the long way round through south to get from
     * one to the other would be worse than no smoothing at all.
     */
    fun blend(previous: Double?, next: Double, factor: Double): Double {
        if (previous == null) return normalise(next)
        return normalise(previous + signedDelta(previous, next) * factor)
    }

    /**
     * The turn from [from] to [to], degrees, the short way round and positive
     * clockwise: for two headings in 0..360 it is in -180..180, and 350° to 10°
     * is +20, not -340.
     *
     * What [blend] steps along, and what a caller keeping an unwrapped running
     * heading — one that is never folded back into 0..360, so an animation of
     * it never spins through south — adds on each fix.
     */
    fun signedDelta(from: Double, to: Double): Double = ((to - from + 540.0) % 360.0) - 180.0

    /**
     * Where [p] lands on a canvas whose point ([cx], [cy]) is [centre] and whose
     * up is [headingDeg], at [pxPerM].
     *
     * Rotating the ground by minus the heading is what turns "the way I am
     * going" to the top of the screen: a point straight ahead of a user heading
     * east is due east of them, and has to come out directly above the centre.
     */
    fun project(
        centre: LatLon,
        headingDeg: Double,
        pxPerM: Double,
        cx: Double,
        cy: Double,
        p: LatLon,
    ): DoubleArray {
        val l = Geo.toLocal(centre, p)
        val t = headingDeg * PI / 180.0
        val right = l[0] * cos(t) - l[1] * sin(t)
        val ahead = l[0] * sin(t) + l[1] * cos(t)
        return doubleArrayOf(cx + right * pxPerM, cy - ahead * pxPerM)
    }

    /**
     * The recent track the page draws, one more fix added.
     *
     * Thinned to [Tuning.WATCH_TRAIL_MIN_SPACING_M] so that standing at a
     * crossing does not fill it with copies of one point, and cut to
     * [Tuning.WATCH_TRAIL_MAX_POINTS] from the front, which at walking pace is
     * well past the edge of the picture and at cycling pace still the last
     * couple of kilometres.
     */
    fun extend(trail: List<LatLon>, fix: LatLon): List<LatLon> {
        val last = trail.lastOrNull()
        if (last != null && Geo.haversine(last, fix) < Tuning.WATCH_TRAIL_MIN_SPACING_M) return trail
        val keep = if (trail.size >= Tuning.WATCH_TRAIL_MAX_POINTS) {
            trail.subList(trail.size - Tuning.WATCH_TRAIL_MAX_POINTS + 1, trail.size)
        } else {
            trail
        }
        return ArrayList<LatLon>(keep.size + 1).apply { addAll(keep); add(fix) }
    }

    private fun normalise(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
}
