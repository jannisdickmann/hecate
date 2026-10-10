# Bundle size on the watch

**Status:** Open
**Kind:** Needs a decision
**Discovered:** 2026-09-11
**ID:** OP-2.8 (formerly OPEN-POINTS.md §2.8)

## Problem

A route bundle is gzipped JSON with every route's geometry at 10 m spacing plus
its profile. Fifty 10 km routes is roughly 50 000 points — a few hundred
kilobytes gzipped — and the whole bundle is deserialised into memory on the watch
at session start.

## Impact

Comfortable today. If the route list grows past what spec §8.3 contemplates, this
is what complains first.

## Workaround

None needed.
