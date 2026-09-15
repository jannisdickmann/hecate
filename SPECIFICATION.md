# RouteTracker — App Specification v1.0

Personal route-learning companion for Pixel Watch 4 (Wear OS 6) + Android phone.
Single user, no cloud, no cost, sideloaded.

---

## 1. Overview

### 1.1 Problem

Google Health records workouts well but never learns that today's run is the
same route as last Tuesday's. There is no per-route history, no "am I faster
than usual at this exact point", no remaining-distance or arrival-time estimate
for a route it has seen twenty times.

### 1.2 Solution

A standalone Wear OS recorder plus a phone companion. The watch records the
workout using Health Services (the same sensor-fusion layer Google's own
Exercise app consumes), identifies which of the learned routes the user is on
while the activity runs, and displays live route-relative metrics. The phone
stores history, learns routes by clustering past recordings, and writes
completed sessions to Health Connect so they still appear in Google Health.

### 1.3 Constraints that shape the design

These are platform facts, not preferences:

- **Wear OS permits one exercise session device-wide.** Starting a session in
  this app terminates any Google Health session and vice versa. A plugin to
  Google Health is impossible. Live data is only delivered for sessions this
  app owns.
- **Health Connect is phone-only.** The watch cannot write to it. All writes go
  through the phone companion.
- **Reading other apps' routes from Health Connect is restricted** (manual
  grant only, no background reads). Therefore this app owns its own data and
  never depends on reading Google Health's routes.
- **Exercise data arrives at ~1 Hz while the screen is on, batched while
  ambient.** Live UI freshness matches Google's app exactly; ambient staleness
  does too.

### 1.4 Scope philosophy

v1 does five things and nothing else: record reliably, learn routes, identify
the current route, show four live numbers, show per-route history. Every
feature that can be deferred is deferred (§8). Robustness of recording ranks
above every other quality attribute — a lost workout is unrecoverable, a wrong
ETA is a shrug.

---

## 2. User stories

Stories are ordered by build sequence. Each has acceptance criteria.

### US-1 — Record a workout (must)

*As the user, I start a workout on my watch and it records GPS, heart rate and
distance until I stop it.*

- Start/pause/resume/stop from the watch, no phone required, no phone in range.
- Exercise types: `RUNNING`, `WALKING`, `HIKING`, `BIKING`, `SKIING`
  (outdoor, GPS on). The original three were `RUNNING`, `WALKING` and
  `BIKING`; the other two were added later, under the rule in A.18.
- Survives: screen off for the whole activity, app process death, watch reboot
  mid-activity (recording resumes or the partial session is preserved).
- Auto-pause events from Health Services are honoured and recorded.
- Recording continues if the route matcher throws. Matcher failure is never
  fatal to recording.

### US-2 — Session reaches the phone and Health Connect (must)

*As the user, every completed workout ends up in my history and in Google
Health without me doing anything.*

- Transfer is at-least-once; ingest is idempotent on session UUID.
- A session recorded with the phone switched off transfers when the phone
  returns.
- Phone writes `ExerciseSessionRecord` + `ExerciseRoute` + `HeartRateRecord` +
  `DistanceRecord` to Health Connect.
- Watch-side session data is deleted only after the phone acknowledges ingest.
- Failed Health Connect writes are retried and never block local history.

### US-3 — Routes are learned from history (must)

*As the user, after I have run the same way a few times, the app recognises it
as one of my routes.*

- A cluster of ≥ 3 similar recordings becomes an active route.
- Routes are direction-sensitive: the reverse of a route is a different route.
- Routes are auto-named on creation (`Route 7`, time-of-day hint) and renamable.
- Re-clustering runs after each ingest; results are deterministic for a given
  session set.
- A path that differs materially from all known routes forms its own cluster —
  shortcuts and variants become separate routes, not sub-variants (§8.1).

### US-4 — The current route is identified during the activity (must)

*As the user, some minutes into my workout the watch tells me which route I am
on — and honestly says "unknown" when it does not know.*

- Display states: `Suche…` (searching) → `Weg zur Arbeit` (committed) →
  `Unbekannt` (null hypothesis dominant).
- Commitment must not occur while two or more routes remain plausible; a shared
  start segment leaves the display in `Suche…` for as long as the paths coincide.
- Commitment is revocable: if evidence collapses, the display returns to
  `Suche…` rather than showing a wrong name.
- Never commits on a genuinely novel path.

### US-5 — Ahead/behind, before the route is known (must)

*As the user, I see whether I am faster or slower than usual as early as
possible, even before the route is identified.*

- While uncommitted, ahead/behind is computed against the pooled history of all
  still-plausible candidates (they share the traversed prefix, so they share its
  history).
- Displayed as signed seconds vs. the median at the current position.
- Suppressed only when the null hypothesis dominates.

### US-6 — Remaining distance and arrival time (must)

*As the user, once the route is known I see how far is left and when I will
arrive.*

- Remaining distance = route length − current position.
- ETA derived from the per-position median pace profile, scaled by today's
  observed performance ratio.
- Both suppressed while uncommitted (they are meaningless without a route).

### US-7 — Deviation alert (must)

*As the user, if I leave the route I am told, by vibration, without looking at
the watch.*

- Fires after sustained divergence, not on a single GPS jump.
- Haptic + ongoing-activity notification text. No screen takeover.
- Fires at most once per departure; re-arms after returning to the route.
- Never fires while uncommitted or while the null hypothesis dominates.

### US-8 — Per-route history (must)

*As the user, I open the phone app and see how this route has gone over time.*

- Route list: name, length, number of recordings, median time, best time.
- Route detail: table of all recordings (date, time, active duration, avg HR),
  and a plain polyline drawing of the route geometry. Per-kilometre splits were
  specified here, built, and removed again — see A.21.
- Session detail: the same table row plus which route it matched, coverage
  score, and where commitment occurred.

### US-9 — History backfill (must)

*As the user, I can seed the app with workouts I recorded before it existed so
routes are learned immediately rather than in three months.*

