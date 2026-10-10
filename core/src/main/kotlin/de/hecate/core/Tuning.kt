package de.hecate.core

import de.hecate.core.model.ExerciseType

/**
 * NFR-5: every tuning constant in the system lives here, with its unit and the
 * reasoning behind its value. Nothing algorithmic may hardcode a number that a
 * future tuning session would want to change.
 *
 * Values were chosen from the geometry of the problem (consumer GNSS accuracy is
 * 3-10 m in the open, worse between buildings; urban route spacing is rarely below
 * 20 m) and are expected to be revisited against the fixture set (§6.5) rather than
 * trusted blindly.
 */
object Tuning {

    // ---------------------------------------------------------------- geometry

    /** Spacing [m] at which every track and route polyline is resampled.
     *  Small enough that a 10 m bin is below GNSS noise, large enough that an
     *  hour-long 12 km run is only 1200 points. Also the profile bin width. */
    const val RESAMPLE_M: Double = 10.0

    /** Edge length [m] of a spatial grid cell in [de.hecate.core.matcher.GridIndex].
     *  100 m keeps the per-cell candidate list short while a MATCH_RADIUS_M query
     *  still only has to touch a 3x3 neighbourhood. */
    const val GRID_CELL_M: Double = 100.0

    /** Earth radius [m] used by the haversine formula (WGS-84 mean radius). */
    const val EARTH_RADIUS_M: Double = 6_371_008.8

    // ----------------------------------------------------------------- matcher

    /** A route is only considered a candidate while some part of its geometry is
     *  within this distance [m] of the current sample. Generous, because the point
     *  of the weights is to discriminate — the grid query only has to not lose the
     *  right answer. */
    const val MATCH_RADIUS_M: Double = 120.0

    /** A candidate that has stayed outside MATCH_RADIUS_M for this much travelled
     *  distance [m] is dropped from the active set. Travel, not time, so that a
     *  long pause at a traffic light never drops anything. */
    const val CANDIDATE_DROP_M: Double = 300.0

    /** How far *ahead* of the dead-reckoned position [m] a sample may be projected
     *  onto a candidate polyline. Must exceed the worst plausible error in the
     *  travelled distance over one sample interval. */
    const val SEARCH_WINDOW_M: Double = 150.0

    /** How far *behind* the last projection [m] a sample may be projected.
     *  Small, and that asymmetry is the point: position along a route advances,
     *  which is what keeps loops and out-and-backs unambiguous. Only enough slack
     *  to absorb projection jitter under GNSS noise. */
    const val BACKTRACK_SLACK_M: Double = 10.0

    /** Two places a route passes [m] that are this close in distance from the
     *  user are the same answer as far as GNSS can tell, and position along the
     *  route has to choose between them instead (`Polyline.localise`). Covers
     *  the two legs of an out-and-back a street's width apart, and the gap
     *  between a closed loop's start and its finish, which on "Abendrunde" was
     *  23 m — and stays well under the distance to a genuinely different street. */
    const val LOCALISE_TIE_M: Double = 40.0

    /** Net distance [m] a candidate's position has to run *backwards* along its
     *  route, while the user is moving, before the matcher concludes it sits on
     *  the wrong leg of ground the route covers twice and moves it to the other
     *  one. Comfortably above what GNSS jitter at a standstill adds up to, and
     *  reached in about twenty seconds of walking. */
    const val BRANCH_SWITCH_RETREAT_M: Double = 30.0

    /** ...and the candidate's advance per metre covered ([TRACK_RATIO_WINDOW_M])
     *  has to be below this: net backwards over the whole window, not once. A
     *  GNSS spike forwards and back costs a drawdown the size of the spike but
     *  covers no distance; following the wrong leg reads as -1. */
    const val BRANCH_SWITCH_MAX_RATIO: Double = 0.0

    /** Uncertainty [m] in how far along a route one sample interval should have
     *  moved the user, given how far they demonstrably travelled. Floor; for fast
     *  movement [ADVANCE_SIGMA_FRACTION] of the step is used instead.
     *
     *  This is the transition half of the model, and it is what actually decides
     *  direction: a route being run the wrong way round projects the user
     *  *backwards* by exactly as much as they went forwards, and no amount of
     *  geometric agreement excuses that. */
    const val ADVANCE_SIGMA_M: Double = 5.0
    const val ADVANCE_SIGMA_FRACTION: Double = 0.3

    /** How much travel [m] a candidate's advance-per-metre ratio is read over.
     *  Long enough that a U-turn's few metres of projection creeping back, or a
     *  GNSS spike out and back, are a small part of it; short enough that a
     *  route run the wrong way is rejected within half a minute of walking and a
     *  few seconds of riding. */
    const val TRACK_RATIO_WINDOW_M: Double = 40.0

    /** Below this straight-line distance [m] covered across the window the ratio
     *  is not read at all: it would be a few metres of jitter divided by a few
     *  more, which is a random number. */
    const val TRACK_RATIO_MIN_DISPLACEMENT_M: Double = 15.0

    /** Within this distance [m] of a route's end the advance ratio is not read:
     *  the position cannot advance past the finish, and walking the last metres
     *  to the door is not leaving the route. */
    const val FINISH_HOLD_M: Double = 30.0

    /** Below this smoothed ratio a candidate is not considered to be tracking at
     *  all. A route being followed reads about 1.0; a route being run backwards
     *  reads about -1.0; a route the user has left behind reads about 0.
     *
     *  A candidate that is not tracking is not an explanation of the journey, only
     *  of the current position, so it neither stays in the candidate set nor gets
     *  a say in whether the leader is unambiguous. */
    const val MIN_TRACKING_RATIO: Double = 0.5

    /** Floor for the GNSS error model sigma [m]. The reported accuracy is used when
     *  it is larger. Never zero: a sample claiming 1 m accuracy must not be allowed
     *  to annihilate a candidate on a single reading.
     *
     *  Deliberately above a good fix's reported accuracy, because the model has two
     *  error sources and the receiver only reports one: today's fix, and the
     *  learned geometry, which is one past recording's worth of exactly the same
     *  noise. Fifteen metres is roughly those two added in quadrature. */
    const val GPS_SIGMA_M: Double = 15.0

