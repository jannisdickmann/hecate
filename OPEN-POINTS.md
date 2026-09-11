# Open points

Things that need a human, a device, or a decision before Hecate can be trusted
with a workout. Ordered by how much damage they can do.

The short version: `:core` is built, tested and believed correct. `:wear` and
`:phone` are written but have never been compiled, because this development
environment cannot reach Google's Maven repository. Until they have been built
and worn, none of the device behaviour is verified.

---

## 1. Blocking — nothing has been built for a device

### 1.1 The Android modules have never been compiled

**Why.** The build environment reaches Maven Central but **`dl.google.com`** is
refused by the egress policy, so the Android Gradle Plugin, AndroidX, Compose,
Room, Health Services, Health Connect and Play Services cannot be resolved.
`maven.google.com` is only a redirector and lands on the same host. No Android
SDK is installed either, and the SDK downloads come from that host too.

Allowing that single domain in the environment's network policy would let a
future session install the SDK, resolve everything, and actually compile both
modules — which is most of what this section is about. Running the app would
still need a device.

**What was done instead.** `settings.gradle.kts` includes `:wear` and `:phone`
only when an SDK is present (`sdk.dir` in `local.properties`, or `ANDROID_HOME`
/ `ANDROID_SDK_ROOT`), so `./gradlew :core:test` works regardless. Every Android
source file was run through the Kotlin compiler to rule out syntax errors; the
remaining diagnostics were all unresolved AndroidX symbols.

**What you must do.** Open the project in Android Studio and build both modules.
Expect to fix compile errors. The likeliest places, in order:

- **Health Services** (`ExerciseSource`, `RecorderService`). Alpha-versioned and
  it churns (§7). Specifically unverified: the exact shape of
  `ExerciseUpdateCallback`, whether `DataType.DISTANCE_TOTAL` is retrieved via
  `getData(...).total`, the fields of `LocationData`
  (`horizontalPositionErrorMeters` in particular), and the `ExerciseState`
  category predicates `isEnded` / `isEnding` / `isPaused`. The state handling
  was written against those predicates rather than an exhaustive list of
  constants precisely so that an enum addition lands in a sensible bucket.
- **Health Connect** (`HealthConnectWriter`). `Metadata.activelyRecorded(device,
  clientRecordId)` is the current factory; earlier and later alphas spell this
  differently, and older ones took a bare `Metadata()` constructor. Also verify
  that `ExerciseRoute.Location` still takes `horizontalAccuracy` as a `Length`.
- **Room** (`Daos.kt`). The route-summary query computes a median with
  `LIMIT 1 OFFSET (SELECT COUNT(*)/2 ...)`. Room validates queries at compile
  time, so this will either work or tell you exactly what it dislikes.
- **Wear Compose** (`HecateWearApp`). `Chip`, `ChipDefaults.primaryChipColors`
  and `Button` come from `androidx.wear.compose.material` (Material 2 for Wear).
  If you move to `wear.compose.material3`, all three change shape.

Pin whatever versions actually work in `gradle/libs.versions.toml` and do not
drift from them casually (§7).

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

### 2.3 Route priority is a blunt instrument on purpose

Priority (Appendix A.4b) breaks ties between routes that share the same ground.
It is a single integer per route, compared only against other *contenders*, and
it is deliberately not context-aware: the same ranking applies on a Tuesday
morning and a Sunday afternoon. If you habitually take the long route on
weekdays and the shortcut at weekends, priority cannot express that — the
start-of-session prior already buckets by weekday and time of day, and the
obvious extension is to let priority do the same. Left simple until there is
evidence that the simple version is not enough.

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

`:core` has 62 JVM tests. Neither Android module has a single test. §6.5 does not
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

Two more worth naming explicitly, because they look like oversights:

- **No phone-side recording.** §8.5 defers it. The phone app has no record
  button and the manifest asks for no location permission.
- **No network permission anywhere.** NFR-7. Neither manifest declares
  `INTERNET`. If a future dependency needs it, that is a decision to take
  consciously, not a merge to wave through.
