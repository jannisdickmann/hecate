# A parallel street 80 m away will match a learned route

**Status:** Open
**Kind:** Needs a decision
**Discovered:** 2026-09-11
**ID:** OP-2.5 (formerly OPEN-POINTS.md §2.5)

## Problem

With `NULL_DISTANCE_M` at 120 m (spec A.3), a path running 80 m from a known route
still scores better than "unknown", so it is reported as that route — while the
deviation alert fires, since 80 m is above `DEVIATION_M`.

## Impact

Arguably the right behaviour ("you're doing the work route, but you're off it"),
but untried in a real street grid, where routes 80 m apart are common.

## Workaround

None. Watch for it; `NULL_DISTANCE_M` is the knob, and it must stay above
`DEVIATION_M`.
