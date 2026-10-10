# A watch recording has no altitude

**Status:** Open
**Kind:** Gap against the specification
**Discovered:** 2026-09-15
**ID:** OP-3.11 (formerly OPEN-POINTS.md §3.11)

## Problem

Both rides in the first real backup carry no altitude on any sample, while a
takeout import of the same commute carries one on every sample.

The recorder rejects an altitude that is not finite or falls outside
`MIN_ALTITUDE_M..MAX_ALTITUDE_M` — written for the emulator's synthetic provider
sending `Double.MAX_VALUE` (spec A.18). Whether the watch sends such a sentinel,
sends nothing, or needs `DataType.ELEVATION_GAIN` requested alongside `LOCATION`
is not answerable from the data: a rejected value and an absent one look
identical once written.

## Impact

The elevation profile, ascent and descent figures and the height band —
everything spec A.20 built — are absent from a recording made on the wrist and
present on the same ground imported from Google Health.

## Workaround

None.

## What closes it

Log what `LocationData.altitude` actually contains on a real recording — one
line, one ride — then decide. First question, not last: a barometric altitude is
what makes a profile worth having, and whether this watch exposes one through
Health Services at all is unknown.
