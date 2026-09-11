package de.hecate.core

import de.hecate.core.geo.Geo
import de.hecate.core.geo.LatLon
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.Sample
import de.hecate.core.model.SessionEndReason
import de.hecate.core.model.SessionFooter
import de.hecate.core.model.SessionHeader
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Synthetic geometries for the unit tests (§6.5).
 *
 * Everything is generated from waypoints in local metres around a fixed origin
 * and walked at a fixed speed with seeded jitter, so a test that fails failed
 * because of the code, not because of a different random draw.
 */
object Synthetic {

    val ORIGIN = LatLon(50.1333, 8.9167) // Hanau

    fun at(east: Double, north: Double): LatLon = Geo.fromLocal(ORIGIN, east, north)

    /** Densify a waypoint list to roughly one point per [stepM]. */
    fun path(waypoints: List<LatLon>, stepM: Double = 5.0): List<LatLon> {
        val out = ArrayList<LatLon>()
        for (i in 0 until waypoints.size - 1) {
            val a = waypoints[i]
            val b = waypoints[i + 1]
            val d = Geo.haversine(a, b)
            val n = maxOf(1, (d / stepM).toInt())
            for (k in 0 until n) out.add(Geo.interpolate(a, b, k.toDouble() / n))
        }
        out.add(waypoints.last())
        return out
    }

    /**
     * Walk a path at [speedMps], emitting one sample per second with Gaussian
     * position noise of [jitterM].
     *
     * @param pauseAfterS insert a pause of [pauseDurationS] once this much active
     *        time has elapsed
     * @param dropoutAfterS drop [dropoutDurationS] of samples entirely
     */
    fun record(
        sessionId: String,
        points: List<LatLon>,
        startedAtUtcMs: Long,
        speedMps: Double = 3.0,
        jitterM: Double = 4.0,
        seed: Int = 42,
        type: ExerciseType = ExerciseType.RUNNING,
        accuracyM: Double? = 8.0,
        hrBase: Int = 145,
        pauseAfterS: Double = -1.0,
        pauseDurationS: Double = 0.0,
        dropoutAfterS: Double = -1.0,
        dropoutDurationS: Double = 0.0,
        tzOffsetMinutes: Int = 120,
    ): RecordedSession {
        val rnd = Random(seed)
        val samples = ArrayList<Sample>()

        // Resample the path to one point per second of travel.
        val perSecond = ArrayList<LatLon>()
        var carry = 0.0
        perSecond.add(points.first())
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val seg = Geo.haversine(a, b)
            if (seg <= 0.0) continue
            var d = speedMps - carry
            while (d <= seg) {
                perSecond.add(Geo.interpolate(a, b, d / seg))
                d += speedMps
            }
            carry = (carry + seg) % speedMps
        }

        var tMs = 0L
        var activeMs = 0L
        var cum = 0.0
        // GNSS error is not white noise. It is dominated by slowly varying bias
        // (multipath, satellite geometry) with a correlation time of a minute or
        // more, so it is modelled as an Ornstein-Uhlenbeck process. Independent
        // per-sample noise would inflate the length of a 1 Hz track by most of a
        // factor of two and would make every fixture meaningless.
        val alpha = 0.99
        val innovation = kotlin.math.sqrt(1.0 - alpha * alpha) * jitterM
        var errE = gauss(rnd) * jitterM
        var errN = gauss(rnd) * jitterM
        var paused = false
        var pauseLeftMs = 0L
        var dropLeftMs = 0L
        var prev: LatLon? = null