- Import TCX files (Google Health's per-workout export) from a file picker.
- Multi-select import; duplicates rejected by start-time proximity.
- Imported sessions participate in clustering identically to recorded ones and
  are flagged as imported (not re-written to Health Connect).
- A whole Google Health takeout is converted to a backup archive off the phone
  and restored in one go — Appendix A.15.

### US-10 — Replay and diagnosis (must, developer-facing)

*As the developer, I can replay any recorded session through the matcher and see
exactly what it decided and why.*

- Every session stores a match log: per-sample candidate weights, chosen route,
  commit/demote events.
- The `core` module replays a stored session offline at arbitrary speed and
  produces the same decisions as the watch did.
- Divergence between on-watch and replayed decisions is a bug.

---

## 3. Non-functional requirements

| # | Requirement |
|---|---|
| NFR-1 | A completed workout is never lost. Crash-safe append-only recording, fsync at least every 10 s. |
| NFR-2 | The watch is fully standalone during an activity. No phone dependency for recording, matching, or display. |
| NFR-3 | Battery cost is dominated by GNSS + HR, i.e. comparable to Google's Exercise app. Matcher CPU per sample < 5 ms for ≤ 50 candidate routes. |
| NFR-4 | Matcher and clustering logic are pure Kotlin, unit-testable on the JVM with no Android dependencies. |
| NFR-5 | All tuning constants live in one documented object. No magic numbers scattered in logic. |
| NFR-6 | Durations use `SystemClock.elapsedRealtimeNanos`, never wall clock. Wall clock is recorded once per session for display and Health Connect. |
| NFR-7 | No network access of any kind. The manifest declares no `INTERNET` permission. |
| NFR-8 | Total third-party dependencies outside AndroidX: one (kotlinx.serialization). |

---

## 4. Technology choices

Chosen for smallest moving-part count, not for elegance.

| Concern | Choice | Rationale |
|---|---|---|
| Language | Kotlin, single Gradle project | — |
| Modules | `:core` (pure JVM), `:wear`, `:phone` | Forces the matcher to stay Android-free and JVM-testable |
| Watch UI | Compose for Wear OS, `HorizontalPager`, 3 pages | No navigation library needed |
| Phone UI | Compose Material 3, 3 screens | — |
| Exercise data | `androidx.health:health-services-client` (pinned version) | The only way to get fused GPS/HR/distance on Wear OS |
| Health Connect | `androidx.health.connect:connect-client` | Phone-side write-back |
| Watch storage | Append-only JSONL file per session in app-internal storage | Crash-safe, no schema migrations, no DB on the critical path |
| Phone storage | Room + SQLite | Queries and history need a real DB |
| Serialization | kotlinx.serialization JSON, gzipped | Avoids a protobuf toolchain; payloads are ~40 KB gzipped per hour |
| Watch↔phone | Wearable Data Layer `DataClient` with `Asset` | Automatic retry and delivery when out of range; the robustness requirement in US-2 comes free |
| Dependency injection | None. Manual constructor wiring, one `AppGraph` object | Hilt would add build complexity for ~15 objects |
| Charts / maps | None. Compose `Canvas` polyline + tables | No API keys, no billing, no tile cache, no map library |
| Background work | `WorkManager` (phone only, one-shot after ingest) | Clustering is the only deferred job |
| Testing | JUnit + kotlin-test on `:core`; replay harness as a `:core` main | — |
| Localization | None. German strings hardcoded | Single user |
| SDK levels | `:wear` min/target 36; `:phone` min 34 / target 36 | One device pair; no back-compat burden |

**Explicitly rejected for v1:** Hilt, protobuf, Retrofit, any maps SDK, any
chart library, Compose Navigation, multi-module feature splits, Google Health
API / OAuth (§8.4), Firebase, Crashlytics.

---

## 5. Architecture

### 5.1 Data flow

```
  WATCH                                   PHONE
  ─────                                   ─────
  Health Services ──1 Hz──┐
                          │
                    RecorderService ──► session-<uuid>.jsonl (append-only)
                          │                     │
                          ├──► RouteMatcher ────┤ (reads route bundle)
                          │         │           │
                          │    LiveMetrics      │
                          │         ▼           │
                          │    Compose UI + haptics
                          │                     │
                          │              on stop / on reboot
                          │                     ▼
                          │              DataClient (Asset) ──────► IngestService
                          │                                              │
                          │                                              ▼
                          │                                        Room (sessions,
                          │                                         samples, routes)
                          │                                              │
                          │                                    ┌─────────┼─────────┐
                          │                                    ▼         ▼         ▼
                          │                              HealthConnect  Cluster  UI
                          │                                 writer      worker
                          │                                              │
          route-bundle.json.gz ◄──── DataClient ◄──────────── BundleBuilder
```

One-way, no bidirectional sync. The phone is the sole source of truth for
routes. The watch holds a read-only route bundle and its own write-ahead
session files. Nothing on the watch is authoritative except an in-progress
recording.

### 5.2 `:core` — pure Kotlin

Contains everything algorithmic. No Android imports; enforced by the module's
dependency list.

- `model/` — `Sample`, `SessionHeader`, `RouteGeometry`, `RouteProfile`,
  `RouteBundle`, `MatchState`, `MatchLogEntry`
- `matcher/RouteMatcher` — the online matcher. Pure: `update(sample) -> MatchState`
- `matcher/GridIndex` — 100 m spatial cells → candidate route/segment refs
- `cluster/RouteClusterer` — offline clustering of session tracks into routes
- `cluster/CoverageMetric` — the symmetric track-similarity metric
- `profile/ProfileBuilder` — per-position median time and HR curves
- `geo/` — resampling, haversine, point-to-segment projection
- `tcx/TcxParser` — import
- `Tuning` — every constant, with a comment giving its unit and rationale
- `replay/ReplayMain` — CLI entry point for US-10

### 5.3 `:wear`

- `RecorderService` — foreground service, `foregroundServiceType="health|location"`.
  Owns the `ExerciseClient` session. This type is mandatory; without it sessions
  die with `AUTO_ENDED_PERMISSION_LOST`.
- `SessionWriter` — append-only JSONL, fsync every 10 s and on pause/stop.
- `MatcherHost` — feeds samples to `:core` matcher; wraps every call in
  try/catch, and on repeated failure sets a disabled flag and carries on
  recording (NFR-1 outranks the feature).
- `BundleStore` — caches the latest route bundle received from the phone;
  survives without one (matcher simply reports `Unbekannt`).
- `Uploader` — on stop, and on service start (to catch reboot leftovers), puts
  each unacked session file as a `DataClient` Asset.
- UI: three pager pages — **Live** (current metrics), **Route** (name, remaining,
  ETA, ahead/behind), **Control** (pause/stop). Ongoing Activity notification
  mirrors the key metric.

Recorder state machine: `IDLE → PREPARING → ACTIVE ⇄ PAUSED → ENDING → IDLE`.
`PREPARING` calls `prepareExercise()` and waits for a GNSS fix before
`startExercise()`. Any unexpected `ExerciseUpdate` state transitions to a safe
state and preserves the file.

### 5.4 `:phone`

- `IngestService` (`WearableListenerService`) — receives Assets, parses,
  inserts into Room inside one transaction keyed on session UUID, acks the watch.
- `HealthConnectWriter` — writes session/route/HR/distance; retried by
  WorkManager; write status stored per session.
- `ClusterWorker` — after ingest, re-runs `RouteClusterer` over all sessions,
  rebuilds `RouteProfile`s, rebuilds and pushes the route bundle.
- `TcxImporter` — US-9.
- `de.hecate.core.takeout` — US-9 at the scale of a whole export (A.15).
- UI: **Sessions** list → **Session detail**; **Routes** list → **Route detail**.

### 5.5 Data model (phone, Room)

```
session(id TEXT PK, started_at_utc, tz_offset, exercise_type,
        active_duration_ms, total_distance_m, source, hc_write_state,
        matched_route_id NULL, match_coverage NULL, committed_at_m NULL)

sample(session_id FK, t_offset_ms, lat, lon, alt_m NULL,
       hr_bpm NULL, accuracy_m NULL, paused INTEGER)

route(id INTEGER PK, name, exercise_type, length_m, member_count,
      medoid_session_id, created_at, active INTEGER)

route_point(route_id FK, seq, lat, lon, cum_dist_m)      -- resampled at 10 m

route_profile(route_id FK, bin, median_cum_time_s, p25_s, p75_s, median_hr)

match_log(session_id FK, t_offset_ms, payload BLOB)      -- weights, events
```

`sample` is the only large table. One year of daily hour-long workouts is
~1.3 M rows — trivially fine for SQLite.

### 5.6 Matcher specification

State: a weight per active route plus a null hypothesis. Nothing is ever
"chosen" internally; the display layer decides what to show.

**Per sample:**

1. Query `GridIndex` for routes with geometry within `MATCH_RADIUS_M` of the
   sample. Routes not seen for `CANDIDATE_DROP_M` of travel are dropped.
2. For each candidate, project the sample onto the polyline within a window of
   ±`SEARCH_WINDOW_M` around the predicted position (predicted from previous
   position + travelled distance). This keeps loops and out-and-backs
   unambiguous, since position advances monotonically.
3. Emission log-likelihood `= -(d² / (2σ²))`, with
   `σ = max(GPS_SIGMA_M, reported accuracy)`.
4. Null hypothesis gets a fixed log-likelihood equivalent to
   `d = NULL_DISTANCE_M`, with prior floor `NULL_PRIOR_FLOOR`.
5. Accumulate in log space with decay `WEIGHT_DECAY` applied to prior weights,
   so an early mistake is forgettable. Normalise.

**Priors at start:** historical frequency by (exercise type, weekday bucket,
time-of-day bucket), floored so nothing starts at zero, with
`NULL_PRIOR_FLOOR` reserved for "unknown".

**Commitment:** commit when top weight ≥ `COMMIT_WEIGHT` for
`COMMIT_HOLD_SAMPLES` consecutive samples **and** ≥ `COMMIT_MIN_M` travelled.
Demote when top weight < `DEMOTE_WEIGHT`. Report `Unbekannt` when the null
hypothesis exceeds `NULL_DOMINANT_WEIGHT`.

**Ahead/behind:** weight-averaged median cumulative time at current position
across all candidates above `LIVE_CANDIDATE_MIN_WEIGHT`; delta vs. elapsed
active duration.

**ETA:** `now + (median_time_at_end − median_time_at_pos) × k`, where `k` is
the observed-vs-median ratio clamped to `[ETA_K_MIN, ETA_K_MAX]` and smoothed
over `ETA_SMOOTH_SAMPLES`.

**Deviation:** min distance across live candidates > `DEVIATION_M` for
`DEVIATION_HOLD_SAMPLES` → alert once; re-arm below `DEVIATION_CLEAR_M`.
Gated on committed state.

**Batching:** samples may arrive in bursts when the screen is off. Process in
timestamp order; the result must be identical to streaming (verified by US-10).

### 5.7 Clustering specification

- Resample every session track to 10 m spacing; drop paused spans.
- `coverage(A,B) = min(fracWithin(A→B, CLUSTER_TOL_M), fracWithin(B→A, CLUSTER_TOL_M))`
- `distance = 1 − coverage`; average-link agglomerative; cut at
  `CLUSTER_CUT`. Direction reversals are never merged.
- Cluster becomes an active route at ≥ `MIN_MEMBERS` members.
- **Geometry = the medoid member's track**, not an average. Averaging polylines
  is fiddly and error-prone; a medoid is exact, cheap, and good enough.
- Profile = per-10 m-bin median cumulative time and median HR across members,
  with p25/p75 for later use.
- Recompute from scratch on each ingest. O(n²) on a few hundred sessions is
  milliseconds; incremental clustering is deferred (§8).

---

## 6. Development setup

### 6.1 Prerequisites

- Android Studio (current stable), JDK 25 (the `jvmToolchain` every module declares; app bytecode stays at 17)
- Pixel Watch 4 with Developer options enabled
- Android phone paired to the watch
- Android SDK platform-tools on `PATH`

### 6.2 Watch debugging

```bash
# Watch: Settings → Developer options → ADB debugging ON, Wireless debugging ON
# Watch: Wireless debugging → Pair new device  (shows IP:port + 6-digit code)
adb pair <watch-ip>:<pair-port>
adb connect <watch-ip>:<debug-port>
adb devices          # expect the watch listed as "device"
```

### 6.3 Signing and install

Do **not** ship with the debug keystore. Create one release keystore, back it up
outside the machine, and always install with it — a lost key means a signature
mismatch, an uninstall, and the loss of any un-uploaded session on the watch.

```bash
./gradlew :phone:installRelease
./gradlew :wear:installRelease     # with the watch connected via adb
```

### 6.4 Permissions to grant manually after install

- Watch: `ACCESS_FINE_LOCATION` (allow all the time),
  `android.permission.health.READ_HEART_RATE`, `ACTIVITY_RECOGNITION`,
  `POST_NOTIFICATIONS`
- Phone: Health Connect → app permissions → write exercise, exercise route,
  heart rate, distance
- Turn **off** the watch's automatic activity detection to avoid duplicate
  sessions in Health Connect.

### 6.5 Test strategy

| Layer | What it covers | How |
|---|---|---|
| `:core` unit tests | metric, clustering, projection, profile maths | JUnit, synthetic geometries (5° divergence, self-intersecting loop, injected jitter) |
| Replay harness | matcher decisions on real tracks | `ReplayMain` over a fixture set of stored sessions, golden-file assertions |
| Wear emulator | service lifecycle, permissions, UI layout, Ongoing Activity | Emulator synthetic data — plumbing only; it emits a **single default route** with identical values every run, so it can never test matching |
| Event injection | auto-pause handling | `adb shell am broadcast -a "whs.AUTO_PAUSE_DETECTED" com.google.android.wearable.healthservices` |
| On-wrist | real GNSS noise, ambient batching, battery | Log to file, `adb pull` afterwards; no debugger survives a run |

**Fixture set (build this first):** work route, two shortcuts of it, the reverse
direction, one novel path, one with a 90 s GPS dropout, one with a long pause.

### 6.6 Build order

| Milestone | Deliverable | Exit criterion |
|---|---|---|
| M0 | `:core` + replay harness + TCX import | Backfilled history clusters into plausible routes, verified by eye on the polyline view |
| M1 | `:wear` recorder + `:phone` ingest | 10 consecutive workouts recorded and ingested with zero losses, including one with the phone off |
| M2 | Health Connect write-back | Sessions appear in Google Health; no duplicates |
| M3 | Matcher on watch, display only | Match decisions on the wrist equal replayed decisions for the same track |
| M4 | Live metrics + deviation haptics | Correct behaviour on the full fixture set, walked in reality |
| M5 | Phone history UI | US-8 satisfied |

M0 before M1 is deliberate: the hardest logic gets built under a fast feedback
loop, not at walking pace in the rain.

---

## 7. Risks

| Risk | Mitigation |
|---|---|
| Health Services / Health Connect are alpha-versioned and churn | Pin versions; upgrade deliberately |
| Foreground service killed mid-workout | Append-only file + resume on service restart; upload leftovers on boot |
| Duplicate sessions in Health Connect from the watch's own auto-detection | Disable auto-detection; dedupe by time window on ingest |
| Matcher regression from constant tuning | Golden-file replay tests over the fixture set |
| Cold start: nothing works for weeks | TCX backfill in M0 |
| Ambient staleness perceived as a bug | Documented as expected; batching mode left at default until measured |
| Debug-key reinstall wipes an un-uploaded session | Release keystore, backed up; upload before reinstalling |

---

## 8. Deferred — explicitly not in v1

### 8.1 Route variants as a hierarchy
Shortcuts become independent routes that happen to share a prefix. The matcher
handles this correctly without any variant model. A parent/child structure with
shared-segment merging is the elegant version and buys better naming and pooled
statistics — but polyline merging under GPS noise is the single fiddliest piece
of the whole design. Revisit only if the route list becomes cluttered.

### 8.2 Automatic session start
Geofences plus activity recognition could start the session hands-free. Cut
because background foreground-service launch is restricted, false positives are
worse than missed ones, and the first 100–300 m would be lost to GNSS lock —
exactly the segment the matcher needs.

### 8.3 Route graph and merged prefixes
Independent polylines with all-candidate scoring give identical results with a
fraction of the code. Revisit above ~100 routes.

### 8.4 Google Health API integration
Automated history pull via `exportExerciseTcx` requires a Cloud project, OAuth
client, and restricted-scope handling. Manual TCX export achieves the same
backfill with zero moving parts. Revisit only if continuous two-way sync
becomes desirable.

### 8.5 Everything else
Map basemaps (osmdroid), chart library, elevation-adjusted pace, weather or
temperature conditioning, tiles and complications, watch-face integration,
route navigation/turn cues, incremental clustering, multi-user, phone-recorded
sessions, localization, export to GPX/Strava, per-segment leaderboards,
notification of personal bests.

---

## Appendix A — Deviations from this specification, as implemented

This appendix is part of the implementation, not of the original specification.
It records every place where the shipped code departs from the text above, and
why. The specification itself is unchanged.

### A.1 The matcher needs a transition model, not only an emission model

§5.6 step 2 describes the projection window as "±`SEARCH_WINDOW_M` around the
predicted position", and says this "keeps loops and out-and-backs unambiguous,
since position advances monotonically".

A symmetric window does not make position advance monotonically. It lets the
projection move backwards by up to `SEARCH_WINDOW_M` per sample, which at 1 Hz
is far more than anyone travels in a second. The consequence is specific and
fatal to US-3: a route's own reverse occupies exactly the same ground, so a
forward run tracks the reverse route perfectly, just with the position counting
down. Tightening the window until it is narrower than the per-sample travel is
not a fix either — the travel per sample differs by a factor of six between
walking and cycling, and goes to zero whenever the user stops.

Implemented instead:

- The window is asymmetric: `[position − BACKTRACK_SLACK_M, position + travel +
  SEARCH_WINDOW_M]`.
- The per-sample log-likelihood gains a transition term, `−(advance − travel)² /
  (2 σ_adv²)`, where `advance` is how far the projection moved along the route.
  A route being run the wrong way round advances by minus the travelled
  distance, and pays for it on every single sample.
- Each candidate carries a smoothed `advance / travel` ratio. About 1 means
  "being followed", about −1 means "being followed backwards", about 0 means
  "left behind". A candidate below `MIN_TRACKING_RATIO` is not treated as being
  on the route at all.

### A.2 Commitment is gated on geometry, not only on weights

§5.6 commits when the top weight holds above `COMMIT_WEIGHT` for
`COMMIT_HOLD_SAMPLES` samples. US-4 additionally requires that "commitment must
not occur while two or more routes remain plausible; a shared start segment
leaves the display in `Suche…` for as long as the paths coincide".

Weights cannot deliver that second requirement, and the reason is not a tuning
problem. Where two routes share a street they fit every sample equally well, so
the ratio of their weights measures the last minute's GNSS bias and nothing
else. GNSS error is strongly correlated over a minute or more, so that bias
holds steady for long enough to satisfy any consecutive-sample test one cares to
write. On the fixture set the matcher committed to the wrong branch 1.5 km
before the paths diverged.

Implemented: commitment additionally requires every other live, tracking
candidate to be at least `COMMIT_SEPARATION_M` further from the current position
than the leader is. While a rival is still on the same ground, nothing is
decided — which is what US-4 asks for, stated in the units the question is
actually about.

### A.3 `NULL_DISTANCE_M` must exceed `DEVIATION_M`

§5.6 gives the null hypothesis a fixed log-likelihood equivalent to
`d = NULL_DISTANCE_M`, and US-7 fires the deviation alert after sustained
divergence beyond `DEVIATION_M`, "never while the null hypothesis dominates".

If `NULL_DISTANCE_M` is below `DEVIATION_M`, these two rules cancel each other
out. Leaving the route by more than `NULL_DISTANCE_M` makes "unknown" the better
explanation, the match is demoted, and the alert is suppressed — by the very
departure it exists to report. With the originally suggested ordering (45 m null
distance, 60 m deviation threshold) the alert can never fire at all.

Implemented: `NULL_DISTANCE_M` is 120 m, comfortably above the 60 m deviation
threshold, and `MAX_ABS_LOG_LIK` is raised to 60 so that the null and a route a
kilometre away do not both clamp to the same value (which would stop the matcher
ever reporting `Unbekannt`). Both constraints are documented at the constants.

### A.4 The deviation detector is armed by commitment, not gated on it

Related to A.3 but separate. Even with the null pushed out of the way, a
sustained departure eventually demotes the match — correctly, since the user
really is no longer on a known route. If the detector is reset on demotion, a
sufficiently decisive departure is never reported.

Implemented: commitment *arms* the detector on that route, and it then measures
the distance to that route directly until the user is back on it or a different
route is committed. Demotion and even dropping the candidate do not disarm it.
This is a deliberate reading of US-7's intent ("if I leave the route I am told")
over its literal wording ("never fires ... while the null hypothesis dominates").

### A.4b Manual route priority

Not in the specification at all; added after §8.1's consequences were looked at
in practice.

§8.1 makes shortcuts independent routes that happen to share a prefix, and A.2
above stops the matcher choosing between routes while they coincide. Together
these are correct and unhelpful: on a route family sharing 3 km of a 5 km run,
the watch shows `Suche…` — and no remaining distance and no ETA — for most of
the workout. The matcher is right that it cannot know. The user usually does.

Implemented: every route carries an integer priority, −2..2, settable on the
route detail screen and stored alongside the name. Where two or more routes are
*contenders* — live, being tracked, and within `COMMIT_SEPARATION_M` of the
nearest one — and exactly one of them outranks the rest, the matcher commits to
that one and reports remaining distance and ETA from it. A tie at the top
decides nothing and leaves the display searching, so the default (every route at
0) reproduces the previous behaviour exactly; the feature is opt-in by
construction rather than by a flag.

The range was 1..10 until schema v7 rescaled it (A.21): only the *order* of
these numbers has ever mattered, so ten values offered a precision the mechanism
cannot use, and a neutral value of 5 out of 10 invited the user to spend the
other nine.

Priority does not touch the probabilistic weights, and cannot make the matcher
believe the user is somewhere they demonstrably are not:

- It only ever selects among routes that already explain the current position
  equally well. On a novel path there are no contenders and nothing to select.
- It is re-decided every sample for as long as the tie lasts. The moment the
  paths part, the ordinary evidence rule takes over and will demote a
  priority-held commitment that the ground contradicts — so preferring the long
  route costs about twenty seconds of wrong label on the day you take the
  shortcut, and nothing else.
- `MatchState.committedByPriority` and the `COMMIT_BY_PRIORITY` log event record
  which of the two happened. The watch shows an assumed route in blue and a
  worked-out one in green, so a glance distinguishes a finding from a setting.

### A.4c The deviation alert is about the route network, not one route

A.4 armed the detector on the committed route and measured against that route
alone. With A.4b making commitment possible inside a shared prefix, the
consequence became visible: commit to the long route, take a learned shortcut,
and the watch buzzes "Abweichung" for the twenty seconds it takes the shortcut
to commit — an alarm for doing something entirely familiar.

§5.6 already had this right — "min distance across live candidates" — and A.4
had narrowed it. Restored: the detector measures the minimum across the armed
route and every live, tracking candidate. Leaving one known route for another is
not leaving the route.

### A.5 Route identity across re-clustering

§5.7 recomputes clustering from scratch on each ingest, and US-3 makes routes
renameable. The specification does not say how a renamed route survives the next
re-clustering; recomputing from scratch would issue fresh ids and discard the
name.

Implemented: `RouteReconciler` matches each freshly computed cluster onto the
existing `route` rows by Jaccard overlap of their member sets, keeping the id,
the creation date and any user-given name when the overlap reaches
`ROUTE_REIDENTIFY_OVERLAP`. Routes that no longer correspond to any cluster are
deactivated rather than deleted. This needs the member set to be stored, hence
the `route_member` table, which is not in §5.5.

### A.6 Additional columns beyond §5.5

- `route_member(route_id, session_id)` — see A.5.
- `session.avg_hr` — US-8's session table needs average heart rate, and
  computing it once at ingest beats aggregating a million sample rows per view.
- `session.hc_error` — the Health Connect write status is specified; the reason
  a write failed is what makes it actionable.
- `session.ingested_at` — diagnostics.
- `session.ended_at_utc` — added in schema v2. §5.5 gives the session a start and
  an active duration, which are not enough to place its end: a session with
  pauses ends later than start plus active time. Health Connect rejects a write
  whose route points are not *strictly* inside the session — an exclusive bound —
  so the real stop time from the footer had to stop being discarded at ingest.
  Rows written before v2 keep NULL, because the value cannot be reconstructed.
- `route.name_is_custom` — distinguishes an auto-generated name, which may be
  regenerated, from one the user chose, which may not.
- `route.priority` — see A.4b, and A.21 for the rescaling to −2..2. Like the
  name, preserved across re-clustering.
- `sample.active_ms` — §5.5 stores only `t_offset_ms`. Splits, ahead/behind and
  the profile all need elapsed *active* time, which cannot be reconstructed from
  the wall-clock offset once a session contains pauses.
- `meta(key, value)` — holds the route bundle version counter.
- `route_profile.median_alt_m` — the route's elevation, one median per bin (A.20).

### A.7 Dependencies outside AndroidX

NFR-8 allows exactly one third-party dependency outside AndroidX,
kotlinx.serialization. The Wearable Data Layer, which §4 selects as the
watch↔phone transport, ships in `com.google.android.gms:play-services-wearable`
and has no AndroidX equivalent. The two requirements are not simultaneously
satisfiable; the transport was kept, since the robustness it provides is
explicitly load-bearing for US-2.

Both Android modules therefore carry two non-AndroidX dependencies:
kotlinx.serialization and play-services-wearable. `:core`, where the rule
matters most, has exactly one and no Android dependency at all.
`kotlinx-coroutines-play-services` was deliberately *not* added; the handful of
`Task` awaits use `Tasks.await` on an IO dispatcher instead.

`kotlinx-coroutines-android` is declared by both Android modules and is not
counted, which is a judgement rather than an oversight: it is the coroutines
runtime the language's own `suspend` functions need, and `androidx.compose.ui`
pulls it in transitively regardless, so it is in the APK whether this project
names it or not. Declaring it makes the version explicit in the catalog instead
of inherited from whichever AndroidX artifact happens to win.

A third name appears in `:wear`'s dependency block since the module was first
compiled: `com.google.guava:guava`, `compileOnly`. Health Services returns a
`ListenableFuture` from every `*Async` method but declares Guava at runtime
scope, so the type is missing from the compile classpath while the
implementation is already in the APK. It is a declaration of something that was
always shipping, not a new dependency, and it contributes nothing to the
artifact — but the count above is now two declared plus one compile-time-only,
and saying otherwise would be the kind of accounting this appendix exists to
prevent. See OPEN-POINTS.md §1.1.

### A.8 Scope not reached

- §6.5's emulator layer has not been exercised. Its on-wrist layer has, once:
  a Pixel Watch 4 and a Pixel 8 completed a full round trip, which is what found
  the three defects OPEN-POINTS §3.2 lists. One session is not the ten
  consecutive recordings §6.6 asks for.
- §6.6's exit criteria are stated in terms of real workouts (ten consecutive
  recordings, sessions appearing in Google Health, behaviour on the fixture set
  "walked in reality"). The code implementing each milestone is complete; the
  criteria themselves are field measurements and remain open.

