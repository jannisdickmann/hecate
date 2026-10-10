# The standout threshold has been raised once, on one history

**Status:** Open
**Kind:** Gap against the specification
**Discovered:** 2026-09-13
**ID:** OP-3.7 (formerly OPEN-POINTS.md §3.7)

## Problem

The recordings list marks a recording whose speed or average heart rate is at or
above the 85th percentile for its route (spec A.13). The arithmetic is tested —
twelve cases in `StandoutsTest`, including that each route ranks against itself
and that missing heart rates do not drag the threshold down — but with invented
numbers. The only question that matters is whether the marks are *useful*.

The threshold was 0.75 and was raised to 0.85 by spec A.21: one judgement against
one history, where a quarter of a sixty-ride commute carried a mark and the
emphasis had stopped reading as emphasis.

## Impact

Watch for:

- **Whether one in seven is now too few.** 0.85 marks roughly the best in seven;
  on a route with a dozen recordings that is two rows. If the mark feels absent
  rather than rare, the constant is `Standouts.PERCENTILE` and nothing else moves.
- **Whether four members is too few.** At exactly four, only the best recording
  clears the threshold, and it changes every time a faster one arrives — arguably
  correct (a personal best against the route), but a brand-new route immediately
  marks one of its own recordings, which may read as a claim rather than a fact.
- **Whether the two marks can be told apart at a glance** (device only). Primary
  colour for speed, tertiary for heart rate, plus bold weight and an upward arrow;
  both colours come from the wallpaper under dynamic theming. The arrow is the
  same on both, so a wallpaper whose primary and tertiary land close together
  leaves weight and the unit suffix doing all the work.

## Workaround

None; judge it in use.
