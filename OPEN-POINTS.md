# Open points

Things that need a human, a device, or a decision before Hecate can be trusted
with a workout. Ordered by how much damage they can do.

The short version: `:core` is built, tested and believed correct. `:wear` and
`:phone` now compile, lint clean and assemble to APKs — see §1.1 — but neither
has been installed on anything. Until they have been worn, none of the device
*behaviour* is verified; what is verified is that the code says what it meant to
say to the libraries it calls.

---

## 1. Blocking — nothing has been run on a device

### 1.1 ~~The Android modules have never been compiled~~ Both modules build

**Resolved.** Access to `dl.google.com` was the whole of it. With SDK platform
36 installed and `sdk.dir` set in `local.properties`:

```bash
./gradlew :phone:assembleDebug  :wear:assembleDebug    # both APKs
./gradlew :phone:lintDebug      :wear:lintDebug        # "No issues found", both
./gradlew :phone:assembleRelease :wear:assembleRelease # unsigned; see §1.2
./gradlew :core:test                                   # still 77/77
```

`settings.gradle.kts` needed no change: it picks the modules up as soon as an
SDK is there, exactly as intended.

The errors were real version drift, roughly where this section predicted, plus
two places it did not look. Item by item, against the original list:

- **Health Services** (`ExerciseSource`, `RecorderService`).
  ~~the exact shape of `ExerciseUpdateCallback`~~ — five methods, as written.
  ~~whether `DataType.DISTANCE_TOTAL` is retrieved via `getData(...).total`~~ —
  it is. ~~the `ExerciseState` category predicates `isEnded` / `isEnding` /
  `isPaused`~~ — all three exist; writing against the predicates rather than an
  enum list was the right call and needed no change.

  **`LocationData` was wrong, and no version fixes it.** It carries four fields
  — latitude, longitude, altitude, bearing — in the pinned 1.1.0-alpha05 and in
  1.1.0-rc02 alike. `horizontalPositionErrorMeters` lives on `LocationAccuracy`,
  reached through the data point's `accuracy`, which is null whenever Health
  Services has no estimate to give. `altitude` is a non-null `Double` that
  signals unavailability with NaN, so the safe call was never doing any work and
  the `isFinite()` test was already the whole check.

  **Not anticipated: `ListenableFuture` is not on the compile classpath.**
  Every `ExerciseClient.*Async` method returns one, but health-services-client
  declares Guava at *runtime* scope — in every version from 1.1.0-alpha05 to
  1.1.0-rc02. Guava's presence at runtime then resolves
  `com.google.guava:listenablefuture` to the empty
  `9999.0-empty-to-avoid-conflict-with-guava` marker, and AGP's consistent
  resolution propagates that to the compile classpath, so androidx.concurrent's
  own copy is empty too. Fixed with a `compileOnly` dependency on
  `com.google.guava:guava:31.1-android`, the version health-services-client
  itself resolves to. It is already in the APK transitively, so the artifact is
  unchanged, and NFR-8 / Appendix A.7's count of non-AndroidX *runtime*
  dependencies is unchanged with it.

  `health-services-client` stays pinned at **1.1.0-alpha05**. Nothing here
  needed a newer one. 1.1.0-rc02 exists if you want it, and is a deliberate
  decision (§7), not a compile fix.

- **Health Connect** (`HealthConnectWriter`).
  `Metadata.activelyRecorded(device, clientRecordId)` was spelled correctly; the
  *pin* was too early. alpha11 exposes only the raw `Metadata(...)` constructor,
  and the named factories arrive in **1.1.0-alpha12**, which is now what is
  pinned. Not one line of the writer changed.

  Worth knowing if you touch it: `activelyRecordedWithId`'s `id` is Health
  Connect's own record UUID, for updating a record that already exists — not a
  synonym for `clientRecordId`. The idempotent insert this app wants is the
  two-argument `activelyRecorded`.

  ~~verify that `ExerciseRoute.Location` still takes `horizontalAccuracy` as a
  `Length`~~ — it does.

  **1.1.0 has since gone stable** with an identical `Metadata` surface. Moving
  to it is a one-line change and has deliberately been left as a decision rather
  than folded into a compile fix.

- **Room** (`Daos.kt`). ~~this will either work or tell you exactly what it
  dislikes~~ — it told us exactly, and it was right: a LIMIT/OFFSET expression
  in SQLite cannot reach the enclosing query, so the correlated
  `OFFSET (SELECT COUNT(*)/2 … WHERE mm.route_id = r.id)` fails with "no such
  column: r.id". Confirmed against sqlite3 directly, not just taken from the
  error. Replaced with a window function over a CTE — one pass for the whole
  list instead of a correlated subquery per row.

  **Found while rewriting it: there were three different medians.** The comment
  said "lower median", the SQL's `OFFSET n/2` gave the *upper* median for an even
  member count, and `HecateViewModel.median()` — which feeds the route *detail*
  screen — averages the two middle values. The list row and the detail screen
  would have disagreed on the same route. The SQL now matches the Kotlin helper.
  If you would rather both be the lower median, change them together.

