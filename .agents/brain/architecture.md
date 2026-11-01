# Architecture

How Hecate is put together, and the constraints that shaped it. Formerly §4,
§5.1–§5.5 and §7 of `SPECIFICATION.md`; the matcher (§5.6) and clustering
(§5.7) have files of their own, [matcher.md](matcher.md) and
[clustering.md](clustering.md). For how to build, install and run any of it,
see [`README.md`](../../README.md).

---

## Technology choices (§4)

Chosen for smallest moving-part count, not for elegance.

| Concern | Choice | Rationale |
|---|---|---|
| Language | Kotlin, single Gradle project | — |
| Modules | `:core` (pure JVM), `:wear`, `:phone` | Forces the matcher to stay Android-free and JVM-testable |
| Watch UI | Compose for Wear OS **Material 3**, `HorizontalPagerScaffold`, **3 pages** (Map, Live, Control) — originally 3 pages on Material 2.5, then 2 (Live, Control), then a heading-up map page added on the left ([A.11](decisions/2026-09-12-material-3-and-one-live-watch-page.md)) | No navigation library needed |
| Phone UI | Compose Material 3 with dynamic colour (`HecateTheme`); screens switched on a state enum with a bounded back stack and a settings panel ([A.11](decisions/2026-09-12-material-3-and-one-live-watch-page.md), [A.13](decisions/2026-09-12-settings-panel-back-stack-and-standout-marks.md)) | — |
| Exercise data | `androidx.health:health-services-client` (pinned version) | The only way to get fused GPS/HR/distance on Wear OS |
| Health Connect | `androidx.health.connect:connect-client` | Phone-side write-back |
| Watch storage | Append-only JSONL file per session in app-internal storage | Crash-safe, no schema migrations, no DB on the critical path |
| Phone storage | Room + SQLite | Queries and history need a real DB |
| Serialization | kotlinx.serialization JSON, gzipped | Avoids a protobuf toolchain; payloads are ~40 KB gzipped per hour |
| Watch↔phone | Wearable Data Layer `DataClient` with `Asset` | Automatic retry and delivery when out of range; the robustness requirement in [US-2](product.md#us-2--session-reaches-the-phone-and-health-connect-must) comes free. It is not AndroidX, which breaks NFR-8 ([A.7](decisions/2026-09-11-dependencies-outside-androidx.md)) |
| Dependency injection | None. Manual constructor wiring, one `AppGraph` object | Hilt would add build complexity for ~15 objects |
| Charts / maps | Compose `Canvas` + tables, over a dim OpenStreetMap vector basemap fetched from Overpass and stored as local tiles ([A.29](decisions/2026-09-20-osm-basemap-under-every-track.md)) | Still no maps SDK, no API key, no billing and no third-party dependency — the codec, the projection and the viewport query are `:core` Kotlin over `java.util.zip` and `HttpURLConnection` |
| Background work | `WorkManager` (phone only, one-shot after ingest; also Health Connect retries and map prefetch) | Clustering is the only deferred job the design needs |
| Testing | JUnit + kotlin-test on `:core`; replay harness as a `:core` main; Robolectric on `:phone` ([A.16](decisions/2026-09-12-robolectric-for-the-phone.md)) | — |
| Localization | None. German strings hardcoded | Single user. Vocabulary in [glossary.md](glossary.md) |
| SDK levels | `:wear` min/target 36; `:phone` min 34 / target 36 | One device pair; no back-compat burden. `compileSdk` is 37 (Compose BOM requires it); why `targetSdk` trails is open point §2.7 |

**Explicitly rejected for v1:** Hilt, protobuf, Retrofit, any maps SDK, any
chart library, Compose Navigation, multi-module feature splits, Google Health
API / OAuth ([§8.4](scope.md#google-health-api-integration-84)), Firebase,
Crashlytics. Also declined since: Material 3 Expressive (alpha-only),
`material-icons-extended` (release builds are not minified, so every unused
icon ships), `material3-adaptive-navigation-suite`, Auto Backup and a Google
Drive integration ([A.10](decisions/2026-09-11-backup-is-a-file-not-a-cloud.md)),
`kotlinx-coroutines-play-services`
([A.7](decisions/2026-09-11-dependencies-outside-androidx.md)).

---

## Data flow (§5.1)

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

The phone's Room database is therefore the only complete copy of anything once
the watch has deleted an acknowledged file — which is why there is a backup
archive ([A.10](decisions/2026-09-11-backup-is-a-file-not-a-cloud.md)).

Everything on the watch that touches the recording — the matcher, the session
writer, the auto-stop — runs on one lane,
`Dispatchers.Default.limitedParallelism(1)`, first in, first out. None of it is
thread-safe, and a pool once ran two deliveries through the matcher at once
([A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md)).

---

## `:core` — pure Kotlin (§5.2)

Contains everything algorithmic. No Android imports; enforced by the module's
dependency list.

- `model/` — `Sample`, `SessionHeader`, `RouteGeometry`, `RouteProfile`,
  `RouteBundle`, `MatchState`, `MatchLogEntry`
- `matcher/RouteMatcher` — the online matcher. Pure: `update(sample) -> MatchState`
  ([matcher.md](matcher.md))
- `matcher/GridIndex` — 100 m spatial cells → candidate route/segment refs
- `cluster/RouteClusterer` — offline clustering of session tracks into routes
  ([clustering.md](clustering.md))
- `cluster/CoverageMetric` — the symmetric track-similarity metric
- `profile/ProfileBuilder` — per-position median time and HR curves (and, since
  A.20, altitude)
- `geo/` — resampling, haversine, point-to-segment projection
- `tcx/TcxParser` — import
- `takeout` — the Google Takeout importer
  ([A.15](decisions/2026-09-12-backfill-from-a-takeout.md))
- `archive/` — `Archive` and `ArchiveCodec`, the backup format; moved down from
  `:phone` so a command-line tool can write it
  ([A.15](decisions/2026-09-12-backfill-from-a-takeout.md))
- `recorder/AutoStop` — when a recording has stopped being an activity, and
  where to cut it ([A.30](decisions/2026-09-21-a-recording-ends-itself.md)).
  Clock-free, so the watch and a replay agree
- `model/ActiveClock`, `model/HeartRates`, `profile/Timeline`,
  `profile/Series`, `profile/Elevation` — the clock repair, heart-rate join,
  chart series, rolling median and thinning that the phone applies on the way
  in or out of storage
  ([A.20](decisions/2026-09-13-altitude-profiles.md),
  [A.23](decisions/2026-09-15-two-heatmap-rasters-and-one-chart-composable.md)–[A.26](decisions/2026-09-16-heart-rate-is-its-own-series.md))
- `heat/Heatmap` and its codec; the basemap pipeline under `map/` (`OsmJson`,
  `Classify`, `TileCodec`, `BasemapQuery`, `MapView`, with `geo/Simplify`)
  ([A.14](decisions/2026-09-12-heatmap-drawn-around-the-routes-extent.md),
  [A.29](decisions/2026-09-20-osm-basemap-under-every-track.md))
- Shared helpers both apps call rather than copy: `display/Units` (distance and
  duration strings), `geo/Geo.kmh` (average speed), `display/HeadingUp.signedDelta`,
  `model/Altitudes.isPlausible`; inside `:core`, `io/GzipText`,
  `SessionCodec.writeSession` (the one place a session file's line order is
  written, used by the backup too) and `replay/CliInputs` for the two CLIs
  (see [development.md, *Where shared code lives*](development.md#where-shared-code-lives))
- `Tuning` — every constant, with a comment giving its unit and rationale (NFR-5)
- `replay/ReplayMain` — CLI entry point for US-10, and for `bundle`, `sessions`,
  `extract`, `takeout`, `autostop`, `karte`, `karteinfo` (README, *The replay
  harness*)

A principle that runs through `:core` since A.20: **repair on the way out, not
on the way in.** Altitude de-spiking, the active clock, the heart-rate hold and
`unpause` are applied when a session is read, so what the watch said stays in
the database and in every backup and the rule can change without a migration.

---

## `:wear` (§5.3)

- `RecorderService` — foreground service, `foregroundServiceType="health|location"`.
  Owns the `ExerciseClient` session. This type is mandatory; without it sessions
  die with `AUTO_ENDED_PERMISSION_LOST`. The exercise configuration is built from
  the permissions actually granted, because Health Services rejects a
  configuration as a whole
  ([A.17](decisions/2026-09-12-a-missing-permission-must-not-cost-the-recording.md)).
- `SessionWriter` — append-only JSONL, fsync every 10 s and on pause/stop.
- `AutoStopWatch` (in `:core`) — fed every sample and asked every 30 s whether
  the activity is over; ends the session and writes the cut into the footer.
- `MatcherHost` — feeds samples to `:core` matcher; wraps every call in
  try/catch, and on repeated failure sets a disabled flag and carries on
  recording (NFR-1 outranks the feature). A disabled matcher clears its numbers
  rather than leaving them frozen on screen.
- `BundleStore` — caches the latest route bundle received from the phone;
  survives without one (the watch then says `Noch keine Routen`;
  [A.27](decisions/2026-09-19-the-watch-says-what-it-knows.md)).
- `Uploader` — on stop, and on service start (to catch reboot leftovers), puts
  each unacked session file as a `DataClient` Asset. A session with no samples
  is dropped rather than offered.
- `VersionPublisher` — on every process start, puts the installed version as
  the data item `/hecate/watch-version`, so the phone can tell whether the
  watch is behind the newest release
  ([A.34](decisions/2026-11-01-updates-from-github-releases.md)).
- `SampleFeed` — where `ExerciseSource` stands; a debug-only implementation
  replays a stored session into the recorder
  ([A.28](decisions/2026-09-13-replaying-an-activity-into-the-recorder.md)).
- UI: three pager pages, opening on the middle one — **Map** (heading-up, the
  followed route under the trail), **Live** (metrics, route belief, the two edge
  bars and one health line) and **Control** (pause/stop). Layout adapts to the two watch
  sizes at the platform's large-round breakpoint (`screenWidthDp >= 225`).
  Ongoing Activity notification mirrors the key metric and takes its route
  wording from the same place the screen does.

Recorder state machine: `IDLE → PREPARING → ACTIVE ⇄ PAUSED → ENDING → IDLE`.
`PREPARING` calls `prepareExercise()` and waits for a GNSS fix before
`startExercise()` — **for at most 90 s**, after which the session starts
regardless and location joins when it arrives; waiting for a fix is an
optimisation for the matcher, not a precondition
([A.17](decisions/2026-09-12-a-missing-permission-must-not-cost-the-recording.md)).
Any unexpected `ExerciseUpdate` state transitions to a safe state and preserves
the file. `endSession` is reached by two paths (Beenden, and Health Services
reporting the end) and is guarded by a `Mutex`
([A.26](decisions/2026-09-16-heart-rate-is-its-own-series.md)).

### The session file

One JSONL line per record, in `files/sessions/session-<uuid>.jsonl`: a header,
samples (one per *position*), `"e"` lifecycle events
([A.12](decisions/2026-09-12-session-lifecycle-events-are-kept.md)), the match
log, `"r"` heart-rate readings as their own series
([A.26](decisions/2026-09-16-heart-rate-is-its-own-series.md)), and a footer
that may carry `trimmedAfterTOffsetMs`, the auto-stop cut
([A.30](decisions/2026-09-21-a-recording-ends-itself.md)). A reader that does
not know a record kind drops the line, which is what lets new kinds be added
without breaking an older build. The same format is used for `sessions/` in the
backup archive and for replay fixtures.

---

## `:phone` (§5.4)

- `IngestService` (`WearableListenerService`) — receives Assets, parses,
  inserts into Room inside one transaction keyed on session UUID, acks the watch.
  `DataLayerIngest.sweep` also reads what the Data Layer already holds at app
  start, because `onDataChanged` is not a reliable only way in
  ([A.17](decisions/2026-09-12-a-missing-permission-must-not-cost-the-recording.md)).
- `SessionRepository.ingest` — applies the auto-stop cut once, before anything
  else sees the session; joins heart-rate readings onto positions
  (`HeartRates.merge`); refuses start-time duplicates (§7).
- `HealthConnectWriter` — writes session/route/HR/distance; retried by
  WorkManager; write status stored per session.
- `ClusterWorker` — after ingest, re-runs `RouteClusterer` over all sessions,
  reconciles route identity
  ([A.5](decisions/2026-09-11-route-identity-survives-re-clustering.md)),
  rebuilds `RouteProfile`s, rebuilds and pushes the route bundle, and — last, so
  a failure while drawing can never cost the watch its routes — builds the two
  heatmap rasters
  ([A.14](decisions/2026-09-12-heatmap-drawn-around-the-routes-extent.md),
  [A.23](decisions/2026-09-15-two-heatmap-rasters-and-one-chart-composable.md)).
- `TcxImporter` — US-9.
- `de.hecate.core.takeout` — US-9 at the scale of a whole export
  ([A.15](decisions/2026-09-12-backfill-from-a-takeout.md)).
- `ArchiveRepository` — maps Room rows to and from the `:core` archive; restore
  merges and bypasses ingest
  ([A.10](decisions/2026-09-11-backup-is-a-file-not-a-cloud.md)).
- `WeatherRepository` / `WeatherWorker` — each recording's weather from
  Open-Meteo, backfilled on any network after ingest and on resume
  ([A.33](decisions/2026-10-13-weather-and-a-phone-app-to-read-it-in.md)).
- `UpdateRepository` / `UpdateWorker` — asks GitHub's release list for a newer
  phone or watch build (the watch's version from its data item), daily, on
  every start and when the watch reports a new build; one notification per
  release, and the **Version** card in Settings
  ([A.34](decisions/2026-11-01-updates-from-github-releases.md)).
- Basemap tile store under `filesDir/karte/`, outside Room so that a map never
  enters a backup ([A.29](decisions/2026-09-20-osm-basemap-under-every-track.md)).
- UI: **Aufzeichnungen** list → **Recording detail**; **Routes** list →
  **Route detail**; **Settings** panel; the full-screen heatmap as a `Dialog`
  outside the back stack
  ([A.13](decisions/2026-09-12-settings-panel-back-stack-and-standout-marks.md),
  [A.21](decisions/2026-09-13-filter-louder-mark-real-heatmap-no-splits.md)).
  `Screen.Recordings` is the boundary at which "session" becomes "Aufzeichnung".

---

## Data model (phone, Room) (§5.5)

As specified:

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

As built, the schema has more, each addition with its reason in the linked ADR:

| Addition | Schema | Why |
|---|---|---|
| `route_member(route_id, session_id)`, `route.name_is_custom`, `session.avg_hr`, `session.hc_error`, `session.ingested_at`, `sample.active_ms`, `meta(key, value)`, `route.priority` | v1 | [A.6](decisions/2026-09-11-additional-columns-beyond-the-data-model.md), [A.5](decisions/2026-09-11-route-identity-survives-re-clustering.md), [A.4b](decisions/2026-09-11-manual-route-priority.md) |
| `session.ended_at_utc` | v2 | Health Connect needs the real end ([A.6](decisions/2026-09-11-additional-columns-beyond-the-data-model.md)) |
| `session_events` (one blob per session) | v3 | [A.12](decisions/2026-09-12-session-lifecycle-events-are-kept.md) |
| `heatmap` (one gzipped, run-length encoded raster per row; two rows) | v4 | [A.14](decisions/2026-09-12-heatmap-drawn-around-the-routes-extent.md), [A.21](decisions/2026-09-13-filter-louder-mark-real-heatmap-no-splits.md), [A.23](decisions/2026-09-15-two-heatmap-rasters-and-one-chart-composable.md) |
| data only: imported sessions at `tz_offset = 0` repaired | v5 | [A.15](decisions/2026-09-12-backfill-from-a-takeout.md) |
| `session_weather` (readings as JSON, summary in columns) | v10 | [A.33](decisions/2026-10-13-weather-and-a-phone-app-to-read-it-in.md) |
| `route_profile.median_alt_m` | v6 | [A.20](decisions/2026-09-13-altitude-profiles.md) |
| `route.priority` rescaled to −2..2, every route reset to 0 (table rebuilt) | v7 | [A.21](decisions/2026-09-13-filter-louder-mark-real-heatmap-no-splits.md) |

`meta` also holds the route bundle version counter and the peak-heart-rate
setting — a setting stored anywhere else would not survive a restore. Enums
(`exercise_type`, `source`) are `TEXT` holding the constant's name, and an
unknown name is a loud failure
([A.18](decisions/2026-09-13-hiking-and-skiing-and-exercise-types-as-enums.md)).
Every migration is replayed by `MigrationTest` through the app's own
`HecateDatabase.MIGRATIONS`
([A.16](decisions/2026-09-12-robolectric-for-the-phone.md),
[A.20](decisions/2026-09-13-altitude-profiles.md)).

---

## Risks (§7)

| Risk | Mitigation |
|---|---|
| Health Services / Health Connect are alpha-versioned and churn | Pin versions; upgrade deliberately (`gradle/libs.versions.toml`) |
| Foreground service killed mid-workout | Append-only file + resume on service restart; upload leftovers on boot |
| Duplicate sessions in Health Connect from the watch's own auto-detection | Disable auto-detection; dedupe by time window on ingest |
| Matcher regression from constant tuning | Golden-file replay tests over the fixture set |
| Cold start: nothing works for weeks | TCX backfill in M0 — and, at scale, the takeout import ([A.15](decisions/2026-09-12-backfill-from-a-takeout.md)) |
| Ambient staleness perceived as a bug | Documented as expected; batching mode left at default until measured |
| Debug-key reinstall wipes an un-uploaded session | Release keystore, backed up; upload before reinstalling ([development.md](development.md#signing-and-install-63)) |

Risks the original table did not foresee, each now with its own record: a
platform permission that silently stopped existing
([A.17](decisions/2026-09-12-a-missing-permission-must-not-cost-the-recording.md));
a migration that names the wrong column and stops the app from starting
([A.16](decisions/2026-09-12-robolectric-for-the-phone.md)); two threads ending
or matching the same session
([A.26](decisions/2026-09-16-heart-rate-is-its-own-series.md),
[A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md)); a
forgotten recording teaching the clusterer a motorway
([A.30](decisions/2026-09-21-a-recording-ends-itself.md)).
