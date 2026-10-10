# The Health Services update callback is never actually cleared

**Status:** Open
**Kind:** Defect (suspected; unverified on device)
**Discovered:** 2026-10-10, during a code cleanup
**ID:** OP-3.18

## Problem

`ExerciseSource.setCallback` registers a fresh anonymous `ExerciseUpdateCallback`;
`clearCallback` calls `clearUpdateCallbackAsync(NO_OP)` with a different object.
Health Services removes callbacks by identity, so the clear most likely does
nothing — and the failure, if any, is swallowed by `runCatching`.

## Impact

The registration lingers until the process dies. Nothing has been seen to go
wrong because of it, and on a device that is the only test that counts
([no-instrumented-tests.md](no-instrumented-tests.md)).

## What closes it

Keep the registered instance in a field and clear that one. Left as found
because the cleanup that found it promised no change in behaviour.