- **Wear Compose** (`HecateWearApp`). ~~`Chip`, `ChipDefaults.primaryChipColors`
  and `Button`~~ — nothing to fix. All three are where they were expected, in
  `androidx.wear.compose.material` at the pinned wear-compose 1.4.1. The warning
  about `wear.compose.material3` still stands for anyone tempted.

Two things this section did not think to look at:

- **The build scripts themselves.** The first error out of a Gradle that could
  finally resolve AGP was in `wear/build.gradle.kts` and `phone/build.gradle.kts`:
  the receiver inside `signingConfigs { create("release") { … } }` shadows
  `java`, so the literal `java.util.Properties()` does not resolve and the script
  fails to compile before any module is configured. `keystore.properties` is now
  read once at the top of each script.

- **Lint had never run either.** One error, in both modules:
  `InvalidFragmentVersionForActivityResult` on every `registerForActivityResult`.
  Neither app uses fragments — androidx.fragment 1.1.0 arrives through
  play-services-wearable → play-services-basement, and every basement release
  from 18.4.0 to 18.9.0 names that same 1.1.0, so bumping Play Services would not
  have helped. A `constraints` block raises it to 1.8.6. Both modules also had
  twenty warnings between them; all are now fixed or suppressed with the reason
  written at the suppression, and `lintDebug` reports "No issues found" for both.
  The two suppressions are `StaticFieldLeak` on the `AppGraph` objects (what they
  hold is the *application* context) and `UnnecessaryRequiredFeature` on the
  watch's GPS feature (it is required, meant literally).

Every version that changed is pinned in `gradle/libs.versions.toml` with the
reason beside it: health-connect alpha11 → alpha12, plus two new pins that were
not there before, guava 31.1-android and androidx.fragment 1.8.6. Nothing is
left to "latest", and §7 still applies — upgrade deliberately.

**What is still open here is device work, and only device work**: nothing below
§1.2 has moved.

### 1.2 No release keystore exists

§6.3 is emphatic and it is right: a debug-key reinstall means a signature
mismatch, an uninstall, and the loss of any session the watch has not yet
uploaded. Create one release keystore, back it up **off this machine**, and put
its coordinates in `keystore.properties` at the repository root:

```properties
storeFile=/absolute/path/to/hecate-release.jks
storePassword=…
keyAlias=hecate
keyPassword=…
```

Both modules pick it up automatically when the file exists and fall back to an
unsigned release build when it does not. `keystore.properties` and `*.jks` are
git-ignored. **Upload before reinstalling, every time.**

### 1.3 The watch↔phone contract has never seen a real message

`de.hecate.wear.Wire` and `de.hecate.phone.Wire` are two copies of the same ten
constants, deliberately not shared through `:core` (which must not learn about
transports). Nothing checks that they agree. The first end-to-end session is the
test. If sessions never arrive, check in this order: both `WearableListenerService`
declarations are exported with the `/hecate` path prefix; both APKs are signed
with the *same* key (the Data Layer will not deliver between differently signed
apps); and the phone app has been launched at least once.

---

## 2. Needs a decision

### 2.1 Tuning constants have only ever met synthetic GPS

Every constant in `de.hecate.core.Tuning` is reasoned from the geometry of the
problem and validated against synthetic tracks. Synthetic tracks are generated
with a specific noise model — an Ornstein-Uhlenbeck process with a 100 s
correlation time and a 4 m standard deviation — and real GNSS is not that.

Treat the following as first guesses, to be revisited against recorded fixtures
via the replay harness:

| Constant | Value | What to watch for |
|---|---|---|
| `GPS_SIGMA_M` | 15 m | Too low and the matcher is overconfident between buildings |
| `WEIGHT_DECAY` | 0.97 | Too high and an early mistake sticks; too low and nothing ever commits |
| `COMMIT_SEPARATION_M` | 50 m | Too high and parallel streets never separate |
| `NULL_DISTANCE_M` | 120 m | Must stay above `DEVIATION_M`; see Appendix A.3 |
| `CLUSTER_CUT` | 0.25 | Too low and every variation is a new route; too high and shortcuts merge |
| `DEVIATION_HOLD_SAMPLES` | 15 | 15 s of alert latency; shorter risks false alarms at junctions |

The replay harness exists for exactly this (US-10):

```bash
./gradlew :core:run --args="bundle --sessions ./fixtures --out bundle.json"
./gradlew :core:run --args="replay --bundle bundle.json --session fixtures/x.jsonl"
./gradlew :core:run --args="onwrist --bundle bundle.json --session fixtures/x.jsonl"
```

`onwrist` compares the decisions the watch actually made, stored inside the
session file, against a fresh replay. Any difference is a bug by definition.

### 2.2 The fixture set does not exist yet

§6.5 says to build it first: work route, two shortcuts of it, the reverse
direction, one novel path, one with a 90 s GPS dropout, one with a long pause.
All seven exist as *synthetic* geometries in `core/src/test/.../Synthetic.kt`
and the unit tests exercise them. None exists as a real recording. Until real
ones do, the golden-file regression testing §6.5 calls for has nothing to guard.

