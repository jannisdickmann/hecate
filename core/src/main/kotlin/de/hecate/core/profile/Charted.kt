package de.hecate.core.profile

import de.hecate.core.model.Sample

/*
 * What a recording's charts agree on before any of them computes anything.
 *
 * The altitude profile and the speed chart are drawn side by side and coloured
 * from the same heart rate, so they must index the same samples and read the
 * same rate off each one; otherwise the colours slide along one line and not
 * the other. Written once here rather than once per chart for that reason.
 */

/** The samples a recording's charts are drawn from: not paused, and with a
 *  position to measure distance from. */
internal fun List<Sample>.charted(): List<Sample> =
    filter { !it.paused && it.lat.isFinite() && it.lon.isFinite() }

/** The heart rate a chart is coloured by, or null where there was none — a
 *  zero from the strap is a missing reading, not a resting heart. */
internal val Sample.chartHr: Double? get() = hrBpm?.takeIf { it > 0 }?.toDouble()
