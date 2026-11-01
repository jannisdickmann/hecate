# Development

The reasoning behind how Hecate is built, tested and installed. Formerly §6 of
`SPECIFICATION.md`. The commands themselves are in
[`README.md`](../../README.md) and are not repeated here.

---

## Prerequisites (§6.1)

A JDK 25 (the `jvmToolchain` every module declares; app bytecode stays at 17),
the Android SDK command-line tools, a Pixel Watch 4 with Developer options, and
an Android phone paired to it. Android Studio is optional. Step by step, with
the failure modes of each step: README, *Setting up a development machine*.

## Watch debugging (§6.2)

The watch has no USB port; wireless debugging is the only way in, and both its
ports change on every reboot. With two devices attached nothing is implicit,
and the phone APK (`minSdk 34`) installs happily on an API 36 watch — so every
command names its device. README, *Testing on the wrist*.

## Signing and install (§6.3)

Do **not** ship with the debug keystore. Create one release keystore, back it up
outside the machine, and always install with it — a lost key means a signature
mismatch, an uninstall, and the loss of any un-uploaded session on the watch.
That is why `installRelease` exists only once `keystore.properties` does, and
why `run-as` (which needs a debuggable APK) is never an option on a real watch.
The keystore is the one thing with no second copy:
[issues/release-keystore-has-no-second-copy.md](issues/release-keystore-has-no-second-copy.md).

Both modules share the `applicationId` `de.hecate` and must carry one
signature: that is what makes the Data Layer deliver at all.

Release builds run with `isMinifyEnabled = false`. Every unused class or icon
in a dependency ships, which is why `material-icons-extended` was declined and
icons are single vector drawables
([A.11](decisions/2026-09-12-material-3-and-one-live-watch-page.md),
[A.13](decisions/2026-09-12-settings-panel-back-stack-and-standout-marks.md)).

## Permissions to grant manually after install (§6.4)

- Watch: `ACCESS_FINE_LOCATION` (allow all the time),
  `android.permission.health.READ_HEART_RATE`, `ACTIVITY_RECOGNITION`,
  `POST_NOTIFICATIONS`
- Phone: Health Connect → app permissions → write exercise, exercise route,
  heart rate, distance
- Turn **off** the watch's automatic activity detection to avoid duplicate
  sessions in Health Connect.

The `adb` commands and the check of what took are in README, *Testing on the
wrist* and *Permissions to grant by hand after install*. Why heart rate is
checked after every install: on Wear OS 6 at target SDK 36, `BODY_SENSORS` is
no longer a runtime permission at all, requesting it is a silent no-op, and a
refused heart rate once took GNSS and a 44-minute run with it
([A.17](decisions/2026-09-12-a-missing-permission-must-not-cost-the-recording.md)).
Health Connect can be switched on and off from the app's settings, but never
granted automatically — Android health permissions go through a system-owned
sheet ([A.13](decisions/2026-09-12-settings-panel-back-stack-and-standout-marks.md)).

## Test strategy (§6.5)

| Layer | What it covers | How |
|---|---|---|
| `:core` unit tests | metric, clustering, projection, profile maths, archive format, takeout import, auto-stop, basemap codec, CPU budget | JUnit, synthetic geometries (5° divergence, self-intersecting loop, injected jitter) |
| `:phone` JVM tests | Room migrations, ingest, backup round trip, TCX import, standout arithmetic, weather backfill | Robolectric ([A.16](decisions/2026-09-12-robolectric-for-the-phone.md)) — not in the original strategy |
| Phone layout | every phone screen, light and dark, as a picture | `ScreenshotTest`: the real composables rendered under Robolectric native graphics with `androidx.compose.ui:ui-test-junit4`, written to `phone/build/screenshots` — see *Verifying a layout off-device* below ([A.33](decisions/2026-10-13-weather-and-a-phone-app-to-read-it-in.md)) |
| Replay harness | matcher decisions on real tracks | `ReplayMain` over a fixture set of stored sessions, golden-file assertions |
| Wear emulator | service lifecycle, permissions, UI layout, Ongoing Activity | Emulator synthetic data — plumbing only; it emits a **single default route** with identical values every run, so it can never test matching |
| Replay on the emulator | what the watch *shows* while matching: commitment, deviation, ahead/behind | `tools/emu replay` — a stored session fed to the recorder in place of the sensors, so the decisions are the matcher's own ([A.28](decisions/2026-09-13-replaying-an-activity-into-the-recorder.md)). Debug builds only; writes nothing, uploads nothing |
| Event injection | auto-pause handling | `adb shell am broadcast -a "whs.AUTO_PAUSE_DETECTED" com.google.android.wearable.healthservices` (never yet run — open point §3.1) |
| On-wrist | real GNSS noise, ambient batching, battery | Log to file, `adb pull` afterwards; no debugger survives a run |