Once you have ten or so real sessions, `adb pull` them off the watch
(`/data/data/de.hecate.wear/files/sessions/`) into a `fixtures/` directory and
commit the ones that are interesting.

### 2.3 Route priority in use

Priority (Appendix A.4b) breaks ties between routes that share the same ground.
One integer per route, compared only against other *contenders*, and not
context-aware — see §4 for why that is settled rather than pending.

Two things to watch once you are using it:

- Setting a priority rebuilds and pushes the bundle immediately, so it takes
  effect on the next workout rather than after the next ingest. If the watch is
  out of range when you change it, it takes effect whenever the watch next syncs.
- Preferring the long route costs roughly twenty seconds of wrong label on the
  days you take the shortcut — one `COMMIT_HOLD_SAMPLES` window after the paths
  part. That is the trade you are making, and it is the right way round only if
  you take the preferred route most of the time.

### 2.4 Ahead/behind sign convention

`deltaVsMedianS` is `elapsed − median`, so **positive means slower than usual**.
The watch shows `+45 s` in red with "langsamer als sonst" beneath, `−12 s` in
green with "schneller als sonst". Runners are used to the opposite sign from
race splits. Change `Format.delta` and the colour test in `RoutePage` together
if you want it the other way round.

### 2.5 A parallel street 80 m away will match a learned route

With `NULL_DISTANCE_M` at 120 m (Appendix A.3), a path running 80 m from a known
route still scores better than "unknown", so it will be reported as that route —
though the deviation alert will fire, since 80 m is above `DEVIATION_M`. The
combination is arguably the right behaviour ("you're doing the work route, but
you're off it"), but it has not been tried in a real street grid, where routes
80 m apart are common. Watch for it.

### 2.6 Clustering is O(n²) and recomputed on every ingest

§5.7 says this is fine for a few hundred sessions and defers incremental
clustering (§8). At one workout a day that is fine for a year. The coverage
metric is the expensive part and is accelerated by a bucket index, but
`ClusterWorker` also reloads every sample of every session from Room on each
run. Measure it once you have two hundred sessions; if it hurts, cache the
resampled tracks rather than reaching for incremental clustering.

### 2.7 Bundle size on the watch

A route bundle is gzipped JSON containing every route's geometry at 10 m spacing
plus its profile. Fifty 10 km routes is roughly 50 000 points — a few hundred
kilobytes gzipped. Comfortable, but the whole bundle is deserialised into memory
on the watch at session start. If the route list ever grows past what §8.3
contemplates, this is the thing that will complain first.

---

## 3. Known gaps against the specification

### 3.1 The auto-pause broadcast test has not been run

§6.5 gives an `adb shell am broadcast` incantation for injecting
`whs.AUTO_PAUSE_DETECTED`. The code handles `ExerciseState.AUTO_PAUSED` and
records a `PAUSE_AUTO` event, but this has never been exercised.

### 3.2 No instrumented tests

`:core` has 77 JVM tests. Neither Android module has a single test. §6.5 does not
ask for instrumented tests — it asks for emulator and on-wrist verification,
both of which are manual. This is a reasonable position for a single-user
sideloaded app, but it does mean every Android-side regression will be found by
wearing it.

### 3.3 Battery has not been measured

NFR-3 asks for battery cost comparable to Google's Exercise app. The matcher's
CPU budget is tested (a unit test fails if it exceeds 5 ms per sample with a
realistic route set); the GNSS and heart-rate cost is whatever Health Services
does. Unmeasured.

### 3.4 Ambient batching has not been observed

§1.3 says exercise data arrives at ~1 Hz with the screen on and batched while
ambient. The matcher is proven to produce identical results either way — that is
a unit test, `batched processing equals streamed processing`. What has not been
seen is how large the batches actually are on a Pixel Watch 4, which determines
how stale the Route page looks mid-run. §7 says to leave batching at its default
until measured; that is still the position.

---

## 4. Things deliberately not done

Everything in §8 of the specification, as specified: route variant hierarchies,
automatic session start, the route graph, Google Health API integration, maps,
charts, localization, and the rest of §8.5.

Three more worth naming explicitly, because they look like oversights:

- **Route priority does not vary by weekday or time of day.** The obvious
  extension, since the start-of-session prior already buckets by exactly that,
  would be a per-route map of context key to priority, resolved once at session
  start and falling back specific-to-general (this weekday-and-time bucket → all
  weekdays → the base value). It was designed and costed and then declined: one
  number per route is what gets used, twelve cells per route is what gets left at
  the default, and the automatic variant — ranking contenders by how often each
  was taken in this context, which the bundle already carries — follows habit
  rather than intent and is confidently wrong on exactly the days you do
  something unusual. The simple version is the decision, not a placeholder for a
  better one.
- **No phone-side recording.** §8.5 defers it. The phone app has no record
  button and the manifest asks for no location permission.
- **No network permission anywhere.** NFR-7. Neither manifest declares
  `INTERNET`. If a future dependency needs it, that is a decision to take
  consciously, not a merge to wave through.
