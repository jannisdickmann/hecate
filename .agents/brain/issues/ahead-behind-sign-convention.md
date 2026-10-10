# Ahead/behind sign convention

**Status:** Open
**Kind:** Needs a decision
**Discovered:** 2026-09-11
**ID:** OP-2.4 (formerly OPEN-POINTS.md §2.4)

## Problem

`deltaVsMedianS` is `elapsed − median`, so **positive means slower than usual**.
The watch shows `+45 s` in red and `−12 s` in green under the label `Ø`, with
nothing saying which way round it is — the captions that did went when the live
page was made dense. Runners are used to the opposite sign from race splits.

## Impact

A misread delta on the wrist reads "ahead" as "behind". Colour carries the
meaning; the sign may fight it.

## Workaround

None. To flip it, change `Format.delta` and the colour test in `LivePage`
together.