    /** The null hypothesis ("none of the known routes") is scored as if the user
     *  were permanently this far [m] from an imaginary route. Anything consistently
     *  closer than this to a real route beats "unknown"; anything further loses.
     *
     *  Must sit comfortably above [DEVIATION_M], and that ordering is a constraint,
     *  not a coincidence. Leaving a route by more than this makes "unknown" the
     *  better explanation, the match is demoted, and the deviation alert — which
     *  only fires while committed — is suppressed exactly when it was needed. A
     *  hundred-metre detour must still read as "on the route, but off it", or US-7
     *  cannot be satisfied at all. */
    const val NULL_DISTANCE_M: Double = 120.0

    /** Share of the initial probability mass reserved for the null hypothesis.
     *  High, deliberately: on any given day the most likely thing is that this is
     *  not one of the handful of learned routes. */
    const val NULL_PRIOR_FLOOR: Double = 0.45

    /** Minimum prior weight any single known route keeps, so that a route the user
     *  has not run on a Tuesday morning before is still reachable. */
    const val ROUTE_PRIOR_FLOOR: Double = 0.002

    /** How much a route's overall popularity counts relative to its popularity in
     *  the current (weekday, time-of-day) context. Pure context conditioning would
     *  be far too confident after a handful of recordings. */
    const val PRIOR_CONTEXT_BLEND: Double = 0.25

    /** Weight a route is re-admitted with after having been dropped, relative to the
     *  current total. Low enough not to disturb a settled belief, high enough that
     *  re-joining a route after a detour is recoverable. */
    const val REENTRY_WEIGHT: Double = 0.01

    /** Exponential forgetting applied to accumulated log-weights before each new
     *  observation. 1.0 would make an early mistake permanent; 0.97 gives the
     *  evidence a half-life of roughly 23 samples (~23 s at 1 Hz).
     *
     *  Forgetting is not only about mistakes. GNSS error is strongly correlated
     *  from one second to the next, so consecutive samples are nowhere near
     *  independent observations; without decay the matcher would count the same
     *  piece of evidence sixty times a minute and reach certainty it has not
     *  earned. */
    const val WEIGHT_DECAY: Double = 0.97

    /** Per-sample log-likelihood is clamped to this magnitude so a single wild
     *  outlier (tunnel, multipath) cannot eliminate a candidate outright.
     *
     *  Must stay above the null hypothesis's own log-likelihood, or a route a
     *  kilometre away and the null would both clamp to the same value and become
     *  indistinguishable — which would stop the matcher ever saying "Unbekannt". */
    const val MAX_ABS_LOG_LIK: Double = 60.0

    // -------------------------------------------------------------- commitment

    /** How far [m] the user has to have followed a route — along the route
     *  itself, from where they joined it — before the matcher commits to it.
     *
     *  Along the route, not along the session. It used to be the session's
     *  travelled distance, plus twenty samples of weight: a ride of 10 Oct went
     *  eleven kilometres nowhere near a learned route, met the end of the
     *  commute home a hundred metres from the front door, and committed to it
     *  there — as three weekend rides that came home the same way had before.
     *  Four hundred metres is about a minute on a bike and five on foot, and
     *  a route's last hundred metres are no longer taken for the route. */
    const val COMMIT_MIN_M: Double = 400.0

    /** Before a commitment, a route the user set off on at the session start
     *  is named as the guess it is once they have followed it this far [m].
     *  Far enough that the direction has been read off more than one
     *  [TRACK_RATIO_WINDOW_M], and that every variant of a commute has been
     *  found under the user: the two ways home start 25 m apart, and at fifty
     *  metres the lower-ranked one was named for a second before the other.
     *  A route found under the user later in the session is not guessed at,
     *  only committed to. */
    const val PROVISIONAL_MIN_M: Double = 100.0

    /** A route found under the user within this much travel [m] of the start
     *  of the session was set off on there, and is compared from the start of
     *  the workout. Found later, it was joined on the way, and ahead/behind on
     *  it is measured from where it was joined. */
    const val JOIN_AT_START_M: Double = 200.0

    /** How far [m] the user may travel while the named route does not fit —
     *  not followed, or outside [COMMIT_SEPARATION_M] of the nearest route —
     *  before it is given up. A route medoid is one past recording: a ride of
     *  27 Apr ran a hundred metres beside "Arbeit zurück Baiersdorf" for three
     *  hundred metres and cut back across to it, and at fifty the name went
     *  out for several seconds. Leaving a route for real is reported by the
     *  deviation alert on its own terms; this only decides how long the name
     *  stays up. */
    const val FOCUS_RELEASE_M: Double = 100.0

    /** Manual route priority: the range the user may assign, and the value every
     *  route starts at.
     *
     *  Priority breaks the ties that evidence cannot (see [COMMIT_SEPARATION_M]):
     *  of several routes over the same ground, joined at the same point, the
     *  highest-ranked is named. With equal priorities one of them is named all
     *  the same — the one already named, else the heaviest — because their
     *  ahead/behind there is the same; the priority only says which name.
     *
     *  Only the *order* matters — nothing multiplies by a priority — so the range
     *  is as small as it can be while still saying the four things a person means:
     *  never this one, rather not, no opinion, rather this one, always this one.
     *  Signed and centred on zero, because "no opinion" is what almost every route
     *  should be, and a scale whose neutral value is 5 out of 10 invites the user
     *  to spend the other nine. */
    const val MIN_ROUTE_PRIORITY: Int = -2
    const val MAX_ROUTE_PRIORITY: Int = 2
    const val DEFAULT_ROUTE_PRIORITY: Int = 0

    /** Routes that are followed and lie within this distance [m] of the nearest
     *  followed route are "contenders": equally good explanations of where the
     *  user is.
     *
     *  Where two routes coincide they explain the samples equally well, and the
     *  difference in their weights is a measurement of the GNSS bias of the last
     *  minute, not of which route the user is on. So the weights never choose
     *  between contenders: the route followed since the start does, then the
     *  user's own priority, then whichever is already named. */
    const val COMMIT_SEPARATION_M: Double = 50.0

    /** Once the null hypothesis holds this much weight the display says "Unbekannt"
     *  and every route-relative metric is suppressed. */
    const val NULL_DOMINANT_WEIGHT: Double = 0.70