**Fixture set (build this first):** work route, two shortcuts of it, the reverse
direction, one novel path, one with a 90 s GPS dropout, one with a long pause.
It does not exist yet (open point §2.2); a backup archive's `sessions/` are the
same format and stand in for it, and real backups live in `backup/`.

Lessons the layers have taught, each with its record:

- **Read back every field.** A round-trip test that does not assert a field
  passes while that field is always empty
  ([A.12](decisions/2026-09-12-session-lifecycle-events-are-kept.md)).
- **A migration is a string until a database at the old version runs it**, and
  a wrong one stops the app from starting on the user's history
  ([A.16](decisions/2026-09-12-robolectric-for-the-phone.md)). Tests must
  replay the app's own migration list, not a copy
  ([A.20](decisions/2026-09-13-altitude-profiles.md)).
- **An optimisation nobody can see the result of will be quietly broken** —
  test the indexed path against brute force
  ([A.19](decisions/2026-09-13-five-bugs-the-emulators-found.md)).
- **Pure functions for things a screenshot cannot catch** — a wrong zoom
  centre, a wrong percentile
  ([A.23](decisions/2026-09-15-two-heatmap-rasters-and-one-chart-composable.md),
  [A.13](decisions/2026-09-12-settings-panel-back-stack-and-standout-marks.md)).
- **Render it.** `:core` drawing code can be rasterised to PNG or SVG
  off-device, and so can every phone screen (`ScreenshotTest`); looking at it
  found what no assertion would — a label wrapping mid-word, a section title
  said twice, a heading that did not fit
  ([A.14](decisions/2026-09-12-heatmap-drawn-around-the-routes-extent.md),
  [A.29](decisions/2026-09-20-osm-basemap-under-every-track.md)).
- **Anything that talks to `com.google.android.gms`, `androidx.health` or
  `javax.xml` is unverified until it has run on the device** (open point §3.2),
  and a failure that reaches only logcat is a failure nobody finds — the
  watch's main log ring is 64 KiB, about a minute
  ([A.17](decisions/2026-09-12-a-missing-permission-must-not-cost-the-recording.md)).
- **Print the counts.** A number in the log is what exposed a race that had
  been discarding recordings
  ([A.26](decisions/2026-09-16-heart-rate-is-its-own-series.md)).

## Where shared code lives

A cleanup on 2026-10-10 replaced the copies across the three modules with one
shared version each, and removed dead code and unused dependencies, without
changing behaviour (the line count came out about even: each shared helper now
carries the reasoning its copies did not). The copies were what makes the next change
go wrong: a column added to three of the four sample mappings, a backup that
writes a session file's lines in a different order from the watch. Rules it left
behind:

- **One expression, one place, in the lowest module that can hold it.** If the
  phone and the watch both need it and it has no Android in it, it is `:core`:
  `display/Units` (distance, duration), `Geo.kmh`, `HeadingUp.signedDelta`,
  `Altitudes.isPlausible`. Each app's `Format` keeps only what genuinely differs
  between the two screens (a unit on its own line, a dash below 1 km/h).
