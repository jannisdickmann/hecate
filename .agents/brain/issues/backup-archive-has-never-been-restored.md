# The backup archive has never been restored on a device

**Status:** Open
**Kind:** Blocking
**Discovered:** 2026-09-11
**ID:** OP-1.3 (formerly OPEN-POINTS.md §1.3)

## Problem

Export to a file and restore from a file, both through the system file picker
(spec A.10): the sessions screen menu has "Sicherung exportieren" and "Sicherung
einlesen". No cloud, no network, no storage permission — spec NFR-7 is untouched,
and moving the file anywhere is the user's business outside the app.

It exists because the phone's database is the only complete copy of anything.
The watch deletes each session on acknowledgement, Health Connect is write-only
and lossy, and a TCX re-import cannot bring back route names or priorities.

**Verified, without a device:**

- `ArchiveCodec`, the ZIP and JSON layer, in `:core` (spec A.15) —
  `./gradlew :core:test`: a full session round trip, nullable sample fields, many
  sessions addressed by id, a large archive whose last entry must still be
  complete, a future format version, a ZIP that is not a Hecate archive,
  unreadable JSON, unknown fields from a future version.
- `ArchiveRepository`, under Robolectric
  ([no-instrumented-tests.md](no-instrumented-tests.md)): three sessions and two
  named routes exported and restored into an empty database — the names and
  priorities come back, the samples come back, and `committedAtM` is carried
  rather than recomputed. Restoring over a populated database changes nothing
  already there.

**Not verified — everything that needs a device:** the Storage Access Framework,
which the tests replace with a `file://` Uri. That a picked `content://` Uri
really opens for writing, that the suggested filename survives, and that a large
archive does not time out on a real phone. Everything on this side of that Uri is
covered.

Decided while building it, both in spec A.10: restore **merges** rather than
replaces, so it can never destroy what is there; and it writes rows directly
rather than through `SessionRepository.ingest`, so `hcWriteState` and
`committedAtM` survive. Spec A.15 narrows the first: a session whose source is an
import may also be skipped for looking like a workout already in the database,
because a takeout contains the watch's own log of workouts Hecate recorded and no
id relates the two copies.

## Impact

The backup is the only protection for the one complete copy of the data, and its
device half is untested. A backup that has never been restored is a hope.

## Workaround

None. The archives in `backup/` (`hecate-sicherung-*.zip`) exist and are read
fine by the laptop-side tools, which share the codec.

## What closes it

Do both of these:

1. **Export, wipe the app's data, restore, and check the route names and
   priorities came back.** A test does that against an in-memory database; nobody
   has done it to a real one on a phone.
2. **Restore a takeout import** — a better and a worse test: 60 sessions and no
   routes, names or priorities at all, so it exercises the session path and the
   clustering after it while saying nothing about the part of the format that
   exists for names and priorities. See
   [takeout-import-output-never-restored.md](takeout-import-output-never-restored.md).
