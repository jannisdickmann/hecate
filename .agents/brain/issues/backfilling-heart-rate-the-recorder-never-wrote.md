# Backfilling a heart rate the recorder never wrote

**Status:** Open
**Kind:** Gap against the specification
**Discovered:** 2026-09-16
**ID:** OP-3.12 (formerly OPEN-POINTS.md §3.12)

## Problem

The two rides recorded before spec A.26 carry 161 and 787 heart-rate readings
where the sensor measured thousands. Those readings are not in the files and no
processing will bring them back. Two other copies of the same rides plausibly
exist, and reading either into an existing recording is a feature that does not
exist yet:

- **Health Connect.** The phone already writes sessions there and holds the write
  permission; it has never asked for `ReadHeartRateRecord`. If Google Health or
  Fitbit syncs a continuous rate for the workout's window, filling the holes is a
  query and a merge — the merge exists (`HeartRates.merge`); the new decisions are
  which source to trust when both have a reading, and how to mark a recording
  whose colours came from somewhere else.
- **A Google takeout.** The same workout appears in the export with its own
  series, which the importer already parses. Today it skips the whole workout as
  a duplicate of the recording it already has (`isDuplicateImport`); it would have
  to learn to merge one field of a duplicate rather than dropping it.

## Impact

Those recordings draw sparse effort colours and heart-rate charts for good.

## Workaround

None.

## What closes it

Neither path is hard. Both change a recording after the fact, which the app has so
far refused to do — the file is what the watch said. The question is whether a
recording that borrows its heart rate from another app is still the recording. A
visible mark ("Puls aus Health Connect") is probably the price of doing it at all.
