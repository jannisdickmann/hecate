# ADR: A backup archive, and why it is a file rather than a cloud

**Date:** 2026-09-11
**Status:** Accepted — "merge by id and nothing else" narrowed for imported
sessions by A.15; "no `INTERNET` permission" no longer true of the phone since
A.29, which uses it for map data only
**ID:** A.10 (formerly SPECIFICATION.md Appendix A)

## Context

Not in the specification. §8.5 defers "export to GPX/Strava", which is about
*sharing* a workout; this is about not losing one.

The problem, traced through the code: the phone's Room database is the only
complete copy of anything. The watch deletes each session file the moment the
phone acknowledges it (US-2), so the watch is a delivery buffer, not a second
copy. Health Connect is written to and never read — the app holds three
`getWritePermission` grants and no read grant — and what it receives is lossy:
no per-sample GPS accuracy, no match log, and no route names or priorities,
since the exercise session's title is "Lauf"/"Gehen"/"Radfahren". Re-importing
Google Health's TCX export, the only recovery path there was, drops accuracy
(`TcxParser` sets it null), cannot restore what the watch decided on the day,
and re-clusters into freshly auto-named routes at the default priority. Names
and priorities are the only things in the database a human typed, and they were
the one thing nothing could bring back.

Two alternatives were measured and declined:

- **Android Auto Backup.** Its quota is 25 MB per app, and over the exact Room
  schema at 300 sessions of 45 minutes at 1 Hz the database is 145 MB — about
  51 sessions, two months, before the system stops backing up **silently**.
- **A direct Google Drive integration.** It solves the quota and would allow
  restore on demand, but needs the `INTERNET` permission NFR-7 then forbade, an
  OAuth client whose refresh tokens expire every 7 days unless the consent
  screen is published, `kotlinx-coroutines-play-services` — the dependency A.7
  records as deliberately declined — pulled in transitively through
  `play-services-auth`, and it is *less* private than Auto Backup, which is
  end-to-end encrypted with the device PIN while Drive files are not.

## Decision

**Export to a file, and restore from a file.** Both through the Storage Access
Framework, so the user chooses the location, the app needs no storage
permission, and no network is involved. Where the file goes afterwards, Google
Drive included, is the user's business and happens outside the app entirely.

The format is a ZIP: `manifest.json`, a `database.json` holding every table
except samples, and one `sessions/<id>.jsonl` per session (layout in the
README, *Backup and restore*). Same 300 sessions: about 15 MB, a tenth of the
SQLite file, because SQLite spends 36 bytes of UUID plus an index entry on
every sample row. Those session files are `SessionCodec`'s format — the watch's
own — so unzipping `sessions/` into `fixtures/` feeds the replay harness
directly, with no converter.

- **Restore merges; it never replaces.** A session or route already present by
  id is left exactly as it is and counted as skipped. Restoring into an empty
  database — the case this exists for — is therefore exact, and restoring into a
  populated one cannot destroy what is already there. The cost is that merging
  an archive into a database that already has routes keeps the local routes'
  names, since their ids are taken.
- **Restore does not go through `SessionRepository.ingest`.** Ingest would reset
  `hcWriteState` to PENDING, re-write every session to Health Connect, and
  re-derive `committedAtM` — a fact about what the watch decided on the day,
  which nothing can derive again. Rows are written back as they were.

## Consequences

Three fields cannot survive the round trip because the phone never stored them:
`SessionHeader.bundleVersion` and `appVersion` are dropped at ingest, and
`SessionEndReason` is not a column, so the exported footer says `IMPORTED` —
true of the file being read rather than a guess at how the recording ended.

`:phone` gained its first tests for this: plain JVM tests of `ArchiveCodec`,
which is the ZIP and JSON layer with no Android and no Room in it —
deliberately split out from `ArchiveRepository` so that **the part which can
silently lose data is the part that gets read back in a test**. A.12 is the
case where that rule was not followed far enough; A.15 moved the codec into
`:core`; A.16 added a Robolectric round trip through Room.

Anything that must survive a restore has to live in a table the archive
carries — which is why the peak heart rate is in `meta` (A.21) and why the
basemap is deliberately *not* in Room (A.29). That the archive has never been
restored on a real phone is open point §1.3. Archives on the development
machine live in `backup/`.
