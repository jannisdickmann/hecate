# Re-inserting a session row silently deletes its samples

**Status:** Workaround in place
**Kind:** Needs a decision
**Discovered:** 2026-09-12
**ID:** OP-3.2-trap (formerly the "Trap found while writing those tests" paragraph of OPEN-POINTS.md §3.2)

## Problem

`SessionDao.insert` is `OnConflictStrategy.REPLACE`. SQLite implements REPLACE as
DELETE then INSERT, and `sample` has `ON DELETE CASCADE` — so re-inserting a
session row to change one column silently deletes every sample it had.

## Impact

Nothing does this today, but the failure mode is quiet data loss in the one
complete copy of the history, not a crash.

## Workaround

Both callers guard with `countById` first (ingest, and the archive restore in
`ArchiveRepository`). A test pins the cascade, so the next person meets it in a
test rather than in a history.

## What closes it

Decide whether `insert` should be `ABORT` instead, which turns a future mistake
into a crash rather than into data loss.
