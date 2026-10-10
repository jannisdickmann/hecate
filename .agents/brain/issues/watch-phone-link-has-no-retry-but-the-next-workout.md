# The watch↔phone link has no retry but the next workout

**Status:** Workaround in place
**Kind:** Blocking
**Discovered:** 2026-09-11
**ID:** OP-1.2 (formerly OPEN-POINTS.md §1.2)

## Problem

Delivery works — sessions cross, are acknowledged, and the bundle comes back.
The diagnosis that got it there is in the build scripts (why both modules share
`applicationId = "de.hecate"`) and in `README.md`, "Testing on the wrist". First
contact exposed two gaps that are still open:

- **The phone never asks for a resend.** `PATH_RESEND` is defined in both `Wire`
  objects and handled on the watch — `PhoneListenerService` calls
  `uploadPending()` — but nothing in `phone/` ever sends it. The recovery path is
  dead code.
- **Nothing reconciles missed items.** The phone acts only on the live
  `onDataChanged` callback and never scans for `DataItem`s that arrived while it
  was unbound.

## Impact

A session whose delivery event is missed is re-offered only on a watch reboot or
at the end of the *next* recording. The system's only retry is another workout.

Neither gap can be exercised off the wrist: the emulators are not paired
([emulators-are-not-paired.md](emulators-are-not-paired.md)), so both are dead
code until something delivers a `DataItem`.

## Workaround

The watch keeps each session until it is acknowledged, exactly as designed, so
neither gap has cost data — they cost latency.

## What closes it

The phone sending `PATH_RESEND` (on start, or on noticing a gap), and a scan of
existing `DataItem`s when the listener binds.
