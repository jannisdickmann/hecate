# Clustering (§5.7)

How recordings become routes: `de.hecate.core.cluster.RouteClusterer`, run by
the phone's `ClusterWorker` after every ingest and at the end of every restore.
Formerly §5.7 of `SPECIFICATION.md`. There is exactly one implementation of
what counts as a route — the takeout importer deliberately writes sessions and
never routes ([A.15](decisions/2026-09-12-backfill-from-a-takeout.md)).

---

## The specification

- Resample every session track to 10 m spacing; drop paused spans.
- `coverage(A,B) = min(fracWithin(A→B, CLUSTER_TOL_M), fracWithin(B→A, CLUSTER_TOL_M))`
- `distance = 1 − coverage`; average-link agglomerative; cut at
  `CLUSTER_CUT`. Direction reversals are never merged.
- Cluster becomes an active route at ≥ `MIN_MEMBERS` members (3; US-3).
- **Geometry = the medoid member's track**, not an average. Averaging polylines
  is fiddly and error-prone; a medoid is exact, cheap, and good enough.
- Profile = per-10 m-bin median cumulative time and median HR across members,
  with p25/p75 for later use (they now set the scale of the watch's ahead/behind
  bar, [A.27](decisions/2026-09-19-the-watch-says-what-it-knows.md)).
- Recompute from scratch on each ingest. O(n²) on a few hundred sessions is
  milliseconds; incremental clustering is deferred
  ([§8](scope.md#everything-else-85); open point §2.6).

## As built

- **Route identity survives re-clustering.** Fresh clusters are matched onto
  existing `route` rows by Jaccard overlap of their member sets
  (`ROUTE_REIDENTIFY_OVERLAP`), keeping id, creation date, a custom name and the
  priority; routes with no cluster are deactivated, not deleted
  ([A.5](decisions/2026-09-11-route-identity-survives-re-clustering.md)).
- **Different exercise types are infinitely far apart**, so the same forest
  track walked once and hiked once is two routes
  ([A.18](decisions/2026-09-13-hiking-and-skiing-and-exercise-types-as-enums.md)).
- **"Milliseconds" did not survive a real history** until `sameDirection` was
  indexed: 65 sessions took thirteen minutes, then 16 seconds
  ([A.19](decisions/2026-09-13-five-bugs-the-emulators-found.md)). On the phone
  the job is unique and enqueued with `REPLACE`, and the UI shows it running
  ([A.13](decisions/2026-09-12-settings-panel-back-stack-and-standout-marks.md)).
- **The profile also holds altitude** — a per-bin median, deliberately not the
  medoid's: a shape has to belong to one recording to stay a shape, an
  elevation does not ([A.20](decisions/2026-09-13-altitude-profiles.md)).
- **The profile is built on a repaired clock.** `Track.of` reads
  `RecordedSession.timedSamples`, so per-bin times come from the monotonic
  offsets rather than the watch's stale checkpoint
  ([A.24](decisions/2026-09-15-the-active-clock-is-rebuilt-from-offsets.md),
  [A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md)).
- **Duplicates must not reach it.** Two members for one journey inflate the
  member count `MIN_MEMBERS` tests; imported sessions are refused by start-time
  proximity on restore ([A.15](decisions/2026-09-12-backfill-from-a-takeout.md)).
- **Forgotten recordings must not reach it either** — the auto-stop cut is
  applied at ingest, before clustering sees the session
  ([A.30](decisions/2026-09-21-a-recording-ends-itself.md)).
- **Membership decides filing.** After every re-clustering,
  `clearMatchesOfNonMembers` removes the matched route from a recording that is
  not a member of an active route; what the watch committed to stays in the
  match log and `committedAtM`
  ([A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md)).
- **The heatmap is built in the same worker**, last, after the bundle is pushed
  ([A.14](decisions/2026-09-12-heatmap-drawn-around-the-routes-extent.md)).
- **A detour is a new route.** A pair where either track leaves the other in one
  interior stretch longer than `DETOUR_MAX_FRACTION` of its own length never
  merges, whatever the coverage
  ([A.32](decisions/2026-10-11-a-detour-is-a-new-route-and-temporary-routes.md)).
- **Temporary routes.** A promoted recording forms a route on its own until a
  real one forms around it; the worker then clears the promotion
  ([A.32](decisions/2026-10-11-a-detour-is-a-new-route-and-temporary-routes.md)).