    // ------------------------------------------------------------ live metrics

    /** A route found under the user at the start of a session counts as set
     *  off on ([JOIN_AT_START_M]) only within this fraction of its length from
     *  its start. Further along it is far more likely the way back, found near
     *  its finish beside the front door, and it is treated as joined on the
     *  way instead. */
    const val JOIN_ANCHOR_MAX_FRACTION: Double = 0.5

    /** A route member that starts or stops recording within this distance [m]
     *  of the route's own start or end is carried the rest of the way at its
     *  own pace when the profile is built (`ProfileBuilder.extendEnds`), so the
     *  first and last bins are medians over everybody rather than over whoever
     *  happened to start earliest or stop latest. */
    const val PROFILE_END_EXTEND_M: Double = 100.0

    /** Clamp on the observed-vs-median pace ratio k used for the ETA. A ratio
     *  outside this range is a measurement problem, not a performance. */
    const val ETA_K_MIN: Double = 0.60
    const val ETA_K_MAX: Double = 1.80

    /** Number of samples the ETA ratio is averaged over. ~1 min at 1 Hz: long
     *  enough that the arrival time does not jitter, short enough to react to a
     *  genuine slowdown. */
    const val ETA_SMOOTH_SAMPLES: Int = 60

    // ---------------------------------------------------------------- deviation

    /** Sustained distance [m] from every live candidate that counts as "off route".
     *  Comfortably above both GNSS noise and the width of a road, and comfortably
     *  below [NULL_DISTANCE_M] — see the note there. */
    const val DEVIATION_M: Double = 60.0

    /** Number of consecutive samples above DEVIATION_M before the alert fires.
     *  A single GPS jump is not a wrong turn (US-7). */
    const val DEVIATION_HOLD_SAMPLES: Int = 15

    /** Coming back within this distance [m] re-arms the alert. Hysteresis: without
     *  a lower clear threshold the alert would chatter along the boundary. */
    const val DEVIATION_CLEAR_M: Double = 30.0

    // --------------------------------------------------------------- clustering

    /** Tolerance [m] for "this point of track A lies on track B". Twice the typical
     *  open-sky error, so that the same street walked twice always matches. */
    const val CLUSTER_TOL_M: Double = 25.0

    /** Agglomerative cut on distance = 1 - coverage. 0.25 means two tracks are the
     *  same route when at least 75% of each lies on the other — enough slack for a
     *  different start point or a lap around the block at the end. */
    const val CLUSTER_CUT: Double = 0.25

    /** A cluster becomes an active route at this many members (US-3). */
    const val MIN_MEMBERS: Int = 3

    /** Fraction of a track's points whose projection onto the other track must
     *  advance in the same direction for the pair to count as the same direction.
     *  Below this the pair is treated as a reversal and never merged (US-3). */
    const val DIRECTION_AGREE_MIN: Double = 0.70

    /** Jaccard overlap of member sets at or above which a freshly computed cluster
     *  is considered to *be* an existing route, keeping its id and user-given name
     *  across re-clustering. */
    const val ROUTE_REIDENTIFY_OVERLAP: Double = 0.50

    // ------------------------------------------------------------------ ingest

    /** Two sessions whose start times are within this window [ms] and whose
     *  durations are comparable are considered the same workout. Used to reject
     *  TCX duplicates (US-9) and watch auto-detection duplicates (§7). */
    const val DUPLICATE_START_WINDOW_MS: Long = 5 * 60 * 1000L

    // ------------------------------------------------------------------ quality

    /** Samples with a reported accuracy worse than this [m] are recorded but not
     *  fed to the matcher or the clusterer. */
    const val MAX_USABLE_ACCURACY_M: Double = 60.0

    /** A gap larger than this [ms] between consecutive samples is treated as a
     *  dropout: no interpolation across it when resampling a track. */
    const val MAX_INTERPOLATION_GAP_MS: Long = 20_000L

    /** A gap larger than this [m] between consecutive samples is likewise treated
     *  as a dropout rather than a straight line through unknown terrain. */
    const val MAX_INTERPOLATION_GAP_M: Double = 200.0

    // ------------------------------------------------------------------ writing

    /** SessionWriter fsyncs at least this often [ms] (NFR-1) and always on
     *  pause/stop. 10 s is the specified worst-case data loss on power failure. */
    const val FSYNC_INTERVAL_MS: Long = 10_000L

    /** Consecutive matcher exceptions after which the matcher is disabled for the
     *  remainder of the session. Recording must never be endangered by it (US-1). */
    const val MATCHER_FAILURE_LIMIT: Int = 5

    // ------------------------------------------------------------------ heatmap

    /**
     * How far from home the app is willing to believe a coordinate [m].
     *
     * The reference point is the *median* start of all routes, which is robust:
     * half your routes would have to start abroad to move it. Anything beyond
     * this radius — a GPS fix that landed in the Gulf of Guinea, a holiday in
     * another country — is left out of the heatmap, because a single such point
     * would otherwise set the extent and squash every real route into one pixel.
     *
     * It excludes from the *drawing* only. Nothing is deleted and no route stops
     * existing; see Appendix A.14.
     */
    const val HEATMAP_MAX_RADIUS_M: Double = 100_000.0

    /**
     * Edge length [m] of one heatmap cell, on the ground.
     *
     * A fixed *physical* size, which is the whole point: the raster used to be
     * 192x108 cells whatever it covered, so a cell was 40 m across a town and
     * 400 m across a county, and the same two tracks a street apart merged into
     * one blob or stayed separate depending on where else the user had been. A
     * constant here means a constant on the ground, and the raster's dimensions
     * follow from the extent instead of the other way round.
     *
     * 15 m is the GNSS error this app already assumes ([GPS_SIGMA_M]) and below
     * the spacing of adjacent streets — which sounds like resolving noise, and
     * would be if a cell were painted as a cell. It is not:
     * [HEATMAP_BLUR_SIGMA_CELLS] spreads each track over its neighbours before
     * anything is drawn, so the cell is the sampling grid of a continuous
     * density, not a pixel. Sampling at the scale of the noise is what lets that
     * density come out smooth rather than blocky.
     */
    const val HEATMAP_CELL_M: Double = 15.0