### A.9 Speed in km/h, never pace

US-6 describes the ETA as coming from "the per-position median **pace** profile",
and the session detail screen originally showed a `Ø Pace` row in min/km, with
the per-kilometre splits in the same units. Running apps conventionally speak
pace, so that is what got built.

The user does not. Implemented: **every speed the UI shows is km/h, and nothing
anywhere shows pace.**

- The watch's metrics page shows `10,8 km/h` where it showed `5:32 /km`.
- The phone's session detail shows `Ø Geschwindigkeit … km/h`.
- The per-kilometre splits table was headed `Kilometer-Splits (km/h)` and its
  Median / Beste / Schlechteste columns were speeds, "Beste" still meaning the
  fastest kilometre. That table has since been removed altogether (A.21); the
  rule it illustrated holds for everything that remains.

This is a presentation change and nothing more. The stored measurement is
unchanged — the route profile holds cumulative *time* per bin (§5.7), and
`deltaVsMedianS` is still seconds against the median (A.2 of the open points has the sign convention). Speed is derived at the
formatter and never persisted, so no database column, no bundle field and no
wire constant moves, and existing sessions need no migration.

`Tuning.ETA_RATIO_CLAMP`'s documentation still calls its `k` an "observed-vs-median
pace ratio". That is deliberate: `k` is a dimensionless ratio internal to the ETA
calculation, not a displayed quantity, and it lives in `:core`, which has no
opinion about units and is not going to acquire one.

### A.10 A backup archive, and why it is a file rather than a cloud

Not in the specification. §8.5 defers "export to GPX/Strava", which is about
*sharing* a workout; this is about not losing one.

The problem, traced through the code: the phone's Room database is the only
complete copy of anything. The watch deletes each session file the moment the
phone acknowledges it (US-2), so the watch is a delivery buffer, not a second
copy. Health Connect is written to and never read — the app holds three
`getWritePermission` grants and no read grant — and what it receives is lossy:
no per-sample GPS accuracy, no match log, and no route names or priorities,
since the exercise session's title is "Lauf"/"Gehen"/"Radfahren". Re-importing
Google Health's TCX export, the only recovery path there was, drops accuracy
(`TcxParser` sets it null), cannot restore what the watch decided on the day,
and re-clusters into freshly auto-named routes at the default priority. Names
and priorities are the only things in the database a human typed, and they were
the one thing nothing could bring back.

Implemented: **export to a file, and restore from a file.** Both through the
Storage Access Framework, so the user chooses the location, the app needs no
storage permission, and NFR-7 is untouched — the app still declares no
`INTERNET` permission and still talks to no network. Where the file goes
afterwards, Google Drive included, is the user's business and happens outside
the app entirely.

Android's own Auto Backup was considered and rejected on measurement. Its quota
is 25 MB per app, and over the exact Room schema at 300 sessions of 45 minutes
at 1 Hz the database is 145 MB — about 51 sessions, two months, before the
system stops backing up **silently**. A direct Google Drive integration solves
the quota and would allow restore on demand, but needs the `INTERNET` permission
NFR-7 forbids, an OAuth client whose refresh tokens expire every 7 days unless
the consent screen is published, `kotlinx-coroutines-play-services` — the
dependency A.7 records as deliberately declined — pulled in transitively through
`play-services-auth`, and it is *less* private than Auto Backup, which is
end-to-end encrypted with the device PIN while Drive files are not. Both were
declined deliberately; the file is the decision.

The format is a ZIP: `manifest.json`, a `database.json` holding every table
except samples, and one `sessions/<id>.jsonl` per session. Same 300 sessions:
about 15 MB, a tenth of the SQLite file, because SQLite spends 36 bytes of UUID
plus an index entry on every sample row. Those session files are
`SessionCodec`'s format — the watch's own — so unzipping `sessions/` into
`fixtures/` feeds the replay harness directly, with no converter.

Two decisions worth naming:

- **Restore merges; it never replaces.** A session or route already present by
  id is left exactly as it is and counted as skipped. Restoring into an empty
  database — the case this exists for — is therefore exact, and restoring into a
  populated one cannot destroy what is already there. The cost is that merging
  an archive into a database that already has routes keeps the local routes'
  names, since their ids are taken.
- **Restore does not go through `SessionRepository.ingest`.** Ingest would reset
  `hcWriteState` to PENDING, re-write every session to Health Connect, and
  re-derive `committedAtM` — a fact about what the watch decided on the day,
  which nothing can derive again. Rows are written back as they were.

Three fields cannot survive the round trip because the phone never stored them:
`SessionHeader.bundleVersion` and `appVersion` are dropped at ingest, and
`SessionEndReason` is not a column, so the exported footer says `IMPORTED` —
true of the file being read rather than a guess at how the recording ended.

`:phone` gains its first tests for this. They are plain JVM tests of `ArchiveCodec`,
which is the ZIP and JSON layer with no Android and no Room in it — deliberately
split out from `ArchiveRepository` so that the part which can silently lose data
is the part that gets read back in a test.

### A.11 Material 3 on both ends, and one watch page instead of two

§4 selects "Compose for Wear OS, `HorizontalPager`, 3 pages" for the watch and
"Compose Material 3, 3 screens" for the phone. Both rows are still true in
outline; what they left unsaid is what needed fixing.

