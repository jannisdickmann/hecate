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
- Exercise types: `RUNNING`, `WALKING`, `BIKING` (outdoor, GPS on).
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
  median/best/worst splits per kilometre, a plain polyline drawing of the route
  geometry.
- Session detail: the same table row plus which route it matched, coverage
  score, and where commitment occurred.

### US-9 — History backfill (must)

*As the user, I can seed the app with workouts I recorded before it existed so
routes are learned immediately rather than in three months.*

- Import TCX files (Google Health's per-workout export) from a file picker.
- Multi-select import; duplicates rejected by start-time proximity.
- Imported sessions participate in clustering identically to recorded ones and
  are flagged as imported (not re-written to Health Connect).

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

- Android Studio (current stable), JDK 17
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

Implemented: every route carries an integer priority, 1..10, settable on the
route detail screen and stored alongside the name. Where two or more routes are
*contenders* — live, being tracked, and within `COMMIT_SEPARATION_M` of the
nearest one — and exactly one of them outranks the rest, the matcher commits to
that one and reports remaining distance and ETA from it. A tie at the top
decides nothing and leaves the display searching, so the default (every route at
5) reproduces the previous behaviour exactly; the feature is opt-in by
construction rather than by a flag.

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
- `route.name_is_custom` — distinguishes an auto-generated name, which may be
  regenerated, from one the user chose, which may not.
- `route.priority` — see A.4b. Like the name, preserved across re-clustering.
- `sample.active_ms` — §5.5 stores only `t_offset_ms`. Splits, ahead/behind and
  the profile all need elapsed *active* time, which cannot be reconstructed from
  the wall-clock offset once a session contains pauses.
- `meta(key, value)` — holds the route bundle version counter.

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

- §6.5's on-wrist and emulator test layers cannot be exercised here. See
  OPEN-POINTS.md.
- §6.6's exit criteria are stated in terms of real workouts (ten consecutive
  recordings, sessions appearing in Google Health, behaviour on the fixture set
  "walked in reality"). The code implementing each milestone is complete; the
  criteria themselves are field measurements and remain open.