    /**
     * The shape the card's extent is grown to, wide:high.
     *
     * The picture above the routes list is a card in a scrolling list, so its
     * shape is a layout decision rather than a fact about the ground: taller
     * than 16:9 and it pushes the routes themselves off their own screen. The
     * extent is *grown* to it, never cropped — a route drawn out of proportion
     * is a route you no longer recognise.
     *
     * The full-screen picture has no such constraint and is therefore not grown
     * to any shape at all: it covers what it covers, and what the screen cannot
     * fit, panning reaches. See `Heatmap.Extent`.
     */
    const val HEATMAP_ASPECT: Double = 16.0 / 9.0

    /**
     * Ceiling on the number of cells, whatever [HEATMAP_CELL_M] would ask for.
     *
     * The extent is bounded — nothing beyond [HEATMAP_MAX_RADIUS_M] of home is
     * drawn at all — but the bound is 100 km, and 15 m cells over that much
     * ground would be sixty million of them. Above this count the cell size
     * doubles until it fits, so the physical size stays constant *per doubling*
     * rather than sliding continuously with the extent, and the cells of two
     * rasters always line up.
     *
     * Three million is a 2300x1300 raster: 12 MB as counts, another 12 to blur
     * it and 12 more as the bitmap it is drawn from, and it takes an extent of
     * about 35 x 20 km before the first doubling. Everything a person rides
     * from one home is inside that; a second home 60 km away is what buys 30 m
     * cells, and a picture of two towns is not a picture of a street anyway.
     */
    const val HEATMAP_MAX_CELLS: Int = 3_000_000

    /**
     * Standard deviation [cells] of the blur applied to the counts before they
     * are coloured.
     *
     * This is what makes the picture read as density rather than as a
     * spreadsheet, and it is a blur rather than a coarser grid because the two
     * are not the same thing: a coarse grid quantises, a blur spreads. One and
     * a half cells at [HEATMAP_CELL_M] is about 22 m of ground — one GNSS error
     * — so a drawn track is as wide as the uncertainty about where it was,
     * which is the honest thickness for it to have. Wider than that and two
     * streets a block apart merge into one smear.
     */
    const val HEATMAP_BLUR_SIGMA_CELLS: Double = 1.5

    /** Breathing room around the ground the picture covers, as a fraction of
     *  its larger side. Without it the outermost track is the frame. */
    const val HEATMAP_PADDING_FRACTION: Double = 0.08

    /** Smallest extent the heatmap will draw [m]. Stops a single route, or one
     *  that doubles back on itself, from being magnified to absurdity. */
    const val HEATMAP_MIN_SPAN_M: Double = 500.0

    // ---------------------------------------------------------------- elevation

    /**
     * How far altitude has to move from the last counted point [m] before the
     * move is counted as climb or descent.
     *
     * Summing every positive difference between consecutive samples is the
     * obvious way to total an ascent and it is badly wrong: altitude is the
     * noisiest thing a watch reports, and at 1 Hz the noise alone accumulates
     * into hundreds of metres of imaginary climbing on a flat ride. The standard
     * remedy is this threshold — a move only counts once it has committed to a
     * direction by more than the noise.
     *
     * 5 m is chosen for a barometric altimeter, which is what a Pixel Watch 4 has
     * and what Health Services fuses: good to a metre or two, drifting with the
     * weather over hours. It is deliberately not tuned for bare GNSS altitude,
     * which is far worse and which nothing here is fed.
     *
     * The cost of the threshold is that genuinely gentle ground reads as flatter
     * than it was: a long drag that gains 4 m per kilometre counts as nothing.
     * That is the right trade for a number a person compares between days.
     *
     * Reconsidered once the watch actually delivered altitude, and deliberately
     * left alone. The barometer turns out to be far quieter than this: four
     * readings over flat ground spanned 0.65 m, so at its half-minute cadence
     * nothing like a 5 m false move can accumulate, and on the watch's own data
     * this threshold is now conservative enough to flatten genuinely gentle
     * ground. The reason it stays is that the same constant also governs every
     * takeout and TCX import, whose altitude is per position, denser, and much
     * noisier — and A.20's ski day at +9179/−9163 m is the only figure here that
     * has ever been checked against reality. Lowering it would trade a verified
     * number for an unverified one. It wants an outdoor ride recorded on the
     * wrist, read against a takeout of the same ground, and then a value chosen
     * per source rather than one for both.
     */
    const val ELEVATION_THRESHOLD_M: Double = 5.0

    /**
     * Width, in samples, of the rolling median that de-spikes altitude before
     * anything is read off it.
     *
     * Altimeters settle. The first seconds of a recording are routinely 10–25 m
     * out in either direction before the barometer agrees with itself, and
     * isolated samples go astray later too. Both are impulses: wrong for a moment,
     * then right again, which is exactly what a median filter is for and what no
     * amount of averaging will remove.
     *
     * A median filter is *identity on a monotonic run* — the median of a ramp is
     * its middle value — so a climb, and a ski descent at 5 m/s, pass through
     * untouched. What it removes is any excursion narrower than half the window:
     * at the ~1 Hz every source here samples at, about four seconds, which is
     * 20 m of ground at cycling speed. Terrain does not have 20 m-wide, 10 m-tall
     * spikes in it; altimeters do.
     *
     * 9 rather than 5 because the observed settling runs to eight samples on this
     * watch, and the window at the very start is slid inward rather than
     * truncated, so those eight are outvoted by what follows them.
     *
     * 9 rather than *more* for a less obvious reason, and it is worth writing down
     * because the obvious measurement argues for more. Ten recordings of one
     * commute are ten recordings of identical ground, so the spread of their
     * ascent totals is pure noise: widening the window from 1 to 9 cuts that
     * spread from 20 m to 9 m, and widening it further keeps cutting. But the
     * *mean* never settles — it falls from 61 m to 32 m to 17 m to 2 m as the
     * window grows to 101, with no knee anywhere. A filter wide enough to flatten
     * the disagreement is wide enough to flatten the hill, and the metric cannot
     * tell you where to stop because a constant series scores perfectly on it.
     *
     * So the window is chosen by what it is for rather than by what it optimises:
     * the smallest that removes the impulses actually observed. This is a spike
     * filter. Wanting less noise in a single recording's total is a real wish and
     * a different mechanism — and the route profile already grants it, by taking
     * the median across members, which is why a route says +0 m on ground where
     * individual recordings of it still claim 32.
     */
    const val ELEVATION_MEDIAN_WINDOW: Int = 9