**The phone was never themed.** `HecateApp` called `MaterialTheme {}` with no
arguments, which yields the baseline purple placeholder scheme, and then every
composable under it overrode that scheme by hand: `Color.Gray` for secondary
text, `Color(0xFF00796B)` for a matched route, `Color(0x14000000)` for an
unselected chip, `Color(0xFFC62828)` for delete, and font sizes as bare `sp`
literals. The app was on Material 3 in its dependency list and nowhere else, and
the consequence was not only that it looked a decade old: hardcoded colours have
no dark variant, so there was no dark mode at all.

Implemented: a `HecateTheme` built on `dynamicDarkColorScheme` /
`dynamicLightColorScheme`. `minSdk 34` is well past the API 31 floor for dynamic
colour, so there is no version fence and no hand-written fallback palette to
drift out of sync. Every colour in the phone UI is now a scheme role and every
size a typography token. Dark mode follows the system for free.

Two colours deliberately did **not** become roles. The start and end markers on
the route drawing say which end of a track is which, which is a fact about the
track; a wallpaper must not be able to recolour one into the other. They live in
`HecateMapColors` and are fixed. The same rule governs the watch's status
colours in `HecateStatus`: "faster than usual" is green on every watch face.

**The watch was on Material 2.5.** `androidx.wear.compose:compose-material` is
the previous generation, and its `Chip` — which was the start screen and half
the control page — is the single most dated thing the app drew. The move to
`androidx.wear.compose:compose-material3` needed no new version line in the
catalog: Material 3 ships at the same `wearCompose` version as the rest of the
group. It brings `AppScaffold`/`ScreenScaffold`, and with them `TimeText`; the
app previously showed no clock at all, which on a watch is conspicuous.

**Three pages became two, and this is a behaviour change, not a restyle.** The
Live/Route split meant that "how am I doing against the usual" — US-5, the one
question this app exists to answer — lived on a page you had to swipe to. On a
bike that is a hand off the bars. Live and Route are now one dense page: route
name, duration, distance, speed, heart rate, delta, remaining and ETA, all
visible at once. Control keeps its own page, and keeping it there is the point —
pause and stop are the two actions a sleeve must not trigger, so the swipe that
used to be a cost is now the guard.

The dense page is laid out against a height budget set by the smaller watch: a
41 mm face is 204 dp tall, `TimeText` takes the first 26, and the six rows have
to live in the remainder. That is what decides the two-column grid, the inline
12 sp units instead of a label line under each number, and the secondary pair
(heart rate, delta) being set smaller than the primary one (distance, speed).
The delta earns its rank by colour rather than by size. The column can still
scroll, but only so that a face smaller than either of these clips nothing —
scrolling is not part of the design.

`HorizontalPagerScaffold` replaced the hand-rolled `PagerDots` with the curved
indicator that fades out on its own, and `EdgeButton` gives Beenden the
bottom-bezel shape, both of which were pixels the numbers wanted back.

**What was declined.** Material 3 Expressive — `MaterialExpressiveTheme`,
`MotionScheme`, `ButtonGroup`, `LoadingIndicator`, the Flexible app bars — is
compiled into material3 1.4.0, which is what Compose BOM 2026.09.00 pins, but
every piece of it is `internal` at that version and only becomes public in the
1.5.0 alphas. Taking it means overriding the BOM with an alpha across the whole
module, which §7 pins against, for what is here a matter of styling. The stable
API gets the same dynamic colour and the same type scale. Also declined:
`material-icons-extended`, because release builds run with
`isMinifyEnabled = false` (§6.3) and would ship every unused icon — the ~40 of
`material-icons-core` cover every icon this app needs; and
`material3-adaptive-navigation-suite`, which earns nothing on one phone.

NFR-8 is untouched: every artifact added here is AndroidX, so third-party
dependencies outside AndroidX remain one (kotlinx.serialization). The only new
transitive artifact anywhere is `androidx.graphics:graphics-shapes`, which Wear
Material 3 depends on. §4's "no navigation library" also still holds — Wear
Material 3 does not drag one in, and the phone still switches screens on a state
enum.
\n
### A.12 The session's lifecycle events are kept, not parsed and dropped

Not a deviation from the specification so much as a hole in the implementation
of it, found while working out what could be debugged from a ride in the field.

`SessionEventType` has eight members — `PAUSE_MANUAL`, `RESUME_MANUAL`,
`PAUSE_AUTO`, `RESUME_AUTO`, `GPS_LOST`, `GPS_ACQUIRED`, `SERVICE_RESTARTED`,
`MATCHER_DISABLED` — and `RecorderService` emits every one of them into the
append-only session file as an `"e"` record, beside the samples and the match
log. `SessionCodec.decodeSession` reads them back into `RecordedSession.events`
correctly. And then the phone threw them away: there was no table, ingest never
stored them, and `ArchiveRepository` rebuilt sessions for export without them,
so `ArchiveCodec`'s events section — which has always been written — was always
empty. The moment the phone acked and the watch deleted its copy (US-2), the
events were gone.

This mattered more than it looks. The match log says what the *matcher* decided,
per sample, and that part was always well kept. The events say what happened to
the *recording*, and they are the evidence for exactly the things OPEN-POINTS
lists as unverified: whether auto-pause fires (§3.1), what surrounds the
unexpected `USER_RESUMING` (§3.5), how often the fix is lost in real terrain, and
whether the foreground service was ever killed mid-workout. All of it was being
recorded on the wrist and discarded on arrival.

Implemented: a `session_events` table, one blob per session, schema v3. It
deliberately mirrors `match_log` rather than becoming a row-per-event table —
the two are written together, read together, and deleted together by the same
`ON DELETE CASCADE`, and a handful of events per session does not want an index.
`BundleCodec` gains the `encodeEvents`/`decodeEvents` pair beside the match log's.
Ingest writes them inside the same transaction as the samples, because they are
part of the recording rather than a commentary on it. Export reads them back, so
the archive's events section now carries what it always claimed to.

Sessions ingested before v3 have no row and never will: the events died with the
watch's copy of the file and nothing can derive them again. The migration adds an
empty table and does not pretend otherwise.

The phone also shows them. Session detail gains a "Verlauf der Aufzeichnung"
block listing each event with its offset and note — uncapped, unlike the match
log's fifty, because there are only ever a handful and the one worth seeing is
the one you did not expect to be there. §3.1 and §3.5 are now answerable from the
phone with no cable at all.

One thing worth recording about how this stayed hidden. `ArchiveCodecTest`'s
round-trip test asserted header, footer, match log and samples, and never looked
at `events` — so a field that was always empty round-tripped as always empty and
passed. The fixture now carries four events, which puts them under every test
that uses it, and two further tests name them directly. The lesson is the one
A.10 already states about this file: the part that can lose data silently is the
part that has to be read back in a test, and "round trip" has to mean every
field, not every field someone remembered.
\n
### A.13 A settings panel, a back stack, and what a fast run is

Seven changes to the phone UI, all from using the thing. Grouped here because
they share a cause: §5.4 specified which screens exist and said nothing about
how to get between them or what they should be called.

**Settings is now a panel, not two overflow menus.** TCX import, backup export
and backup restore lived behind a `⋮` on the recordings screen; re-clustering
lived behind a separate button on the routes screen. Neither was discoverable,
and a menu item has room for a label but not for an explanation — which is how
"Health Connect einrichten" came to be a button nobody could explain. The panel
costs one slot in the bottom bar and gives every action a sentence.

**Back walks the panels.** The old `BackHandler` was armed only on the detail
screens, so pressing back from a panel the user had reached one tap earlier fell
through to the launcher and closed the app. The view model now keeps a bounded
history and `back()` reports whether it handled the press, leaving the system to
close the app only at the end of it. Bounded at 16 because this is a
convenience, not an undo log: someone who has tapped between two panels forty
times wants the next few back presses to work, not all forty. Deleting a
recording drops that recording's detail screen from the history as it goes —
otherwise back leads to a screen about something that no longer exists.

**Re-clustering shows that it is running.** Clustering is O(n²) over every
recording (§2.6), so on a real history the button appeared to do nothing.
WorkManager already knows the state of the unique job; the panel now watches it
and swaps the button for a spinner, and the routes list grows a banner, since
that list is where the effect actually lands. The button is replaced rather than
disabled: the job is enqueued with `REPLACE`, so a second press would restart it
from the beginning rather than queue anything.

**"Sitzung" became "Aufzeichnung".** User-facing strings only. `session` stays
the wire format, the table name, the archive's directory and the watch's
filename — renaming it downward would change the backup format in order to
relabel a screen. `Screen.Recordings` is the boundary.

**Every screen keeps its scroll position.** Screens are swapped by a `when`, so
the one being left drops out of composition and everything it remembered goes
with it — which meant a back press out of a recording landed at the top of a
year of them, and the same on the way out of a route. A `SaveableStateHolder`,
keyed per screen, keeps each key's saved state alive while its composable is
gone; it is the mechanism Navigation-Compose uses for exactly this, and nothing
inside the screens had to change, because `LazyColumn`'s own state and
`rememberScrollState` were already saveable and were simply being saved into a
slot that no longer existed. Detail screens are keyed by id rather than by
kind: two recordings scrolled to different places are two different places.

**Delete asks first, and says what goes.** The dialog names the recording's date,
distance and duration, and then what is destroyed with it — samples, match log,
recording history — and what is not: the Health Connect entry stays. Deleting
the only complete copy of a workout on one tap was the last unguarded
irreversible action in the app.

**The watch says how many routes it has and how old they are**, not which
version of the bundle it is holding. The version is a counter the phone bumps on
every rebuild, so "v42" answers nothing a person wants to know before pressing
start; "4 Routen · vor 3 Tagen" answers all of it. The counter still exists and
is still what the Data Layer item and the session header are keyed on — it is
simply not a fact about anybody's day (A.20).

**Activity types have icons.** One vector drawable per type in `res/drawable`,
the official Material Symbols paths from `google/material-design-icons`, shown on
both lists and both detail screens. Not `material-icons-extended`, for the
reason A.11 already gives: release builds run with `isMinifyEnabled = false`, so
an unused icon is an icon that ships. Symbols are drawn in a 960×960 box whose
origin is the baseline — the viewBox is `0 -960 960 960` — and Android's
viewport has no negative origin, so each drawable wraps its path in a
`<group android:translateY="960">` rather than having the path rewritten by
hand, which would make the next update a transcription exercise. The lookup is
over the enum rather than over its name, so a type without a drawable is a
compile error rather than a blank space at run time — which is what it used to
be, back when the icon was chosen by matching the string `"RUNNING"`.

**Fast and hard recordings are marked.** A recording is marked fast when its
average speed is at or above the 85th percentile of the recordings on the same
route — the 75th until A.21 raised it and gave the mark an arrow — and marked
for effort when its average heart rate is. Three decisions worth naming:

- **Per route, or not at all.** A recording with no matched route has nothing to
  be fast against and is never marked. Ranking is within the route, so the best
  climb of a slow hilly route is marked even though every recording of the flat
  route beat it.
- **Four members minimum.** A route exists at three (§5.6), and a percentile of
  three values is "the best one" wearing a statistician's hat. Four is the first
  count at which the threshold falls between two recordings rather than on one,
  and even then only the best of them clears it — this marks a personal best
  against the route and claims no more.
- **Speed and heart rate rank independently**, because a hard effort on a slow
  day is worth seeing precisely when the two come apart. Heart rate ranks only
  over the members that have one, and only if at least four do: a route whose
  older recordings predate a heart rate strap must not let those absences drag
  the threshold down to where everything qualifies.

The arithmetic lives in `ui/Standouts.kt` rather than in the view model, with no
Android in it, and has eleven tests. It is the only arithmetic in the phone UI
and the kind whose bugs hide well — a wrong threshold still highlights
*something*. The emphasis is colour **and** weight, never colour alone: the two
marks sit in the same row and have to be tellable apart by someone who cannot
separate the hues.

**Health Connect is a switch.** It cannot be made automatic, and that is not a
limitation of this app: Android health permissions are granted by the user
through a system-owned sheet, and `PermissionController`'s only entry point is
an `ActivityResultContract`. So the action stays, but it moves to settings, says
what it does, and gains the half it never had — `revokeAllPermissions()` turns
it off. A one-way "einrichten" button left the user to go and find Android's own
settings to undo it, which is most of why it read as inexplicable. Four states
rather than a boolean, because "off" has three different causes that need
different words: not installed, too old, or simply not granted yet. The state is
re-read on every `onResume` and whenever the panel appears, since both the grant
and its removal can happen outside this app.
\n
### A.14 A heatmap of everything, drawn around the routes' own extent

Not in the specification. §4 rejected every maps SDK and §8.5 defers charts; this
is neither. It is the app's own tracks drawn against an empty ground, with no
map behind them — which for ground you have covered two hundred times is a more
recognisable picture than a street map would be, and costs no API key, no tiles
and no third-party dependency. NFR-7 and NFR-8 are untouched.

**Precomputed, not drawn on demand.** The raster is O(samples) over the whole
history — the same pass the clusterer already makes over the same data — so
`ClusterWorker` builds it and stores it, and the routes screen reads a row. It
runs last in that worker, after the bundle is pushed, so a failure while drawing
can never cost the watch its routes. Schema v4 holds it as one gzipped blob:
written whole, read whole, never queried, and a raster's worth of counts as rows
would be a table nothing ever selects from. (A.21 made the raster a great deal
larger and run-length encoded the blob; everything else here still holds.)

