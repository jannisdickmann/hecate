# No instrumented tests — platform behaviour is found by wearing it

**Status:** Workaround in place
**Kind:** Gap against the specification
**Discovered:** 2026-09-11
**ID:** OP-3.2 (formerly OPEN-POINTS.md §3.2)

## Problem

`:core` carries the algorithms, the archive format and the takeout import.
`:phone` runs its Android-side code under Robolectric —
`./gradlew :phone:testDebugUnitTest`, about twenty seconds, no device — covering
Room and every migration, ingest, the backup round trip and the TCX import.
`:wear` has no tests, and nothing anywhere is instrumented.

Still unreachable from a laptop: Compose UI, the Wearable Data Layer, real
permissions, Health Connect, and a genuine `content://` Uri from the file picker
(the archive tests substitute `file://`, which exercises every line of the app's
code and says nothing about whether the picker's Uri opens for writing).

Spec §6.5 does not ask for instrumented tests — it asks for emulator and
on-wrist verification, both manual. Reasonable for a single-user sideloaded app,
but every regression in that list will be found by wearing it.

## Impact

The record so far. The first session on real hardware produced three defects with
the suite green throughout — the exact shape of what it cannot see:

- **The Data Layer never delivered anything.** No JVM test can observe
  `applicationId` scoping between two devices (see
  [watch-phone-link-has-no-retry-but-the-next-workout.md](watch-phone-link-has-no-retry-but-the-next-workout.md)).
- **Every Health Connect write was rejected** with `route can not be out of
  parent time range`. The end time came from the last sample, and Health Connect
  requires route points *strictly* before the end (`location.time.isBefore(endTime)`).
  Ingest had been discarding the footer's real `endedAtUtcMs`; the column now
  exists (schema v2) and the writer uses it.
- **Every TCX import failed** before parsing a byte.
  `DocumentBuilderFactory.setXIncludeAware` throws
  `UnsupportedOperationException` on Android and works on the desktop JVM, so
  `TcxParserTest` passed while the phone refused every file. The XML hardening is
  now best-effort with an `EntityResolver` doing the work, and a test pins the
  security property rather than the mechanism.

All three lived in platform behaviour: cross-device delivery, a vendor library's
validation, a JDK-versus-Android API difference. The replay harness is excellent
at the algorithms and structurally blind to all of them. **Anything that talks to
`com.google.android.gms`, `androidx.health` or `javax.xml` is unverified until it
has run on the device.**

Two more from the first long run on a wrist (spec A.17):

- **The heart-rate permission had never been granted, and could not be.** The app
  asked for `BODY_SENSORS`, which Wear OS 6 no longer tracks as a runtime
  permission for an app targeting SDK 36; the grant it wants is
  `health.READ_HEART_RATE`. Health Services rejects an exercise configuration as
  a whole when it names a data type the caller may not collect, so the missing
  grant cost the whole exercise — GNSS was never switched on for 44 minutes,
  which `dumpsys location` showed plainly. Nothing on the wrist said so: the
  service ran, the notification said "recording", and `PREPARING` had no exit but
  a fix that could not come.
- **Three empty sessions had stranded themselves.** Ingest refuses a session with
  no samples, a refusal was never acknowledged, so the watch kept the file and
  re-offered it after every reboot while the data item sat on the phone.

And a sixth, of a different kind: **`MIGRATION_4_5` named a column that does not
exist** — property `tzOffsetMinutes`, column `tz_offset`, SQL
`tz_offset_minutes`. Room runs pending migrations on first open, so every start
of the new build crashed. Raw SQL is a string until something executes it; this
is what bought Robolectric. `MigrationTest` builds each exported schema version,
runs the real migration list against it, and fails in a second on that typo.
Unlike the others, this one was reachable from a laptop all along and simply had
nothing looking.

## Workaround

- Robolectric for everything below the platform line.
- `tools/emu replay` plays a stored activity into the recorder, so Compose, the
  match display and the deviation haptic can be watched against a real track at
  the desk (spec A.28). Still a human looking at a screen, not a test.
- **Raise the log ring before any run you might want to diagnose**, on both
  devices. The watch's main ring is 64 KiB — perhaps a minute of Wear OS chatter —
  which is why the two wrist defects were found with `dumpsys`, not logcat. It
  resets on reboot; the persistent property needs root.

  ```bash
  adb -s <device> shell logcat -G 8M
  ```

A related trap found while writing these tests is its own issue:
[session-insert-replace-deletes-samples.md](session-insert-replace-deletes-samples.md).