    /**
     * Plausible altitudes [m]. Outside this the value is a sentinel or a broken
     * fix, not a place anyone has been — see the same bound in the recorder.
     */
    const val MIN_ALTITUDE_M: Double = -1_000.0
    const val MAX_ALTITUDE_M: Double = 10_000.0

    // ---------------------------------------------------------------- intensity

    /**
     * The heart rate the zones are measured against [bpm], when the user has not
     * said otherwise.
     *
     * Their own peak, not a formula. `220 − age` is the best known number in
     * amateur sport and one of the worst: its standard deviation across people
     * is 10-12 bpm, so for one person in three it is out by more than a whole
     * zone. Anybody who has ever finished a hard climb knows their own figure
     * better than arithmetic on their birthday does, so this is a default to be
     * replaced in settings rather than a model to be trusted.
     *
     * 180 is roughly the peak of a fit adult in their thirties and is chosen to
     * be *plausible*, not accurate. Being wrong here recolours a chart; it
     * changes no stored data and no decision the app makes.
     */
    const val DEFAULT_PEAK_HR_BPM: Int = 180

    /** What the settings field will accept [bpm]. Wide enough for a well-trained
     *  teenager and a well-rested seventy-year-old, narrow enough that a typo of
     *  one digit too many is refused rather than stored. */
    const val MIN_PEAK_HR_BPM: Int = 100
    const val MAX_PEAK_HR_BPM: Int = 240

    /**
     * Width, in samples, of the rolling median applied to heart rate before it
     * is turned into a band.
     *
     * Not for spikes, which is what the altitude filter is for — for boundaries.
     * An effort a few beats under the line between Mittel and Intensiv reaches
     * over it every few seconds, and the drawing would alternate between two
     * colours every few pixels: noise rendered at the resolution of a decision.
     * Fifteen seconds at the 1 Hz everything here samples at, and a median rather
     * than a mean for the reason [de.hecate.core.profile.Series] gives — a real
     * effort ramping up is a monotonic run, and a median leaves those exactly
     * alone.
     *
     * It settles a reading that is *mostly* one side of a line, which is what a
     * heart rate does. A reading balanced exactly on one has no band and no
     * filter can invent it one; that case alternates, and honestly so.
     */
    const val HR_MEDIAN_WINDOW: Int = 15

    /**
     * How long a heart rate stays true after the last reading [ms].
     *
     * The watch stamps a rate on a sample only when the sensor delivered one in
     * the same batch as the position, which on two real rides was 28 % and 36 %
     * of the samples — with holes of two to four seconds between readings and a
     * handful of genuine minute-long ones where the strap lost contact. Read
     * literally, that is a chart of forty disconnected bars and a track drawn in
     * the "no reading" colour for two thirds of its length, about a ride whose
     * effort was in fact measured all the way along.
     *
     * Thirty seconds is chosen from both sides: it covers every ordinary hole
     * between readings with room to spare, and a heart rate half a minute old is
     * still the right band for the effort — the bands are ten percent of a peak
     * wide, and nobody crosses one and comes back inside thirty seconds without
     * the next reading saying so. Longer holes are left as holes, and the chart
     * draws the gap.
     */
    const val HR_HOLD_MS: Long = 30_000L

    /**
     * How near in time a heart-rate reading has to be to a position for the two
     * to be about the same moment [ms].
     *
     * The sensors run on their own schedules — a position a second, a rate
     * whenever the optics settle — so joining them is a question of tolerance
     * rather than of equality. Five seconds is comfortably wider than either
     * cadence and far narrower than the thirty seconds a reading is allowed to
     * *stand* for on a chart ([HR_HOLD_MS]): the first is "these two describe
     * the same moment", the second is "nothing better has arrived since". Both
     * exist because conflating them is how a reading from a climb ends up
     * colouring a descent.
     */
    const val HR_MERGE_TOLERANCE_MS: Long = 5_000L

    /**
     * How far apart two barometer readings may be and still describe one slope
     * [ms].
     *
     * Altitude is recorded as its own series and put onto the sample times by
     * interpolation ([de.hecate.core.model.Altitudes]), because the sensor reports
     * far more slowly than positions arrive — and, it turns out, not at a rate at
     * all. Measured on a Pixel Watch 4: four readings inside one ninety-second
     * stretch, 57 s, 27 s and 2 s apart; then seven readings across a nineteen
     * minute session, about one every two and a half minutes. That pattern says
     * the data type is reported on *change* rather than on a clock, which is also
     * why the quiet session was the flat one.
     *
     * So this bound is not "how long before interpolating becomes a guess". If
     * the sensor speaks when the height changes, then a long silence is itself
     * the statement that it did not change, and a straight line across that
     * silence is the most accurate thing available — refusing to draw it throws
     * away a profile the watch had in fact measured. Ten minutes is chosen well
     * above the longest quiet interval yet observed, and the bound is kept for
     * the case it is actually good for: a subscription that died, a recording
     * resumed hours later, a gap where the sensor was absent rather than content.
     *
     * Ten minutes is a reasoned figure from two indoor sessions and not a
     * verified one. What would settle it is one outdoor ride with real climbing,
     * where the reading interval and what it does on a hill can both be seen.
     */
    const val ALTITUDE_INTERPOLATION_GAP_MS: Long = 600_000L

    /**
     * Width, in *readings*, of the median that de-spikes the barometer series
     * before anything is interpolated from it.
     *
     * Not [ELEVATION_MEDIAN_WINDOW], and the difference is the whole point. That
     * window is nine *samples* — nine seconds of the 1 Hz altitude-per-position a
     * takeout or a TCX carries. Nine barometer readings at one every half-minute
     * would be four and a half minutes, and a median that wide does not remove
     * spikes, it removes hills.
     *
     * Three, which is the narrowest window that removes anything, and here that
     * is the right end of the trade. A centred median costs its first and last
     * half-window, where a sample follows its neighbourhood instead of itself —
     * at 1 Hz that is four seconds and the existing comment can afford to call it
     * 1–2 m, but at one reading per half-minute each extra pair of readings costs
     * a *minute* at each end of the ride. Five would buy the ability to outvote
     * two consecutive bad readings and would flatten the first and last minute of
     * every profile to pay for it. Isolated spikes are what altimeters actually
     * produce, and three removes those.
     *
     * The window still slides inward at the ends rather than truncating, which is
     * what makes it remove the altimeter settling on the first reading or two —
     * the same argument as [ELEVATION_MEDIAN_WINDOW], and the reason the cost
     * above is worth paying at all.
     */
    const val ALTITUDE_READING_MEDIAN_WINDOW: Int = 3

