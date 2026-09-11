package de.hecate.core

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

    /** Smoothing applied to a candidate's advance-per-travelled-metre ratio.
     *  ~10 samples of memory: long enough to see through GNSS jitter, short
     *  enough to notice within a few seconds that a candidate stopped tracking. */
    const val TRACK_RATIO_SMOOTHING: Double = 0.90

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

    /** Top route must hold at least this normalised weight to be committed to. */
    const val COMMIT_WEIGHT: Double = 0.80

    /** ...for this many consecutive samples. Suppresses commitment on a lucky burst. */
    const val COMMIT_HOLD_SAMPLES: Int = 20

    /** ...and not before this much distance [m] has been travelled. Guards against
     *  committing inside a shared start segment that every route leaves from. */
    const val COMMIT_MIN_M: Double = 400.0

    /** Commitment additionally requires every other live candidate to be at least
     *  this much further [m] from the current position than the leader is.
     *
     *  This, not the weights, is what implements "commitment must not occur while
     *  two or more routes remain plausible" (US-4). Where two routes coincide they
     *  explain the samples equally well, and the difference in their weights is
     *  then a measurement of the GNSS bias of the last minute, not of which route
     *  the user is on. Deciding on it would be committing to noise. */
    const val COMMIT_SEPARATION_M: Double = 50.0

    /** Below this weight a committed route is demoted back to "searching". Well
     *  under COMMIT_WEIGHT: commitment should be sticky, not twitchy. */
    const val DEMOTE_WEIGHT: Double = 0.55

    /** Once the null hypothesis holds this much weight the display says "Unbekannt"
     *  and every route-relative metric is suppressed. */
    const val NULL_DOMINANT_WEIGHT: Double = 0.70

    // ------------------------------------------------------------ live metrics

    /** Candidates below this weight contribute nothing to the pooled ahead/behind
     *  estimate (US-5). Keeps a long tail of near-dead routes out of the average. */
    const val LIVE_CANDIDATE_MIN_WEIGHT: Double = 0.05

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
}
