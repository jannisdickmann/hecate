# Product

What Hecate is for, what it must do, and the qualities it must keep. Formerly
§1–§3 of `SPECIFICATION.md` (v1.0); the IDs below are the ones code comments
cite. Where the implementation departs from the original text, the text here
says what is built and links the ADR that says why. For how to build and run
it, see [`README.md`](../../README.md).

Personal route-learning companion for Pixel Watch 4 (Wear OS 6) + Android
phone. Single user, no cloud, no cost, sideloaded.

---

## Overview (§1)

### Problem (§1.1)

Google Health records workouts well but never learns that today's run is the
same route as last Tuesday's. There is no per-route history, no "am I faster
than usual at this exact point", no remaining-distance or arrival-time estimate
for a route it has seen twenty times.

### Solution (§1.2)

A standalone Wear OS recorder plus a phone companion. The watch records the
workout using Health Services (the same sensor-fusion layer Google's own
Exercise app consumes), identifies which of the learned routes the user is on
while the activity runs, and displays live route-relative metrics. The phone
stores history, learns routes by clustering past recordings, and writes
completed sessions to Health Connect so they still appear in Google Health.

### Constraints that shape the design (§1.3)

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

### Scope philosophy (§1.4)

v1 does five things and nothing else: record reliably, learn routes, identify
the current route, show four live numbers, show per-route history. Every
feature that can be deferred is deferred ([§8](scope.md)). Robustness of
recording ranks above every other quality attribute — a lost workout is
unrecoverable, a wrong ETA is a shrug.

---

## User stories (§2)

Stories are ordered by build sequence. Each has acceptance criteria.

### US-1 — Record a workout (must)

*As the user, I start a workout on my watch and it records GPS, heart rate and
distance until I stop it.*

- Start/pause/resume/stop from the watch, no phone required, no phone in range.
- Exercise types: `RUNNING`, `WALKING`, `HIKING`, `BIKING`, `SKIING`
  (outdoor, GPS on). The original three were `RUNNING`, `WALKING` and
  `BIKING`; the other two were added later, under the rule in
  [A.18](decisions/2026-09-13-hiking-and-skiing-and-exercise-types-as-enums.md).
- Survives: screen off for the whole activity, app process death, watch reboot
  mid-activity (recording resumes or the partial session is preserved).
- Auto-pause events from Health Services are honoured and recorded.
- The recording ends itself when the activity plainly has not, and discards the
  tail that was not part of it: three quarters of an hour inside a 30 m circle,
  a quarter of an hour without a usable fix, or a speed the sport cannot reach
  sustained for ninety seconds. A manual pause suspends all three; nothing ends
  a recording that has not yet travelled 50 m
  ([A.30](decisions/2026-09-21-a-recording-ends-itself.md)).
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
  A rejection says whether it is permanent, and only a permanent one is
  acknowledged; an empty session is dropped on the watch before it is offered
  ([A.17](decisions/2026-09-12-a-missing-permission-must-not-cost-the-recording.md)).
- Failed Health Connect writes are retried and never block local history.

### US-3 — Routes are learned from history (must)

*As the user, after I have run the same way a few times, the app recognises it
as one of my routes.*

- A cluster of ≥ 3 similar recordings becomes an active route.
- Routes are direction-sensitive: the reverse of a route is a different route.
- Routes are auto-named on creation (`Route 7`, time-of-day hint) and renamable.
  A renamed route keeps its name across re-clustering
  ([A.5](decisions/2026-09-11-route-identity-survives-re-clustering.md)).
- Re-clustering runs after each ingest; results are deterministic for a given
  session set.
