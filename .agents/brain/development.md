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
| `:phone` JVM tests | Room migrations, ingest, backup round trip, TCX import, standout arithmetic | Robolectric ([A.16](decisions/2026-09-12-robolectric-for-the-phone.md)) — not in the original strategy |
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
  off-device, and looking at it found what no assertion would
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