    /**
     * Window [samples] of the rolling median over a recording's speed.
     *
     * Speed is the one quantity on these screens that is a *derivative*, and the
     * noise therefore arrives multiplied: two consecutive fixes 15 m apart in
     * the wrong direction are 54 km/h of sprint that never happened. Fifteen
     * seconds at 1 Hz removes every excursion shorter than seven of them, which
     * covers the whole family — a fix that jumped, a tunnel, the second after a
     * dropout — while leaving a real acceleration untouched, because a median is
     * the identity on a monotonic run.
     *
     * The same width as [HR_MEDIAN_WINDOW], and that is not laziness: both are
     * "about as long as a person can meaningfully vary either", and two charts
     * side by side under two different smoothings would disagree about where an
     * effort began.
     */
    const val SPEED_MEDIAN_WINDOW: Int = 15

    /**
     * How many bins a route's speed is measured across.
     *
     * A route's profile holds, per bin, the *median* cumulative time its members
     * took to get there — and each of those medians is taken independently, so
     * the sequence is only monotone on average. Two neighbouring bins can end up
     * a hundredth of a second apart, which over a ten metre bin is four thousand
     * kilometres an hour, and one such bin is enough to make the whole chart a
     * flat line under one spike. That is not a spike to be filtered out
     * afterwards: it is a difference taken over too short a baseline.
     *
     * So the speed at a bin is the distance across [ROUTE_SPEED_WINDOW_BINS] of
     * them over the time across the same, centred, which at [RESAMPLE_M] is
     * 150 m — tens of seconds of riding, long enough that the medians' own
     * scatter is small against it and short enough to keep a hill.
     */
    const val ROUTE_SPEED_WINDOW_BINS: Int = 15

    /**
     * Where one intensity zone ends and the next begins, as a fraction of the
     * peak heart rate.
     *
     * Four zones, not five or seven: this colours a line on a chart, and a
     * reader distinguishes four colours at a glance and seven never. The cuts
     * are the conventional ones — 70 / 80 / 90 % of maximum — which is where
     * every zone model puts its boundaries whatever it calls the bands, because
     * the physiology behind them (aerobic, threshold, anaerobic) does not move
     * with the naming.
     *
     * Fractions of the peak rather than of the reserve (Karvonen): the reserve
     * needs a resting heart rate, this app has never measured one, and asking
     * for a second number to colour a chart is a poor trade.
     */
    const val HR_ZONE_MODERATE_FRACTION: Double = 0.70
    const val HR_ZONE_HARD_FRACTION: Double = 0.80
    const val HR_ZONE_PEAK_FRACTION: Double = 0.90

    // ---------------------------------------------------------- live display

    /**
     * How long the matcher's idea of where the user is may go unrefreshed before
     * the display stops trusting anything derived from it.
     *
     * Unusable samples (accuracy beyond [MAX_USABLE_ACCURACY_M], or none arriving
     * at all) leave the projected position where it was while elapsed time keeps
     * running, so ahead/behind drifts steadily worse for a reason that has
     * nothing to do with the user. Past this the delta is frozen at its last
     * honest value and the display dims it, which is a statement about the
     * measurement rather than about the runner.
     */
    const val STALE_POSITION_MS: Long = 20_000L

    /**
     * Full-scale deflection of the ahead/behind bar [s], one fixed number per
     * sport.
     *
     * It used to be the route's own interquartile spread, which made the same
     * minute fill a different share of the bar on every route, and — because a
     * tie between routes has no single spread — made the scale jump whenever the
     * matcher committed or let go. A bar is read by its length at a glance; that
     * only works if the length always means the same thing. Beyond full scale
     * the bar stays full and says so (the watch draws an arrow at its tip), and
     * the number beside it is exact either way.
     *
     * Chosen as "clearly a different day" for each sport: a few minutes on a
     * commute or an evening walk, less on a run, where a minute is a lot, and
     * ten on the long and weather-bound days hiking and touring are.
     */
    fun deltaFullScaleS(type: ExerciseType): Double = when (type) {
        ExerciseType.RUNNING -> 120.0
        ExerciseType.WALKING -> 180.0
        ExerciseType.BIKING -> 180.0
        ExerciseType.HIKING -> 600.0
        ExerciseType.SKIING -> 600.0
    }

    /**
     * How long since the last usable position fix before the watch says so.
     *
     * [FIX_STALE_MS] is "GPS is struggling", which is worth knowing but changes
     * nothing you would do; [FIX_LOST_MS] is "this recording is currently a
     * stopwatch with a heart rate monitor", which is worth interrupting for.
     * Both are measured from the last sample that actually passed
     * [Sample.usable][de.hecate.core.model.Sample.usable], not from a system
     * availability callback, because the callback fires on transitions and a fix
     * that quietly goes to 200 m of error never produces one.
     */
    const val FIX_STALE_MS: Long = 20_000L
    const val FIX_LOST_MS: Long = 60_000L

    /** Nothing written to the session file for this long while the exercise is
     *  active. The recorder is then not recording, whatever the screen says, and
     *  that is the one thing the watch must never be quiet about. */
    const val NO_WRITE_MS: Long = 90_000L

    // ---------------------------------------------------------------- auto-stop