**The extent is anchored on the routes, never on the recordings.** This is the
whole reason the feature needed thinking about. One trip to another city is a
recording, not a route, and if it got a vote on the bounding box then everything
you actually run would be squashed into a corner of the picture by a thing you
did once. So the box starts as the routes' box, padded, and is *grown* — never
cropped, never stretched. A sample outside it is dropped rather than clamped,
because clamping piles every distant point onto the border cells and draws a hot
frame around a map with nothing at its edges.

(A.22 stretched that box until it held the recordings too, because one that
stops at the routes cuts real tracks off at an invisible line. A.23 replaced
that compromise with two boxes: this one, unchanged and 16:9, for the card, and
a second covering everything inside the radius for the view that pans. Both
paragraphs below describe the card; the full-screen extent is `Extent.EVERYTHING`
and leaves nothing out at all.)

**The radius filter is about believing coordinates.** Any route or recording
with a point more than `HEATMAP_MAX_RADIUS_M` (100 km, configurable) from the
median start of all routes is left out of the drawing. A recording is in or out
whole: a track with one fix in the Gulf of Guinea is a track whose every point
is suspect, and half a recording drawn is worse than none. What it does **not**
do is delete anything — nothing leaves the database, no route stops existing,
and the caption under the picture says how many were left out so that a missing
route is never silently missing.

That median needed care, and a test caught it. It is a true order statistic and
deliberately does *not* average the two middle values the way a textbook median
does. With four routes of which two start abroad, the mean of the two middles
sits halfway to the outliers, at a place no route has ever been — and the radius
filter then rejects every route including the real ones. Taking the lower of the
two middles keeps the breakdown point at the 50% a median is supposed to have,
and guarantees the centre is somewhere a route actually started. A pleasant
consequence: the centre always lies on some route, so that route can never be
excluded by its distance from itself.

**Colour.** Density is a magnitude, so the ramp is one hue stepped light to dark
— blue, six steps of one scale — and never a rainbow, which encodes an order
nobody can read back. The scale is logarithmic: a commute ridden two hundred
times and a lane crossed twice differ by two orders of magnitude, and linearly
everything but the commute would be indistinguishable from empty. Dark mode is
its own ramp rather than an inversion, because on a dark ground "near zero" has
to recede *towards* the surface, so it runs dark to light. Empty cells are fully
transparent: no data is not the same as no activity.

**The route lines are warm, thin, and uncased**, and the first version was none
of those. It drew them as ink lines with a surface-coloured casing underneath —
the standard cartographic treatment — and rendering it showed why that is wrong
here: recordings follow routes, that being what makes them routes, so the lines
sat exactly on top of their own heat and erased it. The picture came out as
three black shapes with a faint blue fringe. A warm hue against the blue ramp
separates the two layers at every step of the scale without covering anything,
and needs no casing. The stroke is a real 1.2 dp rather than raw pixels, so it
is the same weight on every screen instead of a hairline on a dense one.

That was found by rendering it. `Heatmap.build` is pure JVM, so the raster and
the overlay were rasterised to PNG off-device, on synthetic data with a commute
ridden sixty times, a loop ridden twelve, a ride done twice, some wanderings
that never became routes, one holiday far outside the extent and one glitched
fix — and looked at. The wanderings are the case worth checking: they are heat
with no line on them, and they are the only place in the app where they appear
at all.

### A.15 A takeout is a better backfill than a pile of TCX files

§7 and US-9 assume the cold start is solved by TCX: export each workout from
Google Health, pick the files in the app, let the clusterer have them. That
works and it does not scale. Google hands out one TCX per workout behind a
per-activity URL, which for a year of commuting is several hundred downloads
that need an authenticated session; the export that requires no clicking is
Takeout, and Takeout contains no TCX at all. What it contains is a `tcxLink`
column pointing back at those same URLs.

So the backfill reads the takeout directly. `:core:run --args="takeout …"` turns
the ZIP — or the several ZIPs a large export is split into, which are separate
complete archives rather than one spanned one — into a backup archive in the
format of A.10, and the phone restores it through the path that already exists.
Three consequences worth recording.

**The archive format moved to `:core`.** `Archive` and `ArchiveCodec` were in
`:phone` and neither had ever imported anything from `android.*`; only
`ArchiveRepository`, which maps Room rows to and from them, actually needs the
platform. A command-line tool cannot depend on an Android module, and defining
the backup format a second time inside the importer is how the two definitions
start to disagree. Moving the pair down also moved their tests to where nothing
about the Android toolchain can get between them and a laptop.

**The importer builds sessions, never routes.** The archive it writes has an
empty route table. Clustering happens on the phone at the end of the restore,
by the same `RouteClusterer` and the same thresholds as a route learned live, so
there is exactly one implementation of what counts as a route. A converter that
shipped its own clustering would be a second one to keep in step.

**What the export actually holds**, which took reading it to find out. The
authoritative list of workouts is `UserExercises_<date>.csv`: one row each, UTC
start and end, and — the thing worth the whole appendix — an explicit
`utc_offset` per exercise. The positions are elsewhere, in
`gps_location_<date>.csv`, at 1 Hz, in UTC, and not segmented by workout: the
import slices them by the exercise's window. The friendlier-looking
`exercise-0.json` beside them is a trap. It is paginated and capped — 237 rows
against the exercise table's 429 in the export this was built against — its
`hasGps` flag was false for fifteen days that do have position files, and its
`startTime` is `01/28/26 17:56:08` with no zone anywhere near it.

Three duplicate mechanisms rather than one, because there are three ways to get
the same workout twice:

| | caught by |
|---|---|
| the same takeout imported twice, or a later takeout that still contains it | session ids derived from Google's exercise id |
| a workout imported by hand as TCX and also present in the takeout | start-time proximity, `Track.likelyDuplicate` |
| a workout Hecate recorded, which the watch logged to Google Health on its own | the same |

The second and third cannot be caught by id — the two ends of the comparison
have unrelated ids and only the clock relates them — so the restore path now
applies `Track.likelyDuplicate` to any session whose source is an import. This
is a deliberate narrowing of A.10's "merge by id and nothing else": a backup of
the app's own recordings is still restored exactly, and only an imported session
can be refused for looking like something already present. Being wrong in the
cautious direction costs one workout out of a backfill of hundreds. Being wrong
the other way gives the clusterer two members for one journey and quietly
inflates every route's member count, which is the number the UI reports and the
number `MIN_MEMBERS` tests.

Not everything in the export can come in. Workouts with no position file are
dropped — Google keeps the exercise summary long after it has dropped the day's
positions, so the oldest history is rows without tracks. Skiing and tennis have
no `ExerciseType` here; they are refused rather than filed under the nearest
thing, because a ski descent clustered as a run is a route nobody can ever run,
and `--map` exists for when the user wants the track anyway. Auto-detected
activities are taken only when they carry positions and overlap nothing already
taken. Every one of these is reported by name and reason, which is the argument
for this running on a laptop rather than behind a button on the phone.

**Time zones, which were wrong before this and are now the one thing the export
is unambiguous about.** `TcxParser` stamped every session it read with
`tzOffsetMinutes = 0`. A TCX from Google Health is written in UTC and names no
zone, so the instants were right and the offset was a placeholder — and the
offset is what turns an instant back into the time of day it happened at. The
visible symptom was an imported summer workout displaying two hours early. The
invisible one was `RouteMatcher.contextKeyOf` bucketing it by the wrong hour, so
the context prior learned from a history shifted into the small hours.

The parser now believes an offset the file spells out, and otherwise resolves
one from a `ZoneId` — the platform default on a phone — *at the instant of the
workout*, so a January import and a July import differ by an hour as they
should. The takeout importer never needs the fallback: it uses the exercise's
own `utc_offset`, then the profile's zone, then Europe/Berlin. Rows written by
the old parser are repaired by migration v5, which is data-only and touches
only sessions that are both imported and sitting at exactly 0.

### A.16 Robolectric, bought by a migration that named the wrong column

§6.5 asks for JVM tests of the algorithms, an emulator for plumbing and a wrist
for the rest, and says nothing about the middle. The middle is where `:phone`
lives: Room, WorkManager, a `ContentResolver`. None of it is algorithmic, all of
it is ordinary, and until now none of it had a test — the position being that
Android-side code needs a device, so it gets lint and the compiler and a careful
reading.

That held until `MIGRATION_4_5` was written against `tz_offset_minutes`. The
column is `tz_offset`; `tzOffsetMinutes` is the Kotlin property. Room runs
pending migrations the first time the database is opened, so the consequence was
not a wrong number somewhere — it was an app that would not start, on a database
that already held the user's history. Nothing in the build had any opinion,
because raw SQL in a `Migration` is a string until something runs it, and the
only thing that runs it is a database at the old version.

A migration is the worst possible place for "it gets a careful reading" to be
the whole safety net, and it is also entirely reachable from a laptop: the old
version is fully described by the JSON Room already exports into
`phone/schemas/`. So `:phone` now has Robolectric, and four test classes:

| | |
|---|---|
| `MigrationTest` | each exported version built from its schema, migrated through the real list, rows read back |
| `SessionRepositoryTest` | ingest's two silent promises — idempotent on the id (§5.4), start-time duplicates refused (§7) |
| `ArchiveRepositoryTest` | A.10's round trip: export, restore into an empty database, names and priorities intact |
| `TcxImporterTest` | US-9 end to end, including the time zones A.15 fixed |

Three notes on how they are built, each a choice that could have gone otherwise.

**The migration tests do not use `MigrationTestHelper`.** Room's own helper reads
the exported schemas through the `AssetManager`, and Android unit tests do not
merge the test source set's assets — so the only way to satisfy it is to put
`phone/schemas/` on the *main* source set, which ships 60 KB of schema JSON
inside the APK to serve a test. `SchemaFixture` reads the same files off disk
instead. What is given up is per-migration schema validation; what is kept is
better, because the tests go through `Room.databaseBuilder` with the app's own
migration list, so Room validates the final schema itself *and* a migration that
was written but never registered fails.

**The archive tests use a `file://` Uri.** Everything on the app's side of that
Uri is exercised — the codec, the Room mapping both ways, the restore's
transactions and foreign-key ordering. Nothing about the Storage Access Framework
is, and §1.3 still says so.

**The workers are stubbed, not run.** Both repositories enqueue clustering as
their last act. `ClusterWorker` is a thin wrapper over `:core`'s clusterer, which
has its own tests, and `HealthConnectWorker` needs a Health Connect the JVM does
not have; a WorkManager that accepts an enqueue and runs nothing keeps the tests
about the thing they are about.

What this does not change: `:wear` has no tests, nothing is instrumented, and the
three defects OPEN-POINTS §3.2 lists — cross-device delivery, a vendor library's
validation, a JDK-versus-Android API difference — would all still have reached
the device. Robolectric moved the line between "found on a laptop" and "found on
a wrist". It did not remove it.

### A.17 A permission that no longer exists cost a whole run

A 44-minute run recorded nothing and synced nothing. The post-mortem is worth
the space because four separate design decisions each turned a small problem
into a silent one, and only the first was a bug.

**The bug.** `MainActivity` asked for `android.permission.BODY_SENSORS`. On
Wear OS 6, for an app targeting SDK 36, that is no longer a runtime permission
the platform tracks for the app at all — `dumpsys package` does not list it among
the app's runtime permissions, and requesting it is a silent no-op that leaves
the appop at `ignore`. The grant the platform wants is
`android.permission.health.READ_HEART_RATE`, which the manifest declared and
nothing ever requested. So heart rate had been permanently denied since the
watch app was last installed, and no dialog had ever appeared to say so.

**Why that cost GPS.** `ExerciseSource` warmed up with `{LOCATION,
HEART_RATE_BPM}` in one `WarmUpConfig`. Health Services rejects a configuration
as a whole when it names a data type the caller may not collect, so the refused
heart rate took location with it. `dumpsys location` on the watch keeps a
registration history and is unambiguous: Health Services registered with the GPS
provider repeatedly the evening the app was first installed, and not once during
the run. The receiver was never switched on. The user's report — "it never got a
GPS connection" — was exactly right and had nothing to do with the sky.

**Why nobody found out for 44 minutes.** `prepareExercise` failing went to
`Log.w` and no further, and `PREPARING` had no exit but a location fix. No
exercise was started, so no availability callback and no sample could ever
arrive to end the wait. The foreground service ran, the ongoing notification said
the app was recording, and the state machine sat in a state it could not leave.
A gate with no timeout is not a gate, it is a deadlock with a nice name.

Three changes, of which the middle one is the general lesson:

- The configuration is built from the permissions actually granted, so a missing
  grant costs that one data type. A workout with no heart rate is worth having.
