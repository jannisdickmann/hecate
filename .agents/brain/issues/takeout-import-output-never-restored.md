# The takeout import has run, but its output has never been restored

**Status:** Open
**Kind:** Gap against the specification
**Discovered:** 2026-09-12
**ID:** OP-3.2b (formerly OPEN-POINTS.md §3.2b)

## Problem

`./gradlew :core:run --args="takeout"` has been run against one real 273 MB
Google Takeout export (in `takeout/`; the command's output archives land in
`takeout/parsed/`): 429 exercises in, 60 sessions out, which cluster into 4 routes
on a laptop through the same `bundle` command the replay harness uses. Unit tests
cover the CSV reader, the offset handling, heart-rate matching, all three
duplicate mechanisms and a split export, against fixtures shaped like the real
files.

**The archive it produces has never been read by the phone.** It is written by
the same `ArchiveCodec` the tests round-trip, so the risk is not in the bytes —
it is the restore path on a device, which is the whole of
[backup-archive-has-never-been-restored.md](backup-archive-has-never-been-restored.md).

## Impact

Only visible after a real restore:

- Whether 60 sessions of ~3000 samples each cluster in tolerable time on the
  phone rather than a laptop. `ClusterWorker` is O(n²) over sessions
  ([clustering-is-quadratic-and-recomputed-on-every-ingest.md](clustering-is-quadratic-and-recomputed-on-every-ingest.md))
  and had never had more than a handful.
- Whether the routes the phone derives are the four the laptop derived. They
  should be — same code — but "should be" is what the restore issue exists to
  distrust.
- Whether the auto-detected activities let in (three rides here) are real rides
  or artefacts worth excluding with `--no-auto`.

## Workaround

None; restore one on a phone and compare.