    /**
     * How long the user may stay inside [AUTO_STOP_IDLE_RADIUS_M] before the
     * recording ends itself [ms].
     *
     * Three quarters of an hour, because the two mistakes are not symmetric.
     * Ending a workout that was only resting cuts a real recording in half and
     * cannot be undone from the wrist; waiting to notice an activity nobody is
     * doing any more costs battery and a stretch of track that is discarded
     * anyway (see [AutoStop][de.hecate.core.recorder.AutoStop]).
     *
     * Fifteen minutes was the first guess, and the real history refuted it: run
     * over 73 recorded workouts (`autostop --archive`), it ended six walks and
     * hikes in the middle — a half-hour summit rest with two hours of descent
     * after it, a 25-minute stop four hours into a seven-hour walk. Thirty still
     * ended the summit one. At forty-five nothing fires on a break, and the four
     * sessions it does end are the ones that deserve it: a watch left running
     * for an hour after arriving, and three drives home that the speed rule
     * catches first anyway.
     */
    const val AUTO_STOP_IDLE_MS: Long = 45 * 60 * 1000L

    /**
     * The circle the user has to leave for the recording to count as moving [m].
     *
     * Wider than the 3-10 m a consumer receiver manages in the open, so that
     * standing still never looks like walking, and far narrower than anything a
     * moving activity stays inside for long: at the slowest of the five sports
     * this is twenty-five seconds of walking. Fixes too poor to use
     * ([MAX_USABLE_ACCURACY_M]) neither move the anchor nor reset the clock —
     * they are not evidence of anything, and a jumping indoor fix would
     * otherwise make "standing in the kitchen" look like a walk.
     */
    const val AUTO_STOP_IDLE_RADIUS_M: Double = 30.0

    /**
     * How long without a single usable fix before the recording ends itself [ms].
     *
     * Shorter than [AUTO_STOP_IDLE_MS], because a break is not what it looks
     * like: a resting user has perfectly good fixes, and only the stationary
     * rule is about resting. This is the rule that
     * catches walking indoors with the watch still running, which is what
     * [AUTO_STOP_IDLE_MS] cannot see: indoors the receiver does not stop, it
     * degrades, and its positions wander far enough to keep resetting the
     * stationary anchor while being much too poor to use. It never fires before
     * the session has had a usable fix of its own — a recording made knowingly
     * without GPS is a stopwatch with a heart rate monitor, which the watch
     * already says, and not something to throw away.
     */
    const val AUTO_STOP_NO_FIX_MS: Long = 15 * 60 * 1000L

    /**
     * How far the activity has to have travelled before either timer can end it
     * [m].
     *
     * Nothing may auto-stop an activity that has not begun: start the watch, wait
     * for a friend to lace up, and an armed idle timer would end the recording
     * before the first step — and the recording that mattered would be the one
     * nobody noticed was not running. Fifty metres is past the door and past the
     * first fix's own error, and no workout ends before it.
     */
    const val AUTO_STOP_ARM_M: Double = 50.0

    /**
     * How long a speed above the sport's ceiling has to last before it is
     * believed [ms].
     *
     * One second of GNSS noise at 15 m is 54 km/h of imaginary sprint, and a
     * single bad pair of fixes can be far worse than that, so no single segment
     * decides anything: the speed has to stay impossible for a minute and a half
     * of continuous evidence. A car pulling out of the drive supplies that in the
     * first block it drives; noise never does, because the segment after it is
     * ordinary again and the run starts over.
     */
    const val AUTO_STOP_OVERSPEED_MS: Long = 90_000L

    /**
     * The speed above which the sport is no longer being done [km/h].
     *
     * Deliberately generous — the question is not "is this a fast run" but "is
     * this a car". Each is well above what the sport's world records need: a
     * marathon is run at 21 km/h and a sprint peaks near 45, which is why
     * [AUTO_STOP_MAX_KMH_RUNNING] sits above the first and below the second only
     * as a matter of arithmetic and not of ambition — a sprint lasting ninety
     * seconds does not exist. Cycling gets 80 because alpine descents reach 70.
     *
     * Skifahren has no ceiling at all and is exempt from the rule, which is a
     * judgement rather than an oversight: a lift is part of the activity and is
     * not distinguishable from a road vehicle by speed, and a fast descent is
     * faster than most driving. The other two rules still apply to it.
     */
    const val AUTO_STOP_MAX_KMH_RUNNING: Double = 30.0
    const val AUTO_STOP_MAX_KMH_WALKING: Double = 20.0
    const val AUTO_STOP_MAX_KMH_HIKING: Double = 20.0
    const val AUTO_STOP_MAX_KMH_BIKING: Double = 80.0

    /**
     * How often the recorder asks the auto-stop whether time alone has decided
     * anything [ms].
     *
     * Both timers can expire with no sample to carry them: an auto-pause writes
     * nothing at all, and a receiver that has given up writes nothing usable. So
     * the question is also asked on a timer, half a minute apart — often enough
     * that the stop lands within a rounding error of the threshold, rarely enough
     * to be invisible beside a GNSS receiver in the battery budget (NFR-3).
     */
    const val AUTO_STOP_TICK_MS: Long = 30_000L

    // ------------------------------------------------------------------ basemap

    /**
     * How far from a recorded position the dim OpenStreetMap backdrop is fetched
     * at all [m] (Appendix A.29).
     *
     * A margin round the ground you have actually been on, not a box round your
     * whole life: the twelve clusters the recordings fall into are 4,677 km²
     * with this margin and about 175,000 km² without it. Three kilometres is
     * roughly "the next village either way", which is as much context as a
     * drawing of a route ever needs.
     */
    const val MAP_REGION_MARGIN_M: Double = 3_000.0

    /** Ground within this distance of a recorded position is worth the full
     *  street detail [m]; everything else in a region gets the coarse level.
     *  Two kilometres is 712 km² over the whole history — about ninety tiles. */
    const val MAP_DETAIL_RADIUS_M: Double = 2_000.0

    /** Edge [m] of the grid the coverage clusterer works on. Five kilometres is
     *  well under the distance between two places a person would call different
     *  (Erlangen and Bamberg are 50 km apart) and well over the width of any
     *  single ride. */
    const val MAP_CLUSTER_GRID_M: Double = 5_000.0

    /** A coverage cluster holding fewer positions than this is a stray fix, not
     *  a place that was ridden through, and is not worth fetching a region for. */
    const val MAP_MIN_CLUSTER_SAMPLES: Int = 25

    /** How far outside a region place *nodes* are still collected [m].
     *  Bamberg's node sitting a kilometre outside the box must not cost the
     *  label for a city that fills the screen. */
    const val MAP_PLACE_MARGIN_M: Double = 15_000.0