- `PREPARING` has a 90 s deadline, after which the session starts regardless and
  location joins when it arrives, exactly as it does after a mid-run dropout.
  Waiting for a fix is an optimisation for the matcher, not a precondition.
- Every one of these now says something on the watch. A failure that reaches only
  logcat is a failure nobody finds until the run is over — and on this watch the
  main log ring is 64 KiB, which is about a minute of Wear OS chatter, so by the
  time anyone looks it is not in logcat either.

**And why the sync failed, which turned out to be the same story.** Stopping
wrote a session with a header, a footer and no samples, and offered it to the
phone. `SessionRepository.ingest` refuses anything under two samples, and
`IngestService` deliberately did not acknowledge a refusal — a good rule, meant
to stop a bug here throwing away a real recording. For a payload that can never
become storable it is a trap: the file stays on the watch, is re-offered after
every reboot, and the data item sits on the phone for ever. Three had
accumulated.

So a rejection now says whether it is permanent, and only a permanent one is
acknowledged. A session with nothing in it is dropped on the watch before it is
ever offered. And `DataLayerIngest.sweep` reads what the Data Layer is already
holding when the app starts, because `onDataChanged` was the only way in and an
event that never arrives left an item nothing would look at again. Running that
sweep against the real phone cleared all three stranded sessions in one launch,
and confirmed the diagnosis from the other end: each was refused with `zu wenige
Messpunkte`.

**What this says about the test strategy.** Nothing here was reachable from a
laptop. The permission is a platform fact about one OS version, Health Services
is a separate process with its own validation, and the deadlock needs a real
`prepareExercise` to fail. OPEN-POINTS §3.2 predicted exactly this class —
"anything that talks to `com.google.android.gms`, `androidx.health`, or
`javax.xml` should be assumed unverified until it has run on the device" — and
the prediction held. What the laptop *could* have done, and now does, is make the
failure loud: the recorder says what went wrong, and the sweep means a missed
delivery is recoverable instead of permanent.

### A.18 Two more exercise types, and the enums that made them safe to add

v1 recorded three types. It now records five: `HIKING` and `SKIING` joined
`RUNNING`, `WALKING` and `BIKING`.

**The rule for adding one.** A type has to exist on both sides of the round
trip — Health Services must be able to start a session of it on the watch, and
Health Connect must have an `EXERCISE_TYPE_*` for the phone to write it back as.
Both of these do: `HIKING` is 37 and `SKIING` is 61 in Health Connect, and both
are constants of `androidx.health.services.client.data.ExerciseType`. Health
Services knows finer distinctions that Health Connect cannot express —
`MOUNTAIN_BIKING`, `ALPINE_SKIING`, `CROSS_COUNTRY_SKIING` — and none of them is
used, because a type that collapses into another one on write-back leaves the
watch and Google Health disagreeing about what the workout was.

**What the compiler found, and what it could not.** Four `when` expressions over
the type are exhaustive and broke until each was handled: the Health Services
mapping, the Health Connect constant, the route-name word, and the watch's
picker label. Two lookups did not break, because they matched on the *name*:
`Format.exerciseType("RUNNING")` returned the raw string for anything else, and
`ActivityIcon` drew nothing. Both were silent, both would have shipped, and both
are now `when` expressions over the enum. That is the general shape of this
entry: every place the type had been a `String` is now the type itself.

**The three German words are three jobs.** `label` is what the user taps and
reads (*Fahrradfahren*), `sessionTitle` is the noun Health Connect shows in its
own list (*Radfahrt*), `routeWord` goes inside an auto-generated route name
(*Route 4 (Rad abends, 8,2 km)*). They live on the enum rather than in each
module's formatter, because the watch and the phone had been keeping their own
copies and had already drifted: walking was *Gehen* in one place and
*Spaziergang* in the other.

**No migration, and none was needed.** `exercise_type` and `source` have always
been `TEXT` holding the constant's name. Only the Kotlin type either side of them
changed, so the schema is untouched at v5 and every row an older build wrote
reads back unaltered — including the walks that were really hikes, which stay
walks. Nothing in the row says otherwise, and guessing would be worse than
leaving them.

**Unknown names are now an error rather than a run.** `ExerciseType.parse`
returns null instead of falling back, the Room converter throws naming the value
it could not read, and the backup archive carries both enums as enums, so it
fails to parse rather than mislabelling. The one situation that produces an
unknown name is a build that knew a type this one does not — a downgrade, or an
archive restored from a newer phone — and in every one of those the honest
outcome is a loud failure. The alternative is what the code used to do: file the
workout as a run, permanently, with nothing left to say it was ever anything
else.

**The import tables, and why they are ordered.** Both importers now share one
keyword table, `ActivityNames`, so a name the takeout understands cannot be a
name the TCX reader silently gets wrong. It is a list rather than a `when`
because the order is load bearing, and the containment runs the wrong way in
more places than one would guess: `Langlauf` ends in "lauf" and is not a run,
`Wanderung` contains "run" and is not a run either — that one was found by the
test and not by reading — `Skitourengehen` contains "geh" and is not a walk, and
`Mountainbiking` contains "mountain", which is why there is deliberately no bare
"mountain" keyword for hiking. Indoor variants (`Laufband`, `Indoor Cycling`,
`Ergometer`) and look-alikes (`Eislaufen`, `Wasserski`, `Rollschuhlaufen`) are
refused ahead of the table: they share every root the outdoor ones do and have
no route in them.

**And the TCX reader stopped guessing.** `exerciseTypeOf` used to end in
`else -> RUNNING`, so a ski tour exported as TCX imported as a run, silently,
and the clusterer got a route nobody can ever run. It returns null now, and a
file holding nothing this app records is rejected with the sport's own name in
the message.

**The watch offers them most-used first.** Five buttons is enough that a fixed
order costs a scroll before every ski tour, so the start screen sorts them by how
often each type has been recorded. The count comes from the route bundle — the
sum of `RoutePrior.totalCount` over the routes of that type — because the bundle
is the only history the watch has. That makes it an undercount rather than a
census: a recording that never matched a route belongs to no route and is
counted nowhere. Every type stays on the list whatever its count, since the
picker has to offer a first ski tour before there is a ski route to notice, and
the sort is stable, so ties and zeroes keep the enum's own order and a watch with
no bundle shows exactly the list it always showed. Read off the main thread and
keyed on the bundle version, so a bundle arriving while the screen is up
reorders it.

**What this does not fix.** A route is per type, so the same forest track walked
once and hiked once is two routes with separate histories — the clusterer treats
different types as infinitely far apart, and that is the intended behaviour, not
an oversight. And skiing has a caveat worth writing down before the first ski
day: `BACKTRACK_SLACK_M` is 10 m, because position along a route advancing is
what disambiguates loops, and a lift ride back up a piste is a long run
backwards along the descent just matched. Repeated laps of one piste are
unlikely to match the way a loop does.

### A.19 Five bugs the emulators found, and one that was never a bug

A pass over every screen on a Pixel Watch 4 and a Pixel 8 emulator, against a
real 65-workout history restored from a takeout. The emulators cannot test what
§6.5 already says they cannot — no pairing, so no Data Layer, no Health Connect,
and the Wear synthetic provider emits one fixed route, so no matcher. Everything
else is exactly the app.

**Re-clustering did not finish.** Restoring 65 sessions and waiting: thirteen
minutes of pegged CPU before the first route appeared. `CoverageMetric.distance`
calls `fracWithin`, which is indexed, and then `sameDirection`, which was not:
it called `Polyline.projectAll` once per point of A, and that scans every
segment of B. Its own KDoc said "O(n); used at candidate admission and by the
clustering metric" — accurate, and the cost of calling it 1700 times per pair
over 2000 pairs is 12 billion segment projections. `sameDirection` now goes
through the same bucket index `fracWithin` uses. Same 4 routes, same heatmap
down to the byte, 16 seconds instead of 13 minutes; re-clustering by hand went
from minutes to 6 seconds. `CoverageMetricTest` now checks the indexed verdict
against a brute-force projection over five track shapes, because an optimisation
nobody can see the result of is an optimisation that will be quietly broken.

**A paused workout counted the pause as active time.** Health Services reports
the active duration as a checkpoint — a value at a stated instant — and the
recorder added the time since, unconditionally. While paused the checkpoint
stops advancing and the wall clock does not, so the figure kept climbing: on the
watch the timer ran while the distance sat still. That number is not only shown.
It is written into every sample and into the footer, so a ride paused ten
minutes at a level crossing reached the phone claiming ten more minutes of
effort than it had, and the profile, the splits and "am I faster than usual"
are all derived from it. The extrapolation now happens only in `ACTIVE`.

**The splits table contradicted itself.** One kilometre of the commute showed a
median of 22,9 km/h against a best of 22,6. The median came from the route
profile and best and worst from the members' own crossings — two populations,
two methods, and nothing holding them together. The profile's number is a
bin-wise median of *cumulative* times, so differencing two of them is not the
median of anybody's kilometre, and it is taken over every member while best and
worst are taken over the subset whose samples project onto the geometry. All
three columns now come from the members, so worst ≤ median ≤ best holds by
construction. The profile stays what the matcher compares against live, which is
a per-position model and the right thing there.

**The watch claimed it had no routes.** The start screen read the bundle version
out of the recorder state, which only ever learns it when a bundle *arrives*. So
every launch after the app had been closed said "Keine Routen" — on a watch
holding a perfectly good bundle, which the same screen had just loaded to order
the buttons by. It reads the version off that bundle now.

**A failed import said only that it had failed.** `TcxImporter.Report` carries a
reason per file and the status line dropped it, so "1 fehlgeschlagen" was the
whole story. That matters more since A.18: a sport this app does not record is
now refused rather than quietly filed as a run, and the message is the only sign
a ski tour did not come in. The first reason is now appended, with a count of
the rest.

**And the altitude that was not a bug, until it was.** The synthetic provider
sends `Double.MAX_VALUE` for altitude. `LocationData.ALTITUDE_UNAVAILABLE` is
documented as NaN and the recorder tested `isFinite()`, which is correct against
the contract and useless against this: 1.8e308 is finite. Nothing reads altitude
yet — which is the reason to reject it now rather than later, because it was
landing in the database and in every backup, and would have been waiting there
for the first feature that did. A plausibility bound, as for heart rate.

**What looked wrong and was not.** Two synthetic TCX imports near Hanau were
left out of the heatmap; the real routes are 150 km away near Nuremberg and the
radius is 100 km, so that is the feature working. The average-speed field on the
watch showed "–" throughout a recording; it is the ahead/behind-against-median
field, correctly empty without a route. And the recordings list opened partway
down its own history, which is a remembered scroll position and not an ordering
fault — the newest recording is at the top where it belongs.

### A.20 Altitude profiles, and what a number for a hill is worth

Both detail screens now draw height against distance under the track, with the
climb, the drop and the band it all happened in.

**Where each profile comes from.** A recording's is its own samples: altitude
against distance travelled, drawn as itself. A route's is the per-bin median
altitude over its members, computed beside the median time that was already
there and stored in a new `route_profile.median_alt_m` (schema v6). The median
is the point — altitude is the noisiest thing a watch reports, and the ground
under a route is the one quantity where averaging over twenty rides is exactly
right. It is deliberately not taken from the medoid alone, which is where the
*geometry* comes from: a shape has to belong to one recording to stay a shape,
an elevation does not.

**Ascent is not the sum of the rises.** At 1 Hz, a barometer wandering by a
couple of metres adds up to hundreds of metres of imaginary climbing over an
hour of flat ground — the test builds exactly that and the naive sum reads over
a kilometre of ascent on a road that never leaves 500 m. The remedy is the
standard one: an anchor, and a move that only counts once it has committed by
more than `ELEVATION_THRESHOLD_M`. 5 m, chosen for the barometric altimeter a
Pixel Watch 4 actually has rather than for bare GNSS altitude, which is far
worse and which nothing here is fed.

The price is stated rather than hidden: ground gentler than the threshold reads
as flat, so a long drag gaining 4 m per kilometre counts as nothing. That is why
the band — "257–308 m" — sits under the totals. It comes from the raw minimum
and maximum and so sees everything the threshold discards, and it answers the
question the totals cannot: whether "+240 m" was a dyke or an alp.

**What the numbers say on real data.** A ski day imported from the takeout draws
as twenty sawtooth teeth and totals +9179 m against −9163 m between 1470 m and
2382 m, netting +16 m — you finish where the first lift started. The commute is
quieter and more telling: the two morning routes descend (+0/−46 m and +11/−43 m)
and the two evening ones climb (+31/−30 m and +37/−33 m), which is the same hill
read twice from opposite ends and is about as good a check on the arithmetic as
real data offers.

**Thinning keeps the peaks.** An hour is 3600 samples and the chart is a few
hundred pixels, so the series is bucketed — and each bucket keeps its highest
and lowest point rather than an average. Averaging is precisely what turns that
ski day into a smooth hump: the shape of the teeth is the entire content of the
picture.

