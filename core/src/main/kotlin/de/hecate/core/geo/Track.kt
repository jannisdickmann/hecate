package de.hecate.core.geo

import de.hecate.core.Tuning
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.Sample
import kotlin.math.abs

/**
 * A session reduced to what the clusterer and the profile builder need: an evenly
 * resampled path plus, for each resampled point, the active elapsed time at which
 * the user was there.
 *
 * Paused spans are dropped rather than interpolated across (§5.7). So are dropouts
 * — a 90 s GPS hole is not a straight line through somebody's garden — which means
 * a track can consist of several runs with gaps between them. Cumulative distance
 * is nevertheless continuous across a gap, because the user did travel that ground;
 * only the geometry between the two ends is unknown.
 */
class Track(
    val sessionId: String,
    val exerciseType: ExerciseType,
    val startedAtUtcMs: Long,
    val tzOffsetMinutes: Int,
    /** Evenly spaced positions, gaps removed. */
    val points: List<LatLon>,
    /** Active elapsed time [s] at each point. Parallel to [points]. */
    val timeS: DoubleArray,
    /** Index of the first point of each run; used to avoid measuring across gaps. */
    val runStarts: IntArray,
    val totalDistanceM: Double,
    val activeDurationS: Double,
) {
    val size: Int get() = points.size

    /** The path as a polyline. Segments that bridge a gap are still present; the
     *  coverage metric only uses vertices, so they do no harm. */
    val polyline: Polyline by lazy { Polyline.of(points) }

    /** The same path reversed, used to test for direction reversals. */
    fun reversed(): List<LatLon> = points.asReversed()

    companion object {

        /** Build a track from a recorded session. Returns null if too little is usable. */
        fun of(session: RecordedSession, spacingM: Double = Tuning.RESAMPLE_M): Track? {
            val runs = splitRuns(session.samples)
            if (runs.isEmpty()) return null

            val points = ArrayList<LatLon>()
            val times = ArrayList<Double>()
            val starts = ArrayList<Int>()
            for (run in runs) {
                if (run.size < 2) continue
                val p = run.map { LatLon(it.lat, it.lon) }
                val v = DoubleArray(run.size) { run[it].activeMs / 1000.0 }
                val (rp, rv) = Resampler.resampleWithValue(p, v, spacingM)
                if (rp.size < 2) continue
                starts.add(points.size)
                points.addAll(rp)
                for (x in rv) times.add(x)
            }
            if (points.size < 2) return null

            var dist = 0.0
            var s = 0
            for (i in 1 until points.size) {
                if (s + 1 < starts.size && i == starts[s + 1]) s++
                dist += Geo.haversine(points[i - 1], points[i])
            }
            return Track(
                sessionId = session.id,
                exerciseType = session.header.exerciseType,
                startedAtUtcMs = session.header.startedAtUtcMs,
                tzOffsetMinutes = session.header.tzOffsetMinutes,
                points = points,
                timeS = times.toDoubleArray(),
                runStarts = starts.toIntArray(),
                totalDistanceM = session.footer?.totalDistanceM?.takeIf { it > 0 } ?: dist,
                activeDurationS = session.activeDurationMs / 1000.0,
            )
        }

        /**
         * Split the sample stream into continuous, usable runs: drop paused and
         * low-quality samples, and cut wherever the stream skipped in time or space.
         */
        fun splitRuns(samples: List<Sample>): List<List<Sample>> {
            val runs = ArrayList<List<Sample>>()
            var cur = ArrayList<Sample>()
            var prev: Sample? = null
            for (s in samples) {
                if (!s.usable) {
                    if (cur.size >= 2) runs.add(cur)
                    cur = ArrayList()
                    prev = null
                    continue
                }
                val p = prev
                if (p != null) {
                    val dt = s.tOffsetMs - p.tOffsetMs
                    val dm = Geo.haversine(p.lat, p.lon, s.lat, s.lon)
                    if (dt > Tuning.MAX_INTERPOLATION_GAP_MS || dm > Tuning.MAX_INTERPOLATION_GAP_M) {
                        if (cur.size >= 2) runs.add(cur)
                        cur = ArrayList()
                    }
                }
                cur.add(s)
                prev = s
            }
            if (cur.size >= 2) runs.add(cur)
            return runs
        }

        /** True when the two sessions are plausibly the same workout (§7, US-9). */
        fun likelyDuplicate(
            startAUtcMs: Long, durationAMs: Long,
            startBUtcMs: Long, durationBMs: Long,
        ): Boolean {
            if (abs(startAUtcMs - startBUtcMs) > Tuning.DUPLICATE_START_WINDOW_MS) return false
            val longer = maxOf(durationAMs, durationBMs).coerceAtLeast(1)
            return abs(durationAMs - durationBMs).toDouble() / longer <= 0.25
        }
    }
}
