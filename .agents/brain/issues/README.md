# Issues

What still needs a human, a device, or a decision before Hecate can be trusted
with a workout. One file per issue; the `OP-n.m` IDs are the section numbers of
the retired `OPEN-POINTS.md`.

## Status as of 2026-10-10

All three modules build and lint clean, and the system has completed real round
trips: a Pixel Watch 4 recorded, a Pixel 8 ingested and acknowledged, Health
Connect accepted the write, and a route bundle came back. The first session alone
found three defects the JVM suite could not see
([no-instrumented-tests.md](no-instrumented-tests.md)) — the honest measure of how
much device behaviour is still unverified. Algorithms are well covered on a
laptop; anything that crosses the platform line is verified only by wearing it.

## Blocking — what can still cost data

- [OP-1.1 The release keystore has no second copy](release-keystore-has-no-second-copy.md)
- [OP-1.2 The watch↔phone link has no retry but the next workout](watch-phone-link-has-no-retry-but-the-next-workout.md)
- [OP-1.3 The backup archive has never been restored on a device](backup-archive-has-never-been-restored.md)

## Needs a decision

- [OP-2.1 Tuning constants have only ever met synthetic GPS](tuning-constants-have-only-met-synthetic-gps.md)
- [OP-2.2 The real fixture set does not exist yet](fixture-set-does-not-exist-yet.md)
- [OP-2.3 Route priority in use](route-priority-in-use.md)
- [OP-2.4 Ahead/behind sign convention](ahead-behind-sign-convention.md)
- [OP-2.5 A parallel street 80 m away will match a learned route](parallel-street-matches-a-learned-route.md)
- [OP-2.6 Clustering is O(n²) and recomputed on every ingest](clustering-is-quadratic-and-recomputed-on-every-ingest.md)
- [OP-2.7 targetSdk trails compileSdk deliberately](target-sdk-trails-compile-sdk-deliberately.md)
- [OP-2.8 Bundle size on the watch](bundle-size-on-the-watch.md)
- [OP-2.9 The two emulators are not paired](emulators-are-not-paired.md)
- [OP-3.2-trap Re-inserting a session row silently deletes its samples](session-insert-replace-deletes-samples.md)

## Gaps against the specification

- [OP-3.1 Auto-pause has never been seen to fire](auto-pause-broadcast-test-has-not-been-run.md)
- [OP-3.2 No instrumented tests — platform behaviour is found by wearing it](no-instrumented-tests.md)
- [OP-3.2b The takeout import has run, but its output has never been restored](takeout-import-output-never-restored.md)
- [OP-3.3 Battery has not been measured](battery-has-not-been-measured.md)
- [OP-3.4 Ambient batching has not been observed](ambient-batching-has-not-been-observed.md)
- [OP-3.5 The recorder logs an unexpected state on resume](recorder-logs-unexpected-state-on-resume.md)
- [OP-3.6 The dense live page has been measured, not seen](dense-live-page-measured-not-seen.md)
- [OP-3.7 The standout threshold has been raised once, on one history](standout-threshold-raised-once-on-one-history.md)
- [OP-3.8 The heatmap has been seen on an emulator, not on a phone](heatmap-seen-on-emulator-not-on-phone.md)
- [OP-3.9 The intensity colours have been seen on an emulator, not on a wrist-worn history](intensity-colours-seen-on-emulator-not-on-wrist.md)
- [OP-3.10 The speed chart is an inference, not a measurement](speed-chart-is-an-inference.md)
- [OP-3.11 A watch recording has no altitude](watch-recording-has-no-altitude.md)
- [OP-3.12 Backfilling a heart rate the recorder never wrote](backfilling-heart-rate-the-recorder-never-wrote.md)
- [OP-3.13 The basemap has been seen on an emulator, not on a phone in daylight](basemap-seen-on-emulator-not-in-daylight.md)
- [OP-3.14 Route lines still fatten under the pinch; the basemap does not](route-lines-fatten-under-the-pinch.md)
- [OP-3.15 Overpass is a volunteer service, and a full prefetch leans on it](overpass-is-a-volunteer-service.md)
- [OP-3.16 The auto-stop has never met a forgotten activity](auto-stop-has-never-met-a-forgotten-activity.md)

## Defects found by reading the code

Found during the 2026-10-10 cleanup and left as found, because that cleanup
promised no change in behaviour. Each is a small fix.

- [OP-3.17 The last speed carries over into the next session](speed-carries-over-into-the-next-session.md)
- [OP-3.18 The Health Services update callback is never actually cleared](exercise-callback-is-never-cleared.md)
- [OP-3.19 `tools/emu log` shows only crashes](emu-log-shows-only-crashes.md)

Things deliberately not done (former OP-4) are scope decisions, not issues; they
live in `../scope.md`.