**The one dishonest thing, said out loud.** The chart's axes are not to the same
scale, unlike the map above it, which is careful never to distort a route's
shape. A 17 km ride climbing 120 m drawn honestly is a horizontal line. Every
elevation profile ever printed is exaggerated vertically and this one is too;
the height band beside it is what stops that being a lie rather than a
convention.

**The altimeter settles, and it does it in the data.** Every profile opened with
a spike: the first samples of a recording are routinely 10–25 m out, in either
direction, before the barometer agrees with itself — 315 m where the next nine
samples say 296, or 298, 275, 291 before settling on 271. Isolated samples go
astray later too. Both are impulses, which is what a rolling median is for, and
the filter runs on the way out of storage rather than into it: the session file
and the database keep what the watch actually said.

A median is the right tool here and not merely a convenient one, because on a
monotonic run it is *exactly* the identity — the median of a ramp is its middle
value — so no climb and no ski descent is touched. Measured over the routes, the
mean change per bin is 0.25 m and the only bins that move by more than 4 m are
the first and last: it removes the spike and does nothing else. The cost is
confined to the first and last half-window, where there is no symmetric context
and a sample follows its neighbourhood instead of itself, and to a sharp apex,
which gets clipped by about 3 m in 300.

**The window is 9, and the obvious measurement argues for more.** Ten recordings
of one commute are ten recordings of the same ground, so the spread of their
ascent totals is pure noise — and filtering cuts it from 20 m to 9 m, with wider
windows cutting further. Eight recordings of the evening route went from
51–97 m raw to 42–57 m filtered, a standard deviation of 17.1 down to 4.2. But
the *mean* never converges: it falls from 61 m to 32 to 17 to 2 as the window
grows to 101, with no knee. A filter wide enough to flatten the disagreement is
wide enough to flatten the hill, and no amount of that metric will say where to
stop, because a flat line scores perfectly on it. So the window is the smallest
that removes the impulses actually observed, and the wish behind a wider one —
less noise in a single recording's total — is answered by a different mechanism
that already exists: the route profile takes its median across members, which is
why a route reads +0 m on ground where individual recordings of it still claim
32.

**Absent rather than empty.** A recording from a watch that reported no altitude,
and a route last clustered before v6, draw no chart and no rows at all. A flat
line at zero would be a claim about the ground.

**And one test that had been quietly excused.** `MigrationTest` kept its own copy
of the migration list, so adding v6 failed it with "a migration from 4 to 6 was
required but not found" — a true statement about the test and nothing about the
app. Both now use `HecateDatabase.MIGRATIONS`. A.16 exists because a migration
nobody replays is a crash on a real phone; a test that replays a different list
from the app's is the same hazard wearing a passing badge.

### A.21 A filter, a louder mark, a heatmap worth looking at, and one table fewer

Six changes, all from using the phone app against a real history. Four of them
are about *reading* a screen rather than about what is on it.

**The recordings list has an activity filter, offering only activities that
exist.** A chip row above the list, from `ExerciseType.entries` intersected with
the history — a chip for a sport the user has never recorded can do exactly one
thing, empty the list, and reads as a promise the app cannot keep. The row hides
itself entirely below two kinds of recording, because a filter with one option
and an "Alle" beside it says the same thing twice.

The filter lives in the view model rather than in the screen, so that a trip
into a recording and back does not silently drop it — the list is restored to
where it was scrolled (A.13), and coming back to that scroll position in a
differently filtered list is worse than not restoring it at all. It is *derived*
rather than stored: what the user picked, unless that activity has since stopped
occurring. Delete the last ski tour while filtered to skiing and the alternative
is an empty list, no chip selected, and — once one activity is left and the row
hides — no way back out of a state with no visible cause. Marking stays over the
whole history: a recording is fast against the others on its route, and
narrowing what is on screen does not change which those are.

**The standout mark is rarer and louder.** The threshold moves from the 75th
percentile to the 85th, which is roughly the best in seven rather than the best
in four; on a commute ridden sixty times, fifteen emphasised rows had stopped
being emphasis. Rarer needs louder, and the two are one change rather than two:
a mark on a quarter of the rows is found by comparing two rows, while a mark on
one row in seven has to be findable by scanning, and a shade of colour with a
slightly heavier weight is not. So the figure now carries an upward arrow —
`res/drawable/ic_arrow_up.xml`, one more Material Symbol beside the activity
icons, for the reason A.11 gives: `Icons.Filled` has no upward arrow and
`material-icons-extended` would ship every icon that is not this one. The
constant is `Standouts.PERCENTILE`; §3.7 of the open points was the prediction
that a quarter would be too many.

**The heatmap is a heatmap now.** It looked like a spreadsheet with the numbers
hidden, and three separate things were responsible.

- **A cell is now a fixed size on the ground.** The raster was 192×108 whatever
  it covered, so a cell was 40 m across a town and 400 m across a county: how
  much detail the picture had depended on where else the user had been. It is
  now `HEATMAP_CELL_M` = 15 m always, and the raster is as many cells as the
  extent needs — a few hundred thousand for a town, and capped at
  `HEATMAP_MAX_CELLS` = 3 million, past which the cell *doubles* until it fits.
  Doubling rather than scaling to fit, so the cell stays a fixed physical size
  per zoom level and two rasters of the same ground line up. The cap is reached
  at an extent of about 35 × 20 km, and the extent can never exceed the 100 km
  radius filter, so the worst case is bounded and known.
- **The raster counts passes, not samples.** A sample is a second of somebody's
  life, so counting samples measured how *slowly* the ground was covered: five
  minutes waiting at a level crossing outshouted a route ridden sixty times, in
  one cell. Each recording now contributes at most one count per cell it passes
  through, and the segment between consecutive samples is drawn rather than its
  endpoints — which is also what stops a 15 m cell going empty because nobody's
  receiver happened to fire inside it. A gap beyond
  `MAX_INTERPOLATION_GAP_M` is a dropout and is not bridged, the same rule and
  the same constant resampling a track already uses.
- **A Gaussian blur before the colour ramp.** This is what turns counts into
  density, and it is a blur rather than a coarser grid because the two are not
  the same thing: a coarse grid quantises, a blur spreads. σ is 1.5 cells, about
  22 m of ground — one GNSS error — so a drawn track is as wide as the
  uncertainty about where it was. Wider merges streets a block apart. It is
  applied at drawing time rather than stored, so the counts stay what was
  measured and changing σ costs a redraw rather than a re-clustering of the
  whole history. The ramp normalises against the *blurred* peak, since a blur
  lowers the peak it spreads.

Two consequences followed. The blob is run-length encoded: the raster is now
millions of cells, almost all of them zero, and gzip compresses those perfectly
well but a decoder does not survive four million JSON integers. The reader still
accepts the old shape — every cell as an integer, and no cell size, which is its
extent over its width — because the alternative is a routes screen with no
picture on it until the next clustering, over a file this code can plainly
understand. And the work moved off the main thread — the stored raster is decoded with `flowOn`, and the
bitmap is built in a `produceState` on `Dispatchers.Default`; the blur alone is
about 300 ms on a laptop at the cap.

**The heatmap opens full screen, and drags.** The card is a thumbnail of a
raster far finer than a card can show. Tapping it opens a `Dialog` with the
platform width rule off — covering the system bars, rather than becoming a
fourth entry in the screen enum, which would put a picture into the back stack
beside the panels and the details. Pinch to zoom to 8×, drag to pan, and the pan
is clamped against the *drawn* picture rather than the window: the picture is
16:9 inside a taller screen, and clamping against the window lets it be dragged
off the edge and lost. Both views share one bitmap.

**Priority is −2..2 in a single button group.** Only the order of these numbers
has ever mattered — nothing multiplies by a priority — so ten values asked for a
precision the mechanism cannot use, and choosing between a 6 and a 7 for a route
is a question with no answer. Five values, centred on zero, each of which is a
sentence a person can mean: ganz hinten, eher nicht, keine Meinung, eher schon,
ganz vorn. A neutral value of 5 out of 10 also invited the user to spend the
other nine; a neutral value of 0 does not. (Those five sentences were printed
above the picker until A.22, which took them off: a centred row of five already
reads as less to more.)

Schema v7 rescales it, and **resets every route to 0** rather than mapping the
old value on. The old default was 5, so all but a handful of routes hold a
number nobody chose, and nothing distinguishes those from a deliberate 5 —
mapping would turn a value nobody picked into a value nobody picked on a
different scale. The table is rebuilt rather than updated, because the column
default changes and SQLite has no `ALTER COLUMN`: the twelve-step rebuild from
the SQLite manual, minus the steps for indices and triggers that `route` does
not have. Foreign keys are off while Room runs a migration, so dropping the
parent does not cascade into `route_point`, `route_profile` and `route_member`,
and the rename does not rewrite their `REFERENCES` clauses. Every clause of that
sentence fails silently, so `MigrationTest` now puts rows in all three children,
migrates, reads them back, and then deletes the route to prove the cascade still
works. A restore clamps what it reads, since an archive written before v7
carries 1..10 — a 9 is not a crash, it is a route whose priority the picker has
no button for and therefore cannot be changed.

**Both drawings are coloured by effort.** The track on both detail screens, and
the altitude profile under it, are drawn in four bands: Leicht, Mittel, Intensiv
and Höchstleistung, at 70 / 80 / 90 % of the user's peak heart rate. The
boundaries are the conventional ones and are fractions of the peak rather than
of the heart-rate reserve, which would need a resting rate this app has never
measured. Four bands because this exists to be seen, and a reader tells four
colours apart at a glance and seven never; blue → green → orange → red because
that is the order every heart-rate display in the sport uses and it runs cool to
hot, which is the one ordering of hues a person infers without a legend. The
colours are fixed rather than scheme roles, for the reason the start and end
markers are: a wallpaper must not be able to turn a maximal effort into the
colour of an easy day.

The peak rate is a setting, defaulting to 180 bpm. Not `220 − age`, which is the
best known number in amateur sport and one of the worst — its spread across
people is 10-12 bpm, so for one person in three it is out by more than a whole
band. It lives in the `meta` table, which the backup archive already carries: a
setting stored anywhere else is a setting that does not survive a restore.
Changing it recolours every chart already on screen, because the screens read it
as a flow rather than at load.

Where the heart rate comes from differs by screen and is worth writing down. A
recording's is its own samples, carried along with the position and with the
altitude profile — `Elevation.Point` gained an `hrBpm` for exactly this, because
a parallel list would have to be thinned in lock-step with the points by every
caller, and the moment it is not, the colours slide along the line. A route's is
the per-bin median over its members, which `route_profile.median_hr` has held
since the beginning; a route point's distance along the route says which bin it
is in, so it is a lookup rather than an interpolation. Consecutive segments in
the same band are drawn as one path — two hours is seven thousand segments and,
ordinarily, a handful of colour runs.

"Ordinarily" is doing work there, and it is why the rate is passed through a
15 s rolling median first. An effort a few beats under a boundary reaches over
it every few seconds, and without smoothing the line would alternate between two
colours every few pixels: noise rendered at the resolution of a decision, and
also seven thousand paths where there should be six. A median rather than a mean
because a real effort ramping up is a monotonic run and a median is the identity
on those — the same property, and now literally the same function
(`Series.rollingMedian`), that the altitude de-spiking in A.20 rests on. What it
cannot do is settle a rate balanced exactly on a boundary: that case has no band,
and no filter can invent it one. The profile draws its band twice: as the fill
under the curve, which is what makes a profile read as ground, and as the line
itself, which is exactly where the data is. (It was drawn a third time as a
full-height band behind the chart; A.22 says what a real profile did to that
argument.) A stretch with no reading gets no band at all rather than a grey one,
so absence looks like absence rather than like a fifth band, and the legend
appears only where there was something to colour.

**The kilometre splits are gone from the route screen.** Removed on request, and
with them the reconstruction behind them: every member of the route was
projected onto the route geometry to find its crossings of each kilometre mark,
which was the most expensive thing the route detail screen did. The route
profile still holds the per-bin median times — it is what the matcher compares
against live (§5.7) — so nothing the watch does changed. `Format.speedOverKm`
went with the table; A.19 is the record of the day that table spent contradicting
itself.

### A.22 Six notes from the first hour with a real history on a screen

A.21 was written against synthetic data and rasterised PNGs. This is what the
same six screens looked like with 66 real recordings behind them on a phone
emulator, and it is mostly about the difference between *correct* and *legible*.

**The activity filter is ordered by frequency, commonest first.** It was in the
enum's order, which is the order the five sports were added to the app — so on a
history of 46 rides, 13 walks, 6 ski tours and one walk to the shops, the first
chip after "Alle" was whichever sport happened to come first in a list in the
source. The row now counts the history and sorts by it, ties keeping the enum's
order so that two equally common activities do not swap places on every ingest.
Nothing else about the filter changed; the derivation that hides an activity
which has stopped occurring is still what decides *which* chips exist.

**A matched route's name is cut to twenty characters.** The pill on a recording
row is as wide as what is in it, and routes are named automatically — "Route 1
(Rad früh, 16,9 km)" — so the pill grew until it pushed the distance off the
row. `Format.routeName` cuts at 20 and adds one ellipsis character, trimming a
trailing space first so the cut does not read as a typo. Twenty is about as much
as anyone reads to recognise a route they named themselves, and the whole name
is one tap away on a screen with a line to spare for it.

