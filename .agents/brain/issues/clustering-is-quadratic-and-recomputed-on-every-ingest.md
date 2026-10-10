# Clustering is O(n²) and recomputed on every ingest

**Status:** Open
**Kind:** Needs a decision
**Discovered:** 2026-09-11
**ID:** OP-2.6 (formerly OPEN-POINTS.md §2.6)

## Problem

Spec §5.7 calls this fine for a few hundred sessions and defers incremental
clustering (spec §8). The coverage metric is the expensive part and is
accelerated by a bucket index, but `ClusterWorker` also reloads every sample of
every session from Room on each run.

## Impact

At one workout a day, fine for a year. A takeout restore jumps straight to dozens
of sessions on the phone
([takeout-import-output-never-restored.md](takeout-import-output-never-restored.md)).

## Workaround

None needed yet.

## What closes it

Measure once there are two hundred sessions. If it hurts, cache the resampled
tracks rather than reaching for incremental clustering.