- A path that differs materially from all known routes forms its own cluster —
  shortcuts and variants become separate routes, not sub-variants
  ([§8.1](scope.md#route-variants-as-a-hierarchy-81)).

### US-4 — The current route is identified during the activity (must)

*As the user, some minutes into my workout the watch tells me which route I am
on — and honestly says "unknown" when it does not know.*

- Display states, revised in
  [A.27](decisions/2026-09-19-the-watch-says-what-it-knows.md):
  `Noch am Vergleichen` (nothing decided yet) → `Arbeit · Waldrunde` (several
  indistinguishable) → `≈ Weg zur Arbeit` (a leader, named provisionally) →
  `Weg zur Arbeit` (committed) → `Neue Strecke` (null hypothesis dominant).
  `Noch keine Routen` is separate: the bundle holds no route of this type. The
  original wording — `Suche…` and `Unbekannt` — described the same states as
  failures and threw away a named favourite the matcher was already carrying.
- Commitment must not occur while two or more routes remain plausible; neither
  may the provisional naming above. A shared start segment shows both names for
  as long as the paths coincide — unless the user's route priority
  ([A.4b](decisions/2026-09-11-manual-route-priority.md)) or the seniority rule
  of [A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md)
  names one of them, flagged as an assumption.
- Commitment is revocable: if evidence collapses, the display falls back to the
  weaker claims above rather than showing a wrong name.
- Never commits on a genuinely novel path. A route met late in a ride is
  compared from where it was joined and is never guessed at
  ([A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md)).
- No state of the matcher may be drawn in a warning colour. Whether the app
  knows the route and whether the recording is working are separate questions
  with separate lines — the route line and the health line of
  [A.27](decisions/2026-09-19-the-watch-says-what-it-knows.md). (The original
  text cited this as "US-11"; there is no US-11.)

### US-5 — Ahead/behind, before the route is known (must)

*As the user, I see whether I am faster or slower than usual as early as
possible, even before the route is identified.*

- **As built:** ahead/behind is read off the one named route — committed, or
  named provisionally — from where that route was joined, and carried across
  when the name changes, so a junction never makes it jump
  ([A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md)).
  The original criterion — computed while uncommitted against the pooled
  history of all still-plausible candidates, which share the traversed prefix
  and so share its history — is superseded; on shared ground the named
  contenders' ahead/behind is the same anyway.
- Displayed as signed seconds vs. the median at the current position, and as a
  bar down the right edge of the screen whose full-scale deflection is a
  multiple of the route's own interquartile spread at the finish
  ([A.27](decisions/2026-09-19-the-watch-says-what-it-knows.md)).
- Suppressed only when the null hypothesis dominates, and frozen — not
  recomputed — while the projected position is stale, so that a GNSS outage is
  never charged to the user as lateness.
- Where there is nothing to compare against, the cell shows the session's
  average speed rather than a dash.

### US-6 — Remaining distance and arrival time (must)

*As the user, once the route is known I see how far is left and when I will
arrive.*

- Remaining distance = route length − current position, also drawn as a
  progress bar down the left edge of the screen
  ([A.27](decisions/2026-09-19-the-watch-says-what-it-knows.md)).
- ETA derived from the per-position median time profile (shown as speed, never
  pace — [A.9](decisions/2026-09-11-speed-in-kmh-never-pace.md)), scaled by
  today's observed performance ratio, and published as an absolute arrival
  instant so a stale figure cannot walk forwards.
- Both suppressed while there is no single route behind them. An unambiguous
  leader counts, marked as provisional; a tie does not
  ([A.4b](decisions/2026-09-11-manual-route-priority.md),
  [A.27](decisions/2026-09-19-the-watch-says-what-it-knows.md),
  [A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md)).

### US-7 — Deviation alert (must)

*As the user, if I leave the route I am told, by vibration, without looking at
the watch.*

- Fires after sustained divergence, not on a single GPS jump.
- Haptic + ongoing-activity notification text. No screen takeover.
- Fires at most once per departure; re-arms after returning to the route.
- **As built:** commitment *arms* the detector, and a later demotion — even the
  null hypothesis winning — does not disarm it
  ([A.4](decisions/2026-09-11-deviation-detector-is-armed-by-commitment.md));
  the distance measured is to the nearest of the armed route and every live,
  tracking candidate, so leaving one known route for another is not a departure
  ([A.4c](decisions/2026-09-11-deviation-is-measured-against-the-route-network.md)).
  The original wording, "never fires while uncommitted or while the null
  hypothesis dominates", holds for the first half only: it never fires before
  anything has been committed.

### US-8 — Per-route history (must)

*As the user, I open the phone app and see how this route has gone over time.*

- Route list: name, length, number of recordings, median time, best time.
- Route detail: table of all recordings (date, time, active duration, avg HR),
  and a drawing of the route geometry — over an OpenStreetMap ground
  ([A.29](decisions/2026-09-20-osm-basemap-under-every-track.md)), coloured by
  effort, with altitude, speed and heart-rate charts
  ([A.20](decisions/2026-09-13-altitude-profiles.md),
  [A.21](decisions/2026-09-13-filter-louder-mark-real-heatmap-no-splits.md),
  [A.23](decisions/2026-09-15-two-heatmap-rasters-and-one-chart-composable.md)).
  Per-kilometre splits were specified here, built, and removed again — see
  [A.21](decisions/2026-09-13-filter-louder-mark-real-heatmap-no-splits.md).
- Session detail: the same table row plus which route it matched, coverage
  score, and where commitment occurred. A recording is filed under a route only
  if the clusterer made it a member
  ([A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md)).

### US-9 — History backfill (must)

*As the user, I can seed the app with workouts I recorded before it existed so
routes are learned immediately rather than in three months.*

- Import TCX files (Google Health's per-workout export) from a file picker.
- Multi-select import; duplicates rejected by start-time proximity.
- Imported sessions participate in clustering identically to recorded ones and
  are flagged as imported (not re-written to Health Connect).
- A whole Google Health takeout is converted to a backup archive off the phone
  and restored in one go —
  [A.15](decisions/2026-09-12-backfill-from-a-takeout.md); the commands are in
  the README, *Importing a Google Health takeout*.

### US-10 — Replay and diagnosis (must, developer-facing)

*As the developer, I can replay any recorded session through the matcher and see
exactly what it decided and why.*

- Every session stores a match log: per-sample candidate weights, chosen route,
  commit/demote events.
- The `core` module replays a stored session offline at arbitrary speed and
  produces the same decisions as the watch did.
- Divergence between on-watch and replayed decisions is a bug.
- The same session can also be replayed *into the watch app* on an emulator, so
  the screen's behaviour is visible, not only the decisions
  ([A.28](decisions/2026-09-13-replaying-an-activity-into-the-recorder.md)).

---

## Non-functional requirements (§3)

| # | Requirement |
|---|---|
| NFR-1 | A completed workout is never lost. Crash-safe append-only recording, fsync at least every 10 s. |
| NFR-2 | The watch is fully standalone during an activity. No phone dependency for recording, matching, or display. |
| NFR-3 | Battery cost is dominated by GNSS + HR, i.e. comparable to Google's Exercise app. Matcher CPU per sample < 5 ms for ≤ 50 candidate routes. |
| NFR-4 | Matcher and clustering logic are pure Kotlin, unit-testable on the JVM with no Android dependencies. |
| NFR-5 | All tuning constants live in one documented object (`Tuning`). No magic numbers scattered in logic. |
| NFR-6 | Durations use `SystemClock.elapsedRealtimeNanos`, never wall clock. Wall clock is recorded once per session for display and Health Connect. |
| NFR-7 | The watch has no network access of any kind and its manifest declares no `INTERNET` permission. The phone declares one, and uses it for exactly one thing: fetching OpenStreetMap geometry for ground already recorded, in tile-grid-aligned rectangles, on request ([A.29](decisions/2026-09-20-osm-basemap-under-every-track.md)). No recording, position, time or identifier ever leaves either device. |
| NFR-8 | Total third-party dependencies outside AndroidX: one (kotlinx.serialization). **As built:** the Android modules also carry `play-services-wearable`, and `:wear` declares Guava `compileOnly`; `:core` has exactly one ([A.7](decisions/2026-09-11-dependencies-outside-androidx.md)). |

NFR-7 was originally "no network at all, on either device"; A.29 rewrote it
rather than deleting it, because the watch half is still true and the reason
belongs beside it.
