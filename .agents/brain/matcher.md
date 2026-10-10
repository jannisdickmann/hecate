# Matcher (§5.6)

The online route matcher, `de.hecate.core.matcher.RouteMatcher`: what it
believes about which learned route the user is on, and what the watch reads
off that belief. Formerly §5.6 of `SPECIFICATION.md`. The original text is kept
below because the code still cites its step numbers; every place the
implementation departs from it says so and links the ADR. The constants named
here all live in `Tuning` (NFR-5), each with its unit and rationale.

The matcher is pure and clock-free: it reads no clock and counts its hold
windows in samples, so a replay at any speed reaches the decisions the watch
reached (US-10, [A.28](decisions/2026-09-13-replaying-an-activity-into-the-recorder.md)).

---

## State

A weight per active route plus a null hypothesis. Nothing is ever "chosen"
internally from the weights alone; what is named, and what the numbers are read
off, is decided by the focus rule below
([A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md)).

## Per sample

1. Query `GridIndex` for routes with geometry within `MATCH_RADIUS_M` of the
   sample. Routes not seen for `CANDIDATE_DROP_M` of travel are dropped.
2. For each candidate, project the sample onto the polyline within a window
   around the predicted position (predicted from previous position + travelled
   distance). **As built** the window is asymmetric,
   `[position − BACKTRACK_SLACK_M, position + travel + SEARCH_WINDOW_M]`, not
   the symmetric ±`SEARCH_WINDOW_M` the original text gave: a symmetric window
   does not make position advance monotonically, and a route's own reverse then
   tracks a forward run perfectly
   ([A.1](decisions/2026-09-11-the-matcher-needs-a-transition-model.md)).
3. Emission log-likelihood `= -(d² / (2σ²))`, with
   `σ = max(GPS_SIGMA_M, reported accuracy)`. **Plus** a transition term,
   `−(advance − travel)² / (2 σ_adv²)`, where `advance` is how far the
   projection moved along the route; each candidate also carries a smoothed
   `advance / travel` ratio, and one below `MIN_TRACKING_RATIO` is not treated
   as being on the route at all (A.1).
4. Null hypothesis gets a fixed log-likelihood equivalent to
   `d = NULL_DISTANCE_M`, with prior floor `NULL_PRIOR_FLOOR`.
   `NULL_DISTANCE_M` (120 m) must exceed `DEVIATION_M` (60 m), and
   `MAX_ABS_LOG_LIK` is 60 so the null and a distant route do not clamp to the
   same value ([A.3](decisions/2026-09-11-null-distance-exceeds-deviation-threshold.md)).
5. Accumulate in log space with decay `WEIGHT_DECAY` applied to prior weights,
   so an early mistake is forgettable. Normalise.

Samples whose accuracy is beyond `MAX_USABLE_ACCURACY_M` are rejected, and the
projected position freezes; ahead/behind is frozen with it rather than charged
to the user (US-5).

Around this, the geometric machinery that A.31 lists as unchanged: windowed
projection, localisation that prefers the start and the pass that continues,
the other-leg rescue for lollipops and loops, and forward-only re-admission.

## Priors at start

Historical frequency by (exercise type, weekday bucket, time-of-day bucket),
floored so nothing starts at zero, with `NULL_PRIOR_FLOOR` reserved for
"unknown". The bucket comes from `RouteMatcher.contextKeyOf`, which needs a
correct time-zone offset on every session — an imported history stamped at
offset 0 once taught the priors the wrong hours
([A.15](decisions/2026-09-12-backfill-from-a-takeout.md)).

## Commitment

*Original:* commit when top weight ≥ `COMMIT_WEIGHT` for `COMMIT_HOLD_SAMPLES`
consecutive samples **and** ≥ `COMMIT_MIN_M` travelled. Demote when top weight
< `DEMOTE_WEIGHT`. Report `Unbekannt` when the null hypothesis exceeds
`NULL_DOMINANT_WEIGHT`.

*Superseded:* weights no longer decide what is named. The history of the rule:

- [A.2](decisions/2026-09-11-commitment-is-gated-on-geometry.md) — on shared
  ground the weight ratio measures a minute's GNSS bias and nothing else, so
  commitment also requires every rival to be `COMMIT_SEPARATION_M` further
  away than the leader.
- [A.4b](decisions/2026-09-11-manual-route-priority.md) — among contenders on
  the same ground, a unique highest user priority decides.
- [A.27](decisions/2026-09-19-the-watch-says-what-it-knows.md) — a provisional
  name (`≈ …`) for a sole leader, and display words that do not read as
  failure.
- [A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md) —
  the current rule, `RouteMatcher.updateFocus`:
  1. **Joined** — a route is joined the first time it is found under the user
     and followed forwards; within `JOIN_AT_START_M` of the session start it
     was *set off on* and is compared from the start of the workout, otherwise
     from where it was joined.
  2. **Contenders** — followed routes within `COMMIT_SEPARATION_M` of the
     nearest followed route.
  3. **Named** — committed once a contender has been followed `COMMIT_MIN_M`
     along itself; a route set off on may be named as a guess after
     `PROVISIONAL_MIN_M`; a route joined on the way is never guessed at.
  4. **Which one** — seniority, then priority, then the route already named,
     then weight.
  5. **Held** — through `FOCUS_RELEASE_M` of not fitting.
  6. **Numbers** — from the named route only, or absent; carried across a name
     change (`handOver`).

`MatchState.committedByPriority` and the `COMMIT_BY_PRIORITY` log event record
when a name is a setting rather than a finding; the watch draws it blue rather
than green.

## Ahead/behind

*Original:* weight-averaged median cumulative time at current position across
all candidates above `LIVE_CANDIDATE_MIN_WEIGHT`; delta vs. elapsed active
duration.

*Superseded:* read off the one named route, from where it was joined, and
carried across when the name changes
([A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md)).
The sign convention is open point §2.4. The elapsed active time it is compared
against is the repaired clock of
[A.24](decisions/2026-09-15-the-active-clock-is-rebuilt-from-offsets.md) and
A.31's `ActiveClock.unpause`.

## ETA

`now + (median_time_at_end − median_time_at_pos) × k`, where `k` is the
observed-vs-median ratio clamped to `[ETA_K_MIN, ETA_K_MAX]` (`ETA_RATIO_CLAMP`)
and smoothed over `ETA_SMOOTH_SAMPLES`. Since A.31, `k` is today's time over the
usual time *since the comparison began*. The arrival is published as an instant,
not as seconds-from-now, so a stale figure cannot walk forwards
([A.27](decisions/2026-09-19-the-watch-says-what-it-knows.md)). `k` stays
called a "pace ratio" in `Tuning`: it is dimensionless and internal, and the UI
never shows pace ([A.9](decisions/2026-09-11-speed-in-kmh-never-pace.md)).

## Deviation

*Original:* min distance across live candidates > `DEVIATION_M` for
`DEVIATION_HOLD_SAMPLES` → alert once; re-arm below `DEVIATION_CLEAR_M`. Gated
on committed state.

*As built:* commitment **arms** the detector, and demotion does not disarm it
([A.4](decisions/2026-09-11-deviation-detector-is-armed-by-commitment.md));
the distance is the minimum across the armed route and every live, tracking
candidate — the original "min across live candidates", restored after A.4 had
narrowed it ([A.4c](decisions/2026-09-11-deviation-is-measured-against-the-route-network.md)).

## Batching

Samples may arrive in bursts when the screen is off. Process in timestamp
order; the result must be identical to streaming (verified by US-10, and by the
harness's `selfcheck`). On the watch, every delivery runs on one serial lane so
that two bursts can never interleave
([A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md)).

## Known limits

- **Skiing.** `BACKTRACK_SLACK_M` is 10 m; a lift ride back up a piste is a long
  run backwards along the descent just matched, so repeated laps of one piste
  are unlikely to match the way a loop does
  ([A.18](decisions/2026-09-13-hiking-and-skiing-and-exercise-types-as-enums.md)).
- **Per type.** Different exercise types never share a route.
- **A parallel street 80 m away** will match a learned route — open point §2.5.
- **Tuning** has only met a little real GPS — open point §2.1.
