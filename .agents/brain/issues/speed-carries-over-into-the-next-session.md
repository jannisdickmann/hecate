# The last speed carries over into the next session

**Status:** Open
**Kind:** Defect (display only)
**Discovered:** 2026-10-10, during a code cleanup
**ID:** OP-3.17

## Problem

`RecorderService.beginSession` resets every per-session "last" value — the heart
rate and its time among them — except `lastSpeedKmh` and `lastSpeedAtMs`. The
service instance outlives a session, so the second recording in one process
starts with the first one's last speed and its session offset. That offset is
large, the new session's offsets are small, and the staleness test
(`FIX_STALE_MS`) can pass: the old speed shows as current at the start of the
new session until the first new speed reading arrives.

## Impact

Display only. The speed series written to the session file is unaffected
(`lastSpeedReadingAtMs` is a different field, and it is reset).

## What closes it

Reset both fields in `beginSession` beside the heart-rate ones. Left as found
because the cleanup that found it promised no change in behaviour.
