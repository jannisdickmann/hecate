package de.hecate.core.geo

import de.hecate.core.Tuning
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.PI

/** A bare latitude/longitude pair. Degrees, WGS-84. */
data class LatLon(val lat: Double, val lon: Double)

/**
 * Spherical geometry, plus the local flat-earth projection everything else is
 * built on.
 *
 * Over the few kilometres a route spans, treating a small patch of the sphere as
 * a plane costs far less than a metre — well below GNSS noise — and makes point
 * projection, resampling and the spatial index straightforward. Distances between
 * arbitrary points still go through the haversine formula.
 */
object Geo {

    private const val DEG_TO_RAD = PI / 180.0

    /** Great-circle distance [m]. */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = (lat2 - lat1) * DEG_TO_RAD
        val dLon = (lon2 - lon1) * DEG_TO_RAD
        val a = sin(dLat / 2).let { it * it } +
            cos(lat1 * DEG_TO_RAD) * cos(lat2 * DEG_TO_RAD) * sin(dLon / 2).let { it * it }
        return 2 * Tuning.EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    fun haversine(a: LatLon, b: LatLon): Double = haversine(a.lat, a.lon, b.lat, b.lon)

    /** Length [m] of the path through [points] in order, vertex to vertex; 0 for
     *  fewer than two. Summed front to back, so every caller gets the same
     *  double for the same path. */
    fun pathLength(points: List<LatLon>): Double {
        var sum = 0.0
        for (i in 1 until points.size) sum += haversine(points[i - 1], points[i])
        return sum
    }

    /**
     * Average speed [km/h] over [distanceM] covered in [durationMs].
     *
     * Kilometres over hours, in that order, rather than `m/s * 3.6`: the two can
     * differ in the last bit, and this is the expression the speed chart, the
     * auto-stop and both apps' average-speed figures were each written with —
     * one copy, so that a ride cannot come out as 10.8 on one screen and
     * 10.799999 on another. No guard: a zero [durationMs] gives Infinity or NaN,
     * and what a speed over no time means is the caller's decision.
     */
    fun kmh(distanceM: Double, durationMs: Long): Double = (distanceM / 1000.0) / (durationMs / 3_600_000.0)

    /** Metres per degree of latitude. Constant enough for our purposes. */
    fun metersPerDegLat(): Double = Tuning.EARTH_RADIUS_M * DEG_TO_RAD

    /** Metres per degree of longitude at the given latitude. */
    fun metersPerDegLon(atLat: Double): Double =
        Tuning.EARTH_RADIUS_M * DEG_TO_RAD * cos(atLat * DEG_TO_RAD)

    /**
     * Local east/north offset [m] of [p] relative to [origin]. Accurate to well
     * under a metre within a few tens of kilometres, which is all a route needs.
     */
    fun toLocal(origin: LatLon, p: LatLon): DoubleArray = doubleArrayOf(
        (p.lon - origin.lon) * metersPerDegLon(origin.lat),
        (p.lat - origin.lat) * metersPerDegLat(),
    )

    /** Inverse of [toLocal]. */
    fun fromLocal(origin: LatLon, east: Double, north: Double): LatLon = LatLon(
        lat = origin.lat + north / metersPerDegLat(),
        lon = origin.lon + east / metersPerDegLon(origin.lat),
    )

    /**
     * Interpolate between two positions. Linear in degrees; over a resample step
     * of ten metres the difference to a great-circle interpolation is nanometres.
     */
    fun interpolate(a: LatLon, b: LatLon, f: Double): LatLon = LatLon(
        lat = a.lat + (b.lat - a.lat) * f,
        lon = a.lon + (b.lon - a.lon) * f,
    )

    /** Result of dropping a point onto a segment. */
    data class Projection(
        /** Fraction along the segment, clamped to [0,1]. */
        val t: Double,
        /** Perpendicular distance [m] from the point to the clamped foot. */
        val distanceM: Double,
    )

    /**
     * Project [p] onto the segment [a]-[b] in the local plane around [a].
     * Degenerate (zero-length) segments return t = 0.
     */
    fun projectToSegment(p: LatLon, a: LatLon, b: LatLon): Projection {
        val mLat = metersPerDegLat()
        val mLon = metersPerDegLon(a.lat)
        val abx = (b.lon - a.lon) * mLon
        val aby = (b.lat - a.lat) * mLat
        val apx = (p.lon - a.lon) * mLon
        val apy = (p.lat - a.lat) * mLat
        val len2 = abx * abx + aby * aby
        if (len2 <= 1e-12) {
            return Projection(0.0, sqrt(apx * apx + apy * apy))
        }
        val t = ((apx * abx + apy * aby) / len2).coerceIn(0.0, 1.0)
        val dx = apx - t * abx
        val dy = apy - t * aby
        return Projection(t, sqrt(dx * dx + dy * dy))
    }
}
