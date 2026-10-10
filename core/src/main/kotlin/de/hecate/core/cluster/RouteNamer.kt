package de.hecate.core.cluster

import de.hecate.core.model.ExerciseType
import de.hecate.core.model.TimeBucket

/**
 * Auto-naming for newly created routes (US-3). Deliberately dull: a number plus
 * the time of day the route is usually done at, which is enough to tell two
 * routes apart in a list until the user renames them.
 */
object RouteNamer {

    private fun timeWord(b: TimeBucket): String = when (b) {
        TimeBucket.NIGHT -> "nachts"
        TimeBucket.EARLY -> "früh"
        TimeBucket.MORNING -> "vormittags"
        TimeBucket.MIDDAY -> "mittags"
        TimeBucket.AFTERNOON -> "nachmittags"
        TimeBucket.EVENING -> "abends"
    }

    /**
     * @param ordinal a number unique among existing routes
     * @param localHours the local hour of day of each member, used for the hint
     */
    fun autoName(ordinal: Long, type: ExerciseType, localHours: List<Int>, lengthM: Double): String {
        val bucket = dominantBucket(localHours)
        val km = String.format(java.util.Locale.GERMANY, "%.1f", lengthM / 1000.0)
        return if (bucket == null) {
            "Route $ordinal (${type.routeWord}, $km km)"
        } else {
            "Route $ordinal (${type.routeWord} ${timeWord(bucket)}, $km km)"
        }
    }

    internal fun dominantBucket(localHours: List<Int>): TimeBucket? {
        if (localHours.isEmpty()) return null
        val counts = HashMap<TimeBucket, Int>()
        for (h in localHours) {
            val b = TimeBucket.of(h)
            counts.merge(b, 1, Int::plus)
        }
        // Ties broken by enum order so the name is deterministic.
        return TimeBucket.entries
            .filter { counts.containsKey(it) }
            .maxByOrNull { counts.getValue(it) * 100 - it.ordinal }
    }
}