**The heatmap was cut off, and it was cut off by its own extent.** (A.23 keeps
the diagnosis and changes the cure: the card went back to the routes' own box
and the full-screen view got an extent of its own. What follows is why the
single extent was grown, which is still the reason there are now two.) The box came
from the routes and stopped there (A.14), which is right about *where* the
picture is and wrong about *how big*: on the history above, a fifth of all
sample points lay outside it and were dropped, so tracks ended in mid-air at an
invisible line. The rule is now that the routes anchor the extent and the
recordings may extend it, out to `HEATMAP_EXTENT_REACH` — one route-span — past
them. Beyond that a recording gets no vote at all, not even the edge it would
have been clamped to: the 17 km-away ride in that history is a place, not a
neighbourhood, and stretching the picture to reach it would squash the ground
the picture is about. What that ride gets instead is a mention: the caption now
says how many recordings are inside the radius but outside the picture, because
a recording that is not drawn looks exactly like one that was never made.

The extent also stopped being forced to 16:9. (A.23 put that back for the card
and dropped it for the full picture — see there.) Growing a 4 × 14 km corridor to
one fixed shape made it 25 km wide, nine tenths surface, and then cost a
doubling of the cell size to stay under the cap — so the fixed ratio became a
range, `HEATMAP_MIN_ASPECT`..`HEATMAP_MAX_ASPECT`, and inside it the picture is
whatever shape the ground is. The bounds are what the two views can show:
squarer than 1:1 is a card taller than it is wide, which pushes the routes list
off its own screen, and wider than 16:9 is a letterbox. The same history that
came out 29 × 16 km at 16:9 is 20 × 20 km now, covering more ground in fewer
cells, still at 15 m.

**The low end of the colour ramp was invisible.** Nearly everything on a heatmap
is down there — the lane crossed twice is most of the picture by area, and the
commute is a handful of cells — and the lowest step was 27 % alpha of a very
pale blue on a near-white surface. Two things were wrong. The step itself is now
a shade darker and half again as opaque, in both ramps, with the top of each
ramp taken down by as much so the range between a lane and a commute is no
smaller. And `LOW_FADE`, the share of the scale over which the lowest step fades
in from nothing, came down from 0.18 to 0.06: it exists to keep the outermost
ring of the blur from drawing a hard edge, and at 0.18 it was also halving the
opacity of every lane on the picture. A cell crossed once lands at about t = 0.10
on a history whose busiest cell has been crossed forty times, which is exactly
the range that was being faded out.

**The effort band on the altitude profile is under the line only.** It used to
be drawn a third time as a 10 % band running the full height of the chart, on
the argument that a hard stretch on flat ground leaves the curve too little to
colour. Seen on a real profile, that argument loses: a band above the line
colours sky, the sky is the one part of the picture where nobody was, and the
eye — which reads a tint as the area under something — finds the curve in the
middle of its own colour and stops reading the chart as ground. The fill under
the curve went from 34 % to 42 % at its top to carry the band on its own, which
is where the reader is looking anyway.

**The priority picker lost its captions.** "0 · keine Meinung", "+1 · eher
schon" — five buttons on a centred scale do not need naming twice, since the row
already reads left to right as less to more and the line under it says what the
scale decides. The second sentence of that line went too: it said that a tie
leaves the watch on "Suche…", which is a description of what happens when the
user does nothing, written above a control they are looking at *because* they
are about to do something. The words themselves are not lost — §A.4b and the
README still say what the five positions mean, which is where a sentence about
the mechanism belongs.

### A.23 Two pictures of the same ground, and one chart drawn four times

Three changes, all of them from the same session with the app as A.22: one
correction to what A.22 did, one gesture that was wrong in a way arithmetic can
state exactly, and the two charts the screens had been missing.

**The heatmap is two rasters now, not one.** A.22 grew the single extent until it
held the recordings as well as the routes, and that was the wrong shape of answer
— it made the card worse to make the full-screen view better. The two views are
asking different questions. The card above the routes list is about the routes:
their own box, grown to 16:9 because it is a card in a scrolling list, and a
recording that wandered elsewhere colours what it crosses without getting a vote
on where the box is. The full-screen view is about where you have been, so its
box holds every recording the radius filter kept, in whatever shape that ground
happens to be — it pans and zooms, so nothing has to be left out to make it fit
and no ratio has to be imposed to make it a card.

`Heatmap.build` therefore takes a `Heatmap.Extent`, and `ClusterWorker` runs it
twice over the same coordinates, storing the two rasters as two rows of the
`heatmap` table. Two rows rather than two columns or two tables because the
schema has carried an id since v4 and never used it for anything: no migration,
and the routes screen goes on inflating only the small one. The big one is read
when the dialog opens and dropped when it closes, which is the other half of the
same decision — it is the larger raster of the two and there is no reason for it
to sit in memory behind a list.

The caption differs between them and that is the point: on the card it can say
"1 außerhalb des Ausschnitts", and on the full picture that number is zero by
construction. An install that has not re-clustered since the second row existed
has only the first, and the dialog falls back to it rather than waiting for a
raster nobody has computed: the smaller ground is what the user was already
looking at, and the next clustering replaces it. `HEATMAP_EXTENT_REACH` is gone, along with the aspect *range* A.22
introduced; `HEATMAP_ASPECT` is back to being one ratio for the one picture that
needs one.

**Pinch zoom now has a place.** It scaled about the picture's own centre however
far from the centre the fingers were — "it seems to zoom to some arbitrary
point", and arbitrary is precise: the centroid had nothing to do with it. A
point p is drawn at s·p + t; for whatever is under the centroid c to stay under
it while s becomes k·s, the translation has to become

    t' = c − k·(c − t) = c·(1 − k) + k·t

with c measured from the centre the picture scales about. That is the whole fix.
The clamp stays as it was — the picture may travel only as far as its own edge —
and on an axis the picture does not yet fill there is nowhere to go, so the
anchoring is given up there rather than dragging empty surface into view. Where
it matters it holds exactly, and provably: the anchoring asks for |c|·(s − 1) of
offset, the clamp allows (drawn·s − window)/2, and for a centroid inside a window
the picture covers, the first is the smaller.

It is `Viewport.pinched`, a pure function, for one reason: a wrong zoom centre is
not a crash and not even a wrong picture. It is a gesture that works, magnifies
the right amount, and magnifies the wrong place — invisible to anything that
checks a screenshot, and exactly what a test can pin. Six of them do.

**Speed and heart rate against time, on both detail screens.** Four charts exist
now, and they are one composable: `BandedChart`, with `ElevationView`,
`SpeedView` and `HeartRateView` as three-line adapters saying what their axes
mean. The point of that arrangement is not brevity — it is that four charts
maintained separately do not stay the same shape for a month, and "the same
shape" here is specific: the same vertical exaggeration, the same four intensity
colours in the same bands, the same fill under the line and nothing above it
(A.22), the same thinning, the same treatment of a gap. The thinning moved to
`Series.thin`, generic over the point type, for the same reason: it is the
function that decides whether a peak survives being drawn, and two copies of it
would eventually disagree about that.

Both new charts are coloured by heart rate rather than by their own value, and on
the speed chart that is the whole reason to have it: 30 km/h in blue is a
descent, 30 km/h in orange is work, and no single-colour speed trace says which.
On the heart-rate chart colouring by the value is not a tautology either — it is
what puts the four zone boundaries onto the trace, so the chart answers "how long
was I in the orange" with no axis to read.

The numbers come from `Timeline`, in `:core` with the rest of the arithmetic. A
recording's speed is measured over the travelled distance between consecutive
samples, with a dropout left as a gap rather than averaged across — a straight
line through unknown terrain is not a path anybody took — and then passed through
the same rolling median as everything else, because speed is the one quantity on
these screens that is a *derivative* and 15 m of GNSS noise in one second is
54 km/h of sprint that never happened. The axis is active time, so a café stop
closes up instead of spending a fifth of the chart saying nothing happened.

A route's speed comes from the per-bin median times its profile has held since
the first schema, and it needed one piece of care that only real data showed. The
medians are taken per bin independently, so the sequence is monotone on average
and not in fact: two adjacent bins came out a hundredth of a second apart, which
over a ten metre bin is four thousand kilometres an hour, and the first real
route drew exactly that — one spike, and a flat line underneath it. The
difference is therefore taken across `ROUTE_SPEED_WINDOW_BINS` = 15 bins (150 m,
tens of seconds of riding) rather than between neighbours, and the result goes
through the rolling median as well, which removes what a window cannot: a single
large jump spread thinly across every window containing it. 4418 km/h became
40.8, which is a bicycle going downhill.

Neither the range nor the average is written under these charts by accident.
Both screens already carry an average of their own — a recording's distance over
its active time, a route's median duration — and a second "Ø", computed
differently and disagreeing by a tenth, would be worse than no number at all. So
the line under each chart is the range, which is the fact the chart's own
exaggerated axis is hiding.

### A.24 The watch was writing a clock that stood still

A.23's speed chart was right on every takeout import and nonsense on everything
the watch had recorded, which is the most useful shape a bug can have: the two
differ in exactly one thing, and it was not the chart.

**`Sample.activeMs` from the watch is not a series.** Health Services reports the
active duration as a *checkpoint* — a value that was true at a stated instant —
and it does not arrive with every batch. The recorder stamped whatever it last
had onto each sample, so on two real rides four fifths of all sample intervals
contained no time at all, and after an auto-pause the number came back *smaller*
than before: three backward steps in one ride, one of them by 34 minutes. A
chart of distance over that is a chart of distance over zero, which is either no
speed or an enormous one, and the ride drew both.

Nothing had noticed because everything that read it read it as a **total**, and
the total is right: the footer is written once at the end of a session from a
checkpoint that is current by then, and it agrees with the wall clock to a
quarter of a percent. What reads it as a series is new — A.23's speed chart, and
before it the per-bin median times of a route profile, which is how "am I faster
than usual" is answered.

The clock is therefore rebuilt from the one field on a sample that cannot lie:
`tOffsetMs`, taken from a monotonic clock. Time between consecutive samples
counts unless either end is paused, or the hole between them is longer than
`MAX_INTERPOLATION_GAP_MS` — an auto-pause writes no samples at all and appears
only as such a hole, a receiver dropout appears the same way, and the app cannot
tell them apart. Counting it would invent active seconds for a workout that may
have been standing still; not counting it under-counts a genuine dropout by its
own length, which is the smaller and quieter error, and it is what every other
part of this app already does with a hole that size.

`ActiveClock.repair` is not a guess, and it did not have to be: run over the 62
sessions of a real backup it reproduces the stored per-sample clock of **every
takeout import exactly**, to the millisecond, and lands within a quarter of a
percent of the footer's own total on both watch recordings — where the last
sample's own figure was out by a factor of four. On those two rides the speed
series went from 429 usable points out of 2686, spanning 1.2 to 34.8 km/h, to
2684 points spanning 9.4 to 29.4, against a recorded average of 22.5.

It is applied on the way out, every time, exactly as the altimeter de-spiking of
A.20 is: what the watch said stays in the database and in every backup, so this
can be changed later without a migration and without anyone having to trust that
it was right the first time. `RecordedSession.timedSamples` is where it happens,
and `Track.of` reads that rather than the raw samples — which also puts the route
profiles back on a real clock at the next clustering, and with them the watch's
own ahead/behind.

The recorder was fixed too, so that new recordings do not need repairing: each
sample's active time is now its own offset less every pause recorded before it,
which is the same arithmetic done where the pauses are known first-hand. The
live display and the footer keep using Health Services' checkpoint, because that
is its authority and it is demonstrably right.

**Heart rate had the same shape of problem.** A sample is written per *position*,
and the rate stamped on it was whatever arrived in the same batch — so on those
two rides only 28 % and 36 % of samples carried one. The sensor had not stopped;
the batches simply do not line up. Read literally, that is a heart-rate chart of
forty disconnected bars and a track drawn in the no-reading colour for two
thirds of its length, about a ride whose effort was measured all the way along.

A reading is therefore carried forward onto later samples for `HR_HOLD_MS` = 30 s
and no longer — `Series.hold` on the phone, for recordings already made, and the
recorder itself for new ones. Thirty seconds is chosen from both sides: it covers
every ordinary hole between readings (the median is two to three seconds) with
room to spare, and a rate half a minute old is still the right band for the
effort, since the bands are ten percent of a peak wide. The sixteen or so holes
per ride that are longer than that are the strap genuinely losing contact, and
they stay holes — the chart draws the gap, which is the honest picture of a wrist
sensor on a bicycle.

**And one thing that is now visible and is not fixed here:** these recordings
carry no altitude at all, so a watch recording has no elevation profile while a
takeout import of the same route does. That is a separate question about what
Health Services offers on this watch, and §3.11 of the open points is where it
now waits.
