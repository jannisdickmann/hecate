package de.hecate.core.model

import de.hecate.core.Tuning

/**
 * How hard a moment of a recording was, as a fraction of the user's own peak
 * heart rate.
 *
 * Four bands, because this exists to be *seen*: the track drawing and the
 * altitude profile are coloured by it, and a reader tells four colours apart at
 * a glance and seven never. The cuts are the conventional 70 / 80 / 90 % of
 * maximum — see [Tuning.HR_ZONE_MODERATE_FRACTION] for why they are fractions of
 * the peak rather than of the heart-rate reserve.
 *
 * In `:core` rather than beside the two views that colour by it, for the reason
 * NFR-4 gives: it is arithmetic with boundaries in it, both ends of the app will
 * want it, and a laptop can check it. The colours themselves are not here — they
 * are a property of the screen, and the watch has a different one.
 */
enum class Intensity(
    /** What the legend calls this band. */
    val label: String,
    /** Inclusive lower bound, as a fraction of the peak heart rate. */
    val lowerFraction: Double,
) {
    EASY("Leicht", 0.0),
    MODERATE("Mittel", Tuning.HR_ZONE_MODERATE_FRACTION),
    HARD("Intensiv", Tuning.HR_ZONE_HARD_FRACTION),
    PEAK("Höchstleistung", Tuning.HR_ZONE_PEAK_FRACTION),
    ;

    companion object {

        /**
         * The band [hrBpm] falls in against [peakHrBpm], or null when there is
         * nothing to say.
         *
         * Null rather than [EASY] for a missing or impossible reading, and the
         * distinction matters on screen: a recording from before the user owned
         * a strap is drawn in the neutral colour, not in the colour of an easy
         * day. Zero and negative rates are missing data wearing a number — an
         * optical sensor that has lost the wrist reports them — and a rate above
         * the peak is not an error but the point of having a top band, so it
         * saturates into [PEAK] rather than being rejected.
         */
        fun of(hrBpm: Double?, peakHrBpm: Int = Tuning.DEFAULT_PEAK_HR_BPM): Intensity? {
            if (hrBpm == null || !hrBpm.isFinite() || hrBpm <= 0.0) return null
            if (peakHrBpm <= 0) return null
            val fraction = hrBpm / peakHrBpm
            // Highest band first: the bounds are lower bounds, so the first one
            // the value clears is the one it belongs to.
            return entries.last { fraction >= it.lowerFraction }
        }
    }
}