    /**
     * Douglas–Peucker tolerance per class [m].
     *
     * Set by the zoomed-in case, which is the only one that can show the error:
     * at a 1 km view (about 2.5 m/px) these are two to five pixels of deviation,
     * under a bright track on a dim ground. They are an order of magnitude above
     * the storage quantisation and well under GNSS noise, so neither of those is
     * ever the limiting step.
     */
    const val MAP_SIMPLIFY_MOTORWAY_M: Double = 6.0
    const val MAP_SIMPLIFY_PRIMARY_M: Double = 8.0
    const val MAP_SIMPLIFY_RAIL_M: Double = 8.0
    const val MAP_SIMPLIFY_MINOR_M: Double = 10.0
    const val MAP_SIMPLIFY_WATER_M: Double = 10.0
    const val MAP_SIMPLIFY_SERVICE_M: Double = 12.0
    const val MAP_SIMPLIFY_PATH_M: Double = 12.0

    /** Under this a path, footway or field track is a stub that costs bytes and
     *  says nothing [m]. */
    const val MAP_MIN_PATH_M: Double = 150.0

    /** Under this a stream is a ditch rather than a river [m]. */
    const val MAP_MIN_STREAM_M: Double = 500.0

    /** Under this a body of water would draw as one pixel [m²]. */
    const val MAP_MIN_WATER_AREA_M2: Double = 2_500.0

    /**
     * Level of detail, in **dp** per metre of ground.
     *
     * Dp and not px, for the reason [HEATMAP_CELL_M] exists one layer down: a
     * 3x screen must not show a denser map than a 1x one at the same apparent
     * size. On a ~360 dp canvas these read straight off as "how wide is this
     * view": 0.008 is 45 km across, 0.03 is 12 km, 0.15 is 2.4 km. For
     * reference, web Mercator z14 at 49.5 °N is 0.107 px/m, so the bands land
     * roughly where a street map's own zoom levels do.
     *
     * Every band is a floor except one. [MAP_LOD_STREETS] is also the *ceiling*
     * of the faint built-up patch: at whole-region zoom "where are the towns" is
     * the useful question and 4,700 km² of residential streets is a black
     * scribble, but the moment the streets themselves are drawn the patch only
     * muddies them. It is the one entry here with its own test, because it is
     * the one somebody will later "fix".
     */
    const val MAP_LOD_TOWN: Double = 0.008
    const val MAP_LOD_STREETS: Double = 0.030
    const val MAP_LOD_SERVICE: Double = 0.080
    const val MAP_LOD_HAMLET: Double = 0.100
    const val MAP_LOD_PATH: Double = 0.150

    /** Decoded tiles held in memory. A full-screen street view is a few dozen;
     *  this is enough to pan without re-inflating and small enough that the
     *  cache cannot become the reason the app is remembered for its memory. */
    const val MAP_TILE_CACHE: Int = 96

    /** Clear space kept around a place label [dp] before it counts as
     *  colliding with another. */
    const val MAP_LABEL_MARGIN_DP: Double = 4.0

    /** Roughly one label per this much canvas area [dp²], which comes out at
     *  about six on a route drawing and twenty-five full screen. */
    const val MAP_LABEL_AREA_PER_LABEL_DP2: Double = 13_000.0

    /** Bounds on that count, so a thumbnail gets at least a couple of names and
     *  a tablet does not get a phone book. */
    const val MAP_LABEL_MIN: Int = 3
    const val MAP_LABEL_MAX: Int = 30

    /** Minimum total length of minor road inside one tile [m] before the tile
     *  counts as built up. A village lane is a few hundred metres; a town tile
     *  is tens of kilometres. */
    const val MAP_URBAN_MIN_LENGTH_M: Double = 12_000.0

    /** Quiet time between two Overpass requests [ms]. Manners, and structural
     *  rather than aspirational: the client sleeps, so nothing can forget. */
    const val MAP_FETCH_PAUSE_MS: Long = 2_000L

    /** Backoff after a 429 or a 504 [ms], in order. Past the end of this the
     *  chunk is given up and the next endpoint is tried. */
    val MAP_FETCH_BACKOFF_MS: LongArray = longArrayOf(5_000L, 15_000L, 45_000L, 120_000L)

    /** The largest box a single Overpass request may ask for [deg]. Beyond
     *  about a quarter degree — 500 km² here — the public instances start
     *  timing out rather than answering slowly. */
    const val MAP_MAX_CHUNK_DEG: Double = 0.25

    // ------------------------------------------------------------ watch map

    /**
     * How much ground the watch's map page shows: metres from the centre of the
     * face to its edge, fixed per sport.
     *
     * Fixed rather than fitted, because the page is read at a glance and a scale
     * that changed under the user would make the same deviation look different
     * from one minute to the next. Chosen so the next minute or so of travel is
     * on the face — enough to see a turn coming and to see a few metres of
     * drift from the route line, which at this scale is a visible gap.
     */
    fun watchMapRadiusM(type: ExerciseType): Double = when (type) {
        ExerciseType.WALKING -> 150.0
        ExerciseType.HIKING -> 200.0
        ExerciseType.RUNNING -> 250.0
        ExerciseType.BIKING -> 600.0
        ExerciseType.SKIING -> 800.0
    }

    /** Path [m] the direction of travel is read over (`HeadingUp.heading`):
     *  long enough to see through GNSS jitter at the sport's pace, short enough
     *  that the map has turned by the time the user has rounded a corner. */
    fun headingLookbackM(type: ExerciseType): Double = when (type) {
        ExerciseType.WALKING, ExerciseType.HIKING -> 25.0
        ExerciseType.RUNNING -> 35.0
        ExerciseType.BIKING, ExerciseType.SKIING -> 70.0
    }

    /** Share of the way the map turns towards a new direction per update, about
     *  once a second: a corner takes a few seconds to come round, a wobble does
     *  not move it. */
    const val HEADING_SMOOTHING: Double = 0.35

    /** The watch keeps at most this many recent positions for the map page,
     *  each at least [WATCH_TRAIL_MIN_SPACING_M] from the last. */
    const val WATCH_TRAIL_MAX_POINTS: Int = 600
    const val WATCH_TRAIL_MIN_SPACING_M: Double = 3.0
}