        for (p in perSecond) {
            if (pauseAfterS >= 0 && !paused && pauseLeftMs == 0L && activeMs >= pauseAfterS * 1000 &&
                samples.none { it.paused }
            ) {
                paused = true
                pauseLeftMs = (pauseDurationS * 1000).toLong()
            }
            if (paused) {
                while (pauseLeftMs > 0) {
                    samples.add(Sample(tMs, activeMs, p.lat, p.lon, hrBpm = hrBase - 25, accuracyM = accuracyM, paused = true))
                    tMs += 1000
                    pauseLeftMs -= 1000
                }
                paused = false
            }
            if (dropoutAfterS >= 0 && dropLeftMs == 0L && activeMs >= dropoutAfterS * 1000 &&
                tMs <= (dropoutAfterS + dropoutDurationS) * 1000
            ) {
                dropLeftMs = (dropoutDurationS * 1000).toLong()
                while (dropLeftMs > 0) {
                    tMs += 1000
                    activeMs += 1000
                    dropLeftMs -= 1000
                }
            }

            errE = alpha * errE + innovation * gauss(rnd)
            errN = alpha * errN + innovation * gauss(rnd)
            val jLat = p.lat + errN / Geo.metersPerDegLat()
            val jLon = p.lon + errE / Geo.metersPerDegLon(p.lat)
            prev?.let { cum += Geo.haversine(it, p) }
            prev = p
            samples.add(
                Sample(
                    tOffsetMs = tMs,
                    activeMs = activeMs,
                    lat = jLat,
                    lon = jLon,
                    altM = 105.0,
                    hrBpm = hrBase + ((sin(tMs / 60000.0) * 8).toInt()),
                    accuracyM = accuracyM,
                    cumDistanceM = cum,
                ),
            )
            tMs += 1000
            activeMs += 1000
        }

        return RecordedSession(
            header = SessionHeader(
                sessionId = sessionId,
                startedAtUtcMs = startedAtUtcMs,
                tzOffsetMinutes = tzOffsetMinutes,
                exerciseType = type,
            ),
            samples = samples,
            footer = SessionFooter(
                endedAtUtcMs = startedAtUtcMs + tMs,
                activeDurationMs = activeMs,
                totalDistanceM = cum,
                reason = SessionEndReason.USER_STOPPED,
            ),
        )
    }

    private fun gauss(r: Random): Double {
        // Box-Muller; one draw is enough and keeps the sequence short and stable.
        val u1 = r.nextDouble().coerceAtLeast(1e-9)
        val u2 = r.nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * Math.PI * u2)
    }

    // ------------------------------------------------------------- the fixtures

    /** The work route: 2 km east, 1.5 km north, 1 km east. */
    val workRoute: List<LatLon> = path(
        listOf(at(0.0, 0.0), at(2000.0, 0.0), at(2000.0, 1500.0), at(3000.0, 1500.0)),
    )

    /** A shortcut that leaves the work route after 2 km and cuts the corner. */
    val shortcut: List<LatLon> = path(
        listOf(at(0.0, 0.0), at(2000.0, 0.0), at(3000.0, 1500.0)),
    )

    /** A second shortcut that diverges earlier. */
    val shortcut2: List<LatLon> = path(
        listOf(at(0.0, 0.0), at(1000.0, 0.0), at(1000.0, 1500.0), at(3000.0, 1500.0)),
    )

    /** The work route walked backwards. */
    val reverseRoute: List<LatLon> = workRoute.asReversed()

    /** A path that shares nothing with the others. */
    val novelRoute: List<LatLon> = path(
        listOf(at(0.0, 0.0), at(-1500.0, 500.0), at(-1500.0, 2500.0), at(-3000.0, 2500.0)),
    )

    /** A route diverging from the work route by 5 degrees — the hardest case for
     *  the coverage metric, because it stays close for a long time. */
    val fiveDegreeDivergence: List<LatLon> = path(
        listOf(
            at(0.0, 0.0),
            at(2000.0 * cos(Math.toRadians(5.0)), 2000.0 * sin(Math.toRadians(5.0))),
            at(2000.0, 1500.0),
            at(3000.0, 1500.0),
        ),
    )

    /** A self-intersecting figure of eight: the case the windowed projection of
     *  §5.6 exists for. */
    val figureEight: List<LatLon> = path(
        listOf(
            at(0.0, 0.0), at(500.0, 500.0), at(1000.0, 0.0), at(500.0, -500.0),
            at(0.0, 0.0), at(-500.0, 500.0), at(-1000.0, 0.0), at(-500.0, -500.0),
            at(0.0, 0.0),
        ),
    )

    /** Monday 2024-03-04, 07:30 UTC. */
    const val MONDAY_0730_UTC = 1_709_537_400_000L
    const val DAY_MS = 86_400_000L
}
