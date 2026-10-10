# Auto-pause has never been seen to fire

**Status:** Open
**Kind:** Gap against the specification
**Discovered:** 2026-09-11
**ID:** OP-3.1 (formerly OPEN-POINTS.md §3.1)

## Problem

Spec §6.5 gives an `adb shell am broadcast` incantation for injecting
`whs.AUTO_PAUSE_DETECTED`. The code handles `ExerciseState.AUTO_PAUSED` and
records a `PAUSE_AUTO` event, but neither the broadcast nor a real auto-pause has
ever been exercised.

## Impact

Auto-pause is unverified on a path every real ride with a standstill takes. The
resume warning in
[recorder-logs-unexpected-state-on-resume.md](recorder-logs-unexpected-state-on-resume.md)
is the same gap seen from the other side.

## Workaround

Since schema v3 (spec A.12) `PAUSE_AUTO` is kept at ingest rather than dropped,
so this no longer needs the broadcast or a cable: ride, then read "Verlauf der
Aufzeichnung" on the session detail screen.

## What closes it

A `PAUSE_AUTO` with a matching `RESUME_AUTO` at a plausible offset settles it.
A ride with a genuine standstill and no such pair is the real finding.
