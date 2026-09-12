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
- `session.ended_at_utc` — added in schema v2. §5.5 gives the session a start and
  an active duration, which are not enough to place its end: a session with
  pauses ends later than start plus active time. Health Connect rejects a write
  whose route points are not *strictly* inside the session — an exclusive bound —
  so the real stop time from the footer had to stop being discarded at ingest.
  Rows written before v2 keep NULL, because the value cannot be reconstructed.
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
- The per-kilometre splits table is headed `Kilometer-Splits (km/h)` and its
  Median / Beste / Schlechteste columns are speeds. "Beste" still means the
  fastest kilometre; the smallest time is the largest speed, so the column
  picks the same member it always did.

This is a presentation change and nothing more. The stored measurement is
unchanged — the route profile holds cumulative *time* per bin (§5.7), splits are
the seconds a kilometre took, and `deltaVsMedianS` is still seconds against the
median (A.2 of the open points has the sign convention). Speed is derived at the
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

`:phone` gains its first tests for this (§3.2 otherwise stands: still no
instrumented tests, no Robolectric). They are plain JVM tests of `ArchiveCodec`,
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

**Delete asks first, and says what goes.** The dialog names the recording's date,
distance and duration, and then what is destroyed with it — samples, match log,
recording history — and what is not: the Health Connect entry stays. Deleting
the only complete copy of a workout on one tap was the last unguarded
irreversible action in the app.

**Activity types have icons.** Three vector drawables in `res/drawable`, the
official Material Symbols paths from `google/material-design-icons`, shown on
both lists and both detail screens. Not `material-icons-extended`, for the
reason A.11 already gives: release builds run with `isMinifyEnabled = false`, so
an unused icon is an icon that ships. Symbols are drawn in a 960×960 box whose
origin is the baseline — the viewBox is `0 -960 960 960` — and Android's
viewport has no negative origin, so each drawable wraps its path in a
`<group android:translateY="960">` rather than having the path rewritten by
hand, which would make the next update a transcription exercise. An exercise
type this build does not recognise draws no icon at all, rather than defaulting
to a plausible wrong one.

**Fast and hard recordings are marked.** A recording is marked fast when its
average speed is at or above the 75th percentile of the recordings on the same
route, and marked for effort when its average heart rate is. Three decisions
worth naming:

- **Per route, or not at all.** A recording with no matched route has nothing to
  be fast against and is never marked. Ranking is within the route, so the best
  climb of a slow hilly route is marked even though every recording of the flat
  route beat it.
- **Four members minimum.** A route exists at three (§5.6), and the 75th
  percentile of three values is "the best one" wearing a statistician's hat.
  Four is the first count at which the top quartile is genuinely a quarter, and
  even then it is one recording — this marks a personal best against the route
  and claims no more.
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
### A.14 A heatmap of everything, drawn inside the routes' own extent

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
written whole, read whole, never queried, and 20,736 counts as rows would be a
table nothing ever selects from.

**The extent comes from the routes, never from the recordings.** This is the
whole reason the feature needed thinking about. One trip to another city is a
recording, not a route, and if it got a vote on the bounding box then everything
you actually run would be squashed into a corner of the picture by a thing you
did once. So the box is the routes' box, padded by 8% and then *grown* — never
cropped, never stretched — to the raster's 16:9. Recordings still contribute
their heat wherever they cross that box; they simply do not get to move it. A
sample outside the box is dropped rather than clamped, because clamping piles
every distant point onto the border cells and draws a hot frame around a map
with nothing at its edges.

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