- **The phone's row mapping is `db/SessionRows.kt`.** `SampleEntity.toSample()`,
  `Sample.toEntity()`, the merged samples, the blob writes, the blob decode and
  the start-time duplicate test are used by ingest, restore, clustering, Health
  Connect, GPX and the detail screen. `HecateViewModel.loadSession` keeps its own
  sample mapping on purpose: it carries the smoothed heart rate and drops
  accuracy.
- **What must stay duplicated, and why:**
  - `Wire` in `:wear` and `:phone` — `:core` must not know about transports.
    Nothing checks the two copies against each other except a session arriving.
  - `ArchiveRepository`'s archive↔entity mappers — hand-written so that renaming
    a column cannot silently change the backup format.
  - Room migrations — frozen as written; a "cleanup" there is a schema change.
  - `TakeoutImport.HC_WRITE_STATE_SKIPPED` mirrors the phone's enum, which
    `:core` cannot see.
  - Near-copies that differ in a way that matters: `Standouts.percentile` and
    `ProfileBuilder.quantile` interpolate differently; the local-hour arithmetic
    in `ClusterWorker` uses `%` where `RouteMatcher.contextKeyOf` uses `floorMod`;
    the two XML escapers and the three nearest-heart-rate lookups break ties
    differently. Unifying any of them changes results.
- **When factoring a `try` into a helper, compare what each `try` covers** (only
  the open, or the open plus every read plus the close) and the order of the
  messages the user sees. Three CLI call sites stayed as they were for that
  reason (`replay/CliInputs`).
- **A shared gzip/codec helper takes a lambda, not a finished String**, so that
  encoding happens inside the stream's `use` and an encode failure still closes
  the caller's stream (`io/GzipText`).
- **`shl 32 or` equals `shl 32 xor` only while the low half is masked** — keep the
  mask inside `geo/cellKey`.
- **Kotlin overloads erase:** `Polyline.of(List<LatLon>)` cannot gain a
  `List<RoutePoint>` overload, hence `Polyline.fromRoutePoints`.
- **Room accepts a `private const val` template inside `@Query`**, which is how
  the `SessionSummary` column list is written once.
- **A Compose helper that returns a `Modifier` should not be `@Composable`**
  (Compose lint flags it); return a value such as a colour instead.
  `HecateIntensityColors.of` must stay non-composable because band colours are
  looked up inside `remember {}` and Canvas draw blocks.
- **Per-session fields in `RecorderService` must be reset in `beginSession`**: the
  service instance outlives a session
  ([issues/speed-carries-over-into-the-next-session.md](issues/speed-carries-over-into-the-next-session.md)).
- **Versions come from CI, not from the build scripts.** `versionCode` is the
  workflow's run number (`HECATE_VERSION_CODE`), `versionName` `1.0.<code>`;
  a local build is 1. Code that needs the version reads it from the package
  (`de.hecate.wear.AppVersion`, `UpdateRepository.installedPhone`) — `:wear`
  builds no `BuildConfig`
  ([A.34](decisions/2026-11-01-updates-from-github-releases.md)).
- **Dead dependencies ship.** Release builds are not minified, so an unused
  library is APK weight: `lifecycle-service`, Compose `ui-tooling(-preview)`,
  `wear-tooling-preview` and `:wear`'s own serialization plugin and dependency
  were removed (`:wear` compiles against `:core`'s `@Serializable` models
  without them). There is no `@Preview` anywhere in the project.

### Building in a cloud sandbox

Without a local Android Studio, the whole project builds and tests in a fresh
Linux container: a JDK 25 (Adoptium), the command-line tools with
`platforms;android-37.0` (`android-37` alone is not a package name),
`sdk.dir` in `local.properties`, and Gradle with
`-Dorg.gradle.java.installations.paths=<jdk25>`. Run once without `-q` to see
compiler warnings.

Maven Central rate-limits (HTTP 429) a cold build from a shared egress, and a
429 is a hard failure — Gradle does not fall through to the next repository.
What worked on 2026-10-13, as `~/.gradle/init.d/mirror.gradle.kts`:

```kotlin
val mirror = "https://maven-central.storage-download.googleapis.com/maven2/"
fun RepositoryHandler.mirrorFirst() {
    // Google first: AGP's aapt2 is only there, and asking Central for it first
    // is a 429 that fails the build.
    val g = google { name = "googleFirst"; content {
        includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*"); includeGroupByRegex("androidx.*") } }
    remove(g); add(0, g)
    val r = maven { name = "gcsMirror"; url = uri(mirror) }
    remove(r); add(1, r)
}
settingsEvaluated {
    pluginManagement.repositories.mirrorFirst()
    dependencyResolutionManagement.repositories.mirrorFirst()
}
allprojects {
    // Robolectric downloads android-all at test time, from inside the test JVM:
    // a -D on the Gradle command line never reaches it.
    tasks.withType<Test>().configureEach {
        systemProperty("robolectric.dependency.repo.url", mirror)
    }
}
```

`maven(url)` already adds the repository, so it is removed and re-added at the
front rather than added twice ("a repository with that name already exists").

### Verifying a layout off-device

`phone/src/test/.../screenshots/ScreenshotTest` renders every phone screen to
PNG on the JVM — no emulator, no phone:

```
./gradlew :phone:testDebugUnitTest --tests '*ScreenshotTest*'
# → phone/build/screenshots/*.png
```

How it works, and why it is shaped this way:

- **The external package** is `androidx.compose.ui:ui-test-junit4`
  (`testImplementation`, versioned by the Compose BOM) plus
  `ui-test-manifest` (`debugImplementation`, the activity the rule hosts
  compositions in). Both are AndroidX and test-only; nothing ships in a
  release. Rendering is Robolectric's `@GraphicsMode(NATIVE)`;
  `onRoot().captureToImage()` is written out with `Bitmap.compress`. No
  Roborazzi or Paparazzi: that would be a dependency outside AndroidX for what
  forty lines do ([A.7](decisions/2026-09-11-dependencies-outside-androidx.md)).
- **Screens take state, not the view model.** Each screen is a thin
  `XScreen(vm)` that collects flows and a `XContent(state, actions)` that
  draws; the harness calls the `Content`. A screen that reads the view model
  inside its drawing cannot be rendered here — keep the split when adding one.
- **Synthetic data, no randomness** (`Synthetic.kt`): a 10 km loop at Hanau
  with a hill, surges, a stop at a light and a shower; sixty history rows;
  three heatmap routes. Same pictures every run, so two runs can be compared.
  The recording detail goes through `SessionDetailBuilder` — the app's own
  assembly, not a hand-made `SessionDetail`.
- **Size and theme come from Robolectric qualifiers.** `w400dp-h2900dp-xxhdpi`
  captures a scrolling screen whole, as a page; `h860dp` is a phone. Dark is
  `-night-`, and it must stand *before* the density (`…-h2900dp-night-xxhdpi`),
  or the qualifier parser throws.
- **The bars are a stand-in.** `HecateApp`'s scaffold needs a live view model,
  so the test draws the same `MediumTopAppBar` / `NavigationBar` around the
  real panel content.
- **Time:** animations run on the test clock (`mainClock.advanceTimeBy`), but
  work on `Dispatchers.Default` — the heatmap raster — does not; the harness
  sleeps briefly for it, and the raster still did not reliably appear.

What it cannot show: **no basemap** (tiles live in `filesDir/karte/` on the
phone, and `AppGraph` is not initialised), so maps are tracks on blank ground;
dynamic colour is Robolectric's default palette, not a wallpaper's; touch
behaviour — scrubbing, scrolling, the full-screen map — is not exercised beyond
`initialCursorMs`, which pre-places the cursor for one picture. A layout seen
here is a layout, not a verified screen (open point §3.2 still applies).

## Build order (§6.6)

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

The code for every milestone is complete; the exit criteria are field
measurements and remain open — see [scope.md, *Scope not reached*
(A.8)](scope.md#scope-not-reached-a8).
