package de.hecate.phone.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.hecate.core.Tuning
import de.hecate.core.profile.Timeline

/**
 * Speed against time, coloured by effort.
 *
 * An adapter over [BandedChart], like [ElevationView], and for the same reason:
 * the only things that are particular to a speed chart are what its axes mean,
 * how flat a ride has to be before the chart stops magnifying it
 * ([FLAT_SPAN_KMH]), and that a dropout breaks the line instead of being drawn
 * across.
 *
 * The colour is the heart rate, not the speed. That pairing is the whole point
 * of having this chart beside the altitude one: 30 km/h in blue is a descent,
 * 30 km/h in orange is work, and no single-colour speed trace says which.
 */
@Composable
fun SpeedView(
    points: List<Timeline.Point>,
    modifier: Modifier = Modifier,
    height: Dp = 90.dp,
    peakHrBpm: Int = Tuning.DEFAULT_PEAK_HR_BPM,
) {
    BandedChart(
        points = points.map { ChartPoint(it.tMs.toDouble(), it.value, it.hrBpm) },
        modifier = modifier,
        height = height,
        peakHrBpm = peakHrBpm,
        minSpanY = FLAT_SPAN_KMH,
        gapX = GAP_MS,
    )
}

/**
 * Heart rate against time, coloured by itself.
 *
 * Colouring a heart rate by the heart rate is not a tautology on this screen: it
 * is what puts the four zone boundaries onto the trace, so that the chart
 * answers "how long was I in the orange" without an axis to read. The line is
 * the number; the colour is which band the number is in.
 */
@Composable
fun HeartRateView(
    points: List<Timeline.Point>,
    modifier: Modifier = Modifier,
    height: Dp = 90.dp,
    peakHrBpm: Int = Tuning.DEFAULT_PEAK_HR_BPM,
) {
    BandedChart(
        points = points.map { ChartPoint(it.tMs.toDouble(), it.value, it.hrBpm) },
        modifier = modifier,
        height = height,
        peakHrBpm = peakHrBpm,
        minSpanY = FLAT_SPAN_BPM,
        gapX = GAP_MS,
    )
}

/** The least speed range [km/h] a chart is drawn across. Below this the ride was
 *  steady, and steady is the fact worth showing. */
private const val FLAT_SPAN_KMH = 5.0

/** The least heart-rate range [bpm] a chart is drawn across. A flat hour at 140
 *  is a flat hour, not a ragged line across the full height of a card. */
private const val FLAT_SPAN_BPM = 20.0

/** How long a hole in a series [ms] before the line stops rather than being
 *  drawn across it: the same constant that decides a dropout everywhere else,
 *  so a gap is a gap by one definition rather than by three. */
private const val GAP_MS = Tuning.MAX_INTERPOLATION_GAP_MS.toDouble()
