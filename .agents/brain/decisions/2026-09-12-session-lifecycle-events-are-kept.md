# ADR: The session's lifecycle events are kept, not parsed and dropped

**Date:** 2026-09-12
**Status:** Accepted
**ID:** A.12 (formerly SPECIFICATION.md Appendix A)

## Context

Not a deviation from the specification so much as a hole in the implementation
of it, found while working out what could be debugged from a ride in the field.

`SessionEventType` has eight members — `PAUSE_MANUAL`, `RESUME_MANUAL`,
`PAUSE_AUTO`, `RESUME_AUTO`, `GPS_LOST`, `GPS_ACQUIRED`, `SERVICE_RESTARTED`,
`MATCHER_DISABLED` (A.30 later added `AUTO_STOP`) — and `RecorderService` emits
every one of them into the append-only session file as an `"e"` record, beside
the samples and the match log. `SessionCodec.decodeSession` reads them back into
`RecordedSession.events` correctly. And then the phone threw them away: there
was no table, ingest never stored them, and `ArchiveRepository` rebuilt sessions
for export without them, so `ArchiveCodec`'s events section — which has always
been written — was always empty. The moment the phone acked and the watch
deleted its copy (US-2), the events were gone.

This mattered more than it looks. The match log says what the *matcher* decided,
per sample, and that part was always well kept. The events say what happened to
the *recording*, and they are the evidence for exactly the things the open
points list as unverified: whether auto-pause fires (§3.1), what surrounds the
unexpected `USER_RESUMING` (§3.5), how often the fix is lost in real terrain, and
whether the foreground service was ever killed mid-workout. All of it was being
recorded on the wrist and discarded on arrival.

## Decision

A `session_events` table, one blob per session, schema v3. It deliberately
mirrors `match_log` rather than becoming a row-per-event table — the two are
written together, read together, and deleted together by the same
`ON DELETE CASCADE`, and a handful of events per session does not want an index.
`BundleCodec` gains the `encodeEvents`/`decodeEvents` pair beside the match
log's. Ingest writes them inside the same transaction as the samples, because
they are part of the recording rather than a commentary on it. Export reads them
back, so the archive's events section now carries what it always claimed to.

The phone also shows them. Session detail gains a "Verlauf der Aufzeichnung"
block listing each event with its offset and note — uncapped, unlike the match
log's fifty, because there are only ever a handful and the one worth seeing is
the one you did not expect to be there. Open points §3.1 and §3.5 are now
answerable from the phone with no cable at all.

## Consequences

Sessions ingested before v3 have no row and never will: the events died with the
watch's copy of the file and nothing can derive them again. The migration adds an
empty table and does not pretend otherwise.

How this stayed hidden: `ArchiveCodecTest`'s round-trip test asserted header,
footer, match log and samples, and never looked at `events` — so a field that
was always empty round-tripped as always empty and passed. The fixture now
carries four events, which puts them under every test that uses it, and two
further tests name them directly. The lesson is the one A.10 already states
about this file: the part that can lose data silently is the part that has to be
read back in a test, and **"round trip" has to mean every field, not every field
someone remembered.**
