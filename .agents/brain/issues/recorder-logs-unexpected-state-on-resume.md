# The recorder logs an unexpected state on resume

**Status:** Open
**Kind:** Gap against the specification
**Discovered:** 2026-09-11
**ID:** OP-3.5 (formerly OPEN-POINTS.md §3.5)

## Problem

Pausing a recording by hand and resuming it produced, once, on a Pixel Watch 4:

```
W RecorderService: Unerwarteter Zustand: USER_RESUMING
```

The session was unharmed — it recorded, stopped cleanly, transferred, and reached
Health Connect with its pauses intact. But `USER_RESUMING` is a state Health
Services does emit and `RecorderService` does not expect, on a path every real run
takes.

## Impact

Until explained, treat pause and auto-pause as unverified;
[auto-pause-broadcast-test-has-not-been-run.md](auto-pause-broadcast-test-has-not-been-run.md)
is the same gap from the other side.

## Workaround

The warning itself is still logcat-only, and logcat will not survive a long ride.
What is durable is the context: schema v3 keeps the `PAUSE_MANUAL`/`RESUME_MANUAL`
pair (spec A.12), so afterwards you can tell whether the resume took effect — a
`RESUME_MANUAL` at the expected offset, and samples that stop being `paused`
after it — without having caught the line live.

## What closes it

Reproduce it deliberately, then decide: a missing branch, or a state genuinely
safe to ignore.
