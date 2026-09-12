package de.hecate.phone.ui

import de.hecate.phone.db.SessionSummary

/**
 * Whether a recording stands out against the others on its route (US-8).
 *
 * Only ever set for a recording that matched a route: "fast" means nothing in
 * the abstract, only against the same ground covered before.
 */
data class Standout(val fast: Boolean = false, val highHr: Boolean = false) {
    val any: Boolean get() = fast || highHr
}

/**
 * Marks the upper quartile of speed and of average heart rate, per route.
 *
 * Pure Kotlin with no Android in it, in the spirit of NFR-4 — it is the only
 * arithmetic in the phone UI, and the kind whose bugs are invisible because a
 * wrong threshold still highlights *something*.
 */
object Standouts {

    /**
     * Recordings a route needs before its upper quartile means anything.
     *
     * A route exists at three members (§5.6), and the 75th percentile of three
     * values is "the best one" wearing a statistician's hat. Four is the first
     * count at which the top quartile is genuinely a quarter — and even then it
     * is one recording, so this marks a personal best against the route and is
     * not claiming more than that.
     */
    const val MIN_MEMBERS = 4

    /** Where "stands out" begins. 0.75 marks roughly the best in four; raise it
     *  to 0.9 if a quarter of the list turns out to be too many to mean
     *  anything — see OPEN-POINTS §3.7. */
    const val QUARTILE = 0.75

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

            val fastCut = percentile(members.mapNotNull(::speedOf), QUARTILE)

            // Heart rate is ranked only over the members that have one, and only
            // if enough of them do: a route half of whose recordings predate a
            // heart rate strap must not let those absences drag the threshold
            // down to where everything qualifies.
            val hrs = members.mapNotNull { it.avgHr }
            val hrCut = if (hrs.size >= MIN_MEMBERS) percentile(hrs, QUARTILE) else null

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
    fun speedOf(s: SessionSummary): Double? {
        if (s.totalDistanceM <= 0 || s.activeDurationMs <= 0) return null
        return (s.totalDistanceM / 1000.0) / (s.activeDurationMs / 3_600_000.0)
    }

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
