package de.hecate.phone.ui

import de.hecate.core.Tuning
import de.hecate.core.geo.LatLon
import de.hecate.core.model.ActiveClock
import de.hecate.core.model.MatchEvent
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.Sample
import de.hecate.core.model.SessionEvent
import de.hecate.core.profile.Analysis
import de.hecate.core.profile.Elevation
import de.hecate.core.profile.Series
import de.hecate.core.profile.Timeline
import de.hecate.core.weather.WeatherSummary
import de.hecate.core.weather.WeatherTrack
import de.hecate.phone.db.RouteEntity
import de.hecate.phone.db.SampleEntity
import de.hecate.phone.db.SessionEntity
import de.hecate.phone.db.SessionSummary
import de.hecate.phone.db.SessionWeatherEntity
import de.hecate.phone.db.toSummary
import kotlin.math.roundToInt

/**
 * A recording's rows in, everything its screen draws out.
 *
 * Out of the view model so that it is a function of its arguments: the
 * screenshot harness builds a detail from synthetic rows through exactly the
 * code the app uses, and nothing in here needs a database to run.
 */
object SessionDetailBuilder {

    /** Points kept for a chart — elevation, speed or heart rate. A phone is a
     *  few hundred pixels wide and an hour's ride is thousands of samples —
     *  see [Elevation.simplify], which keeps the peaks rather than averaging
     *  them away, and [Timeline.simplify], which deliberately does not. */
    const val CHART_POINTS = 400

    fun build(
        entity: SessionEntity,
        samples: List<SampleEntity>,
        route: RouteEntity?,
        routeMembers: List<SessionSummary>,
        log: List<MatchLogEntry>,
        events: List<SessionEvent>,
    ): SessionDetail {
        // Paused samples are dropped once, here, so that the track drawing, the
        // altitude profile and the smoothed heart rate all index the same series
        // — the colours would otherwise slide along the line by however many
        // samples a level crossing took.
        val moving = samples.filterNot { it.paused }

        // Heart rate is smoothed before anything reads it, and for a reason that
        // has nothing to do with the sensor being noisy: the screen quantises it
        // into four bands, so a steady effort sitting on a boundary would cross
        // it every few seconds and the drawing would alternate between two
        // colours every few pixels. See Tuning.HR_MEDIAN_WINDOW.
        //
        // Held across short holes before anything else touches it: a watch
        // stamps a rate only on the samples whose batch happened to carry one,
        // which is about a third of them, and read literally that is a ride
        // whose effort looks unmeasured for two thirds of its length. See
        // Tuning.HR_HOLD_MS.
        val heartRate = Series.rollingMedian(
            Series.hold(
                DoubleArray(moving.size) { i ->
                    moving[i].hrBpm?.takeIf { it > 0 }?.toDouble() ?: Double.NaN
                },
                LongArray(moving.size) { moving[it].tOffsetMs },
                Tuning.HR_HOLD_MS,
            ),
            Tuning.HR_MEDIAN_WINDOW,
        )
        fun hrAt(i: Int): Double? = heartRate[i].takeIf { !it.isNaN() }

        // One list of samples for all three charts, carrying the *smoothed* heart
        // rate: they must colour the same second the same way, and three
        // conversions of the same rows would be three chances for them not to.
        //
        // On the repaired clock, because two of the three are drawn against
        // time: as recorded, a watch session's active clock stands still for
        // minutes and then steps backwards, and the speed chart it produced was
        // the fault that found it (`ActiveClock`).
        val charted = ActiveClock.repair(
            moving.mapIndexed { i, it ->
                Sample(
                    tOffsetMs = it.tOffsetMs,
                    activeMs = it.activeMs,
                    lat = it.lat,
                    lon = it.lon,
                    altM = it.altM,
                    hrBpm = hrAt(i)?.roundToInt(),
                    paused = it.paused,
                )
            },
        )
        val sessionElevation = Elevation.of(charted)
        val chartedSpeed = Timeline.simplify(Timeline.speedOfSamples(charted), CHART_POINTS)
        val chartedHeartRate = Timeline.simplify(Timeline.heartRateOfSamples(charted), CHART_POINTS)

        return SessionDetail(
            summary = entity.toSummary(route?.name, route?.temporary ?: false),
            geometry = moving.mapIndexed { i, it -> TrackPoint(LatLon(it.lat, it.lon), hrAt(i)) },
            routeName = route?.name,
            routeTemporary = route?.temporary ?: false,
            temporaryRouteSeed = entity.temporaryRoute,
            coverage = entity.matchCoverage,
            committedAtM = entity.committedAtM,
            hcState = entity.hcWriteState,
            hcError = entity.hcError,
            matchLogSize = log.size,
            committedByPriority = log.lastOrNull {
                it.event == MatchEvent.COMMIT ||
                    it.event == MatchEvent.COMMIT_BY_PRIORITY
            }?.let { it.event == MatchEvent.COMMIT_BY_PRIORITY },
            // Only the interesting lines: commits, demotions, deviations.
            matchEvents = log.filter { it.event != MatchEvent.SAMPLE }.take(50),
            // Uncapped on purpose: a handful per session, and the one that
            // matters is usually the one you did not expect to be there.
            sessionEvents = events,
            elevation = Elevation.simplify(sessionElevation, CHART_POINTS),
            elevationStats = Elevation.stats(sessionElevation),
            // Stats off the *drawn* series, not the raw one: the line under a
            // chart says what that chart covers, and on a long recording the
            // drawn series is smoothed to the scale it can show (`simplify`).
            // A caption naming a peak that is nowhere on the picture above it
            // is a caption about a different chart.
            speed = chartedSpeed,
            speedStats = Timeline.stats(chartedSpeed),
            heartRate = chartedHeartRate,
            heartRateStats = Timeline.stats(chartedHeartRate),
            moments = Analysis.moments(charted),
            charted = charted,
            splits = Analysis.splits(charted),
            routeDurationsMs = routeMembers.map { it.activeDurationMs },
        )
    }
}

/** What the weather card shows for a stored row (or none) and the switch. */
fun weatherViewOf(row: SessionWeatherEntity?, enabled: Boolean): WeatherView {
    if (row == null) return if (enabled) WeatherView.Pending else WeatherView.Off
    val track = WeatherTrack.decode(row.payload) ?: return WeatherView.Unavailable
    val summary = WeatherSummary.of(track.readings) ?: return WeatherView.Unavailable
    return WeatherView.Ready(track, summary)
}
