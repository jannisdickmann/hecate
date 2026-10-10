package de.hecate.phone.ui

import de.hecate.phone.db.SessionSummary

/**
 * Whether a recording stands out against the others on its route (US-8).
 *
 * Only ever set for a recording that matched a route: "fast" means nothing in
 * the abstract, only against the same ground covered before.
 */
data class Standout(val fast: Boolean = false, val highHr: Boolean = false)

/**
 * Marks the top of the range of speed and of average heart rate, per route.
 *
 * Pure Kotlin with no Android in it, in the spirit of NFR-4 — it is the only
 * arithmetic in the phone UI, and the kind whose bugs are invisible because a
 * wrong threshold still highlights *something*.
 */
object Standouts {

    /**
     * Recordings a route needs before ranking within it means anything.
     *
     * A route exists at three members (§5.6), and a percentile of three values is
     * "the best one" wearing a statistician's hat. Four is the first count at
     * which the threshold falls between two recordings rather than on one — and
     * even then only the best of them clears it, so this marks a personal best
     * against the route and is not claiming more than that.
     */
    const val MIN_MEMBERS = 4

    /**
     * Where "stands out" begins.
     *
     * The 85th percentile, raised from the 75th: a quarter of a long route's
     * history was too many to mean anything — on a commute ridden sixty times,
     * fifteen rows were emphasised and the eye stopped reading the emphasis as
     * emphasis at all. 0.85 marks roughly the best in seven, which is a mark
     * often enough to be worth noticing and rare enough to notice.
     *
     * Fewer marks would be invisible if they stayed a shade of colour and a
     * heavier weight, which is why the arrow came with the change: a mark that
     * appears on one row in seven has to be findable by scanning rather than by
     * comparing two rows side by side.
     */
    const val PERCENTILE = 0.85

    /**
     * Group by route, rank within it, keyed by recording id.
     *
     * Grouped by route because that is the only comparison that means anything:
     * a fast recording is one that was fast *over this ground*. Speed and heart
     * rate are ranked independently — a hard effort on a slow day is worth
     * seeing precisely because the two came apart.
     */
    fun compute(sessions: List<SessionSummary>): Map<String, Standout> {
        val out = HashMap<String, Standout>()
        for ((routeId, members) in sessions.groupBy { it.matchedRouteId }) {
            if (routeId == null || members.size < MIN_MEMBERS) continue

            val fastCut = percentile(members.mapNotNull(::speedOf), PERCENTILE)

            // Heart rate is ranked only over the members that have one, and only
            // if enough of them do: a route half of whose recordings predate a
            // heart rate strap must not let those absences drag the threshold
            // down to where everything qualifies.
            val hrs = members.mapNotNull { it.avgHr }
            val hrCut = if (hrs.size >= MIN_MEMBERS) percentile(hrs, PERCENTILE) else null

            for (m in members) {
                val fast = fastCut != null && speedOf(m).let { it != null && it >= fastCut }
                val highHr = hrCut != null && m.avgHr.let { it != null && it >= hrCut }
                if (fast || highHr) out[m.id] = Standout(fast = fast, highHr = highHr)
            }
        }
        return out
    }

    /** km/h over the active time, or null where there is not enough of either to
     *  divide — a zero-distance recording is not infinitely slow, it is unknown. */
    fun speedOf(s: SessionSummary): Double? = Format.speedFrom(s.totalDistanceM, s.activeDurationMs)

    /**
     * Linear-interpolation percentile — the same definition a spreadsheet's
     * PERCENTILE uses, so a threshold checked by hand agrees with this one.
     */
    fun percentile(values: List<Double>, p: Double): Double? {
        if (values.isEmpty()) return null
        val v = values.sorted()
        if (v.size == 1) return v[0]
        val rank = p * (v.size - 1)
        val lo = rank.toInt()
        val hi = (lo + 1).coerceAtMost(v.size - 1)
        return v[lo] + (rank - lo) * (v[hi] - v[lo])
    }
}
