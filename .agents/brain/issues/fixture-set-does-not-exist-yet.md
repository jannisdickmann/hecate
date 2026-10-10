# The real fixture set does not exist yet

**Status:** Open
**Kind:** Needs a decision
**Discovered:** 2026-09-11
**ID:** OP-2.2 (formerly OPEN-POINTS.md §2.2)

## Problem

Spec §6.5 says to build it first: work route, two shortcuts of it, the reverse
direction, one novel path, one with a 90 s GPS dropout, one with a long pause.
All seven exist as *synthetic* geometries in `core/src/test/.../Synthetic.kt` and
the unit tests exercise them. Real recordings have started, but none of the seven
shapes exists as a real track yet.

## Impact

The golden-file regression testing spec §6.5 calls for has nothing to guard, and
the tuning constants
([tuning-constants-have-only-met-synthetic-gps.md](tuning-constants-have-only-met-synthetic-gps.md))
have nothing real to be checked against.

## Workaround

Backups in `backup/` already carry real recordings, and the laptop-side commands
(`timeline`, `autostop`) read them directly.

## What closes it

Once there are ten or so real sessions, copy them off the watch into `fixtures/`
and commit the interesting ones. Getting them off is the awkward part:

- `/data/data/de.hecate/files/sessions/` is private storage. A plain `adb pull`
  reaches it only on a rooted watch, and `adb shell run-as` only on a debuggable
  build — a signature change, and therefore an uninstall.
- `README.md`, "Testing on the wrist", has the workable spellings.
- The phone is no help: it ingests into Room and keeps no raw JSONL.
