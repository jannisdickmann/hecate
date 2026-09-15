package de.hecate.phone.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.hecate.core.Tuning
import de.hecate.core.profile.Elevation

/**
 * The altitude profile under the track: height against distance travelled,
 * coloured by how hard the user was working when they were there.
 *
 * Nothing but an adapter now. [BandedChart] draws every chart in the app and
 * carries the reasoning about exaggerated axes, the fill under the line and the
 * intensity colours; this says what the x and y of an altitude profile are, and
 * that a flat valley still gets [FLAT_SPAN_M] of band to sit in rather than
 * being magnified into an alp.
 *
 * Half the track's height, because it is the second thing on the screen and a
 * chart that competes with the map for attention wins an argument it should not
 * be having.
 *
 * There is no gap rule here: [Elevation.of] advances distance across a dropout,
 * because the ground was covered even where its shape is unknown, so successive
 * points are never far apart on the x axis in a way the chart could detect.
 */
@Composable
fun ElevationView(
    points: List<Elevation.Point>,
    modifier: Modifier = Modifier,
    height: Dp = 110.dp,
    peakHrBpm: Int = Tuning.DEFAULT_PEAK_HR_BPM,
) {
    BandedChart(
        points = points.map { ChartPoint(it.distanceM, it.altM, it.hrBpm) },
        modifier = modifier,
        height = height,
        peakHrBpm = peakHrBpm,
        minSpanY = FLAT_SPAN_M,
    )
}

/** The least altitude [m] a profile is drawn across. Ten metres of hill drawn
 *  over 110 dp is already an exaggeration; less would be drawing noise. */
private const val FLAT_SPAN_M = 10.0
