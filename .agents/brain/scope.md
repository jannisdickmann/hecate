# Scope

What v1 deliberately does not do, and why; and what the implementation has not
yet been able to prove. Formerly §8 and Appendix A.8 of `SPECIFICATION.md`.

---

## Deferred — explicitly not in v1 (§8)

Each of these was cut on purpose. A request to add one is a request to revisit
the reasoning below, not a gap.

### Route variants as a hierarchy (§8.1)

Shortcuts become independent routes that happen to share a prefix. The matcher
handles this correctly without any variant model. A parent/child structure with
shared-segment merging is the elegant version and buys better naming and pooled
statistics — but polyline merging under GPS noise is the single fiddliest piece
of the whole design. Revisit only if the route list becomes cluttered.

What this costs in practice — a watch that cannot name a route for most of a
shared prefix — is answered by route priority and the seniority rule rather
than by a hierarchy:
[A.4b](decisions/2026-09-11-manual-route-priority.md),
[A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md).

### Automatic session start (§8.2)

Geofences plus activity recognition could start the session hands-free. Cut
because background foreground-service launch is restricted, false positives are
worse than missed ones, and the first 100–300 m would be lost to GNSS lock —
exactly the segment the matcher needs.

Automatic *stop* is a different question and was built: a forgotten recording
costs the route network a motorway, and the cut can be recorded rather than
applied ([A.30](decisions/2026-09-21-a-recording-ends-itself.md)).

### Route graph and merged prefixes (§8.3)

Independent polylines with all-candidate scoring give identical results with a
fraction of the code. Revisit above ~100 routes.

### Google Health API integration (§8.4)

Automated history pull via `exportExerciseTcx` requires a Cloud project, OAuth
client, and restricted-scope handling. Manual TCX export achieves the same
backfill with zero moving parts. Revisit only if continuous two-way sync
becomes desirable.

The backfill that actually scales turned out to be a Google Takeout read on a
laptop, still with zero moving parts on the phone
([A.15](decisions/2026-09-12-backfill-from-a-takeout.md)).

### Everything else (§8.5)

Map basemaps (osmdroid), chart library, elevation-adjusted pace, weather or
temperature conditioning, tiles and complications, watch-face integration,
route navigation/turn cues, incremental clustering, multi-user, phone-recorded
sessions, localization, export to GPX/Strava, per-segment leaderboards,
notification of personal bests.

Two of these have since been met without what they named:

- **Basemaps** — still no osmdroid and no maps SDK, but a dim OpenStreetMap
  ground fetched from Overpass and drawn by the app's own `Canvas` code
  ([A.29](decisions/2026-09-20-osm-basemap-under-every-track.md)).
- **Charts** — still no chart library; heatmap, altitude, speed and heart-rate
  charts are drawn by hand
  ([A.14](decisions/2026-09-12-heatmap-drawn-around-the-routes-extent.md),
  [A.20](decisions/2026-09-13-altitude-profiles.md),
  [A.23](decisions/2026-09-15-two-heatmap-rasters-and-one-chart-composable.md)).

A backup archive is not "export to GPX/Strava": that is about *sharing* a
workout, the archive is about not losing one
([A.10](decisions/2026-09-11-backup-is-a-file-not-a-cloud.md)). The standout
marks of [A.13](decisions/2026-09-12-settings-panel-back-stack-and-standout-marks.md)
mark a personal best against a route on screen; they notify nobody.

---

## Scope not reached (A.8)

The code for every milestone exists; the field evidence does not.

- The emulator layer of the test strategy
  ([§6.5](development.md#test-strategy-65)) has been exercised across every
  screen ([A.19](decisions/2026-09-13-five-bugs-the-emulators-found.md)) and
  can now be pointed at the matcher as well
  ([A.28](decisions/2026-09-13-replaying-an-activity-into-the-recorder.md)).
  What it still cannot reach is anything needing two paired nodes — the Data
  Layer and Health Connect — for which see open point §2.9 in `issues/`.
- The on-wrist layer has been exercised once: a Pixel Watch 4 and a Pixel 8
  completed a full round trip, which is what found the three defects open point
  §3.2 lists. One session is not the ten consecutive recordings
  [§6.6](development.md#build-order-66) asks for.
- §6.6's exit criteria are stated in terms of real workouts (ten consecutive
  recordings, sessions appearing in Google Health, behaviour on the fixture set
  "walked in reality"). The code implementing each milestone is complete; the
  criteria themselves are field measurements and remain open.
