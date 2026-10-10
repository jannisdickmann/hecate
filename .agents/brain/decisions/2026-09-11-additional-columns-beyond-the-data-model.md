# ADR: Additional columns beyond the specified data model

**Date:** 2026-09-11
**Status:** Accepted
**ID:** A.6 (formerly SPECIFICATION.md Appendix A)

## Context

§5.5 specified the phone's Room tables. Building the screens and the Health
Connect writer showed facts the specified schema could not hold, or could only
reconstruct expensively or not at all.

## Decision

- `route_member(route_id, session_id)` — see A.5.
- `session.avg_hr` — US-8's session table needs average heart rate, and
  computing it once at ingest beats aggregating a million sample rows per view.
- `session.hc_error` — the Health Connect write status is specified; the reason
  a write failed is what makes it actionable.
- `session.ingested_at` — diagnostics.
- `session.ended_at_utc` — added in schema v2. §5.5 gives the session a start and
  an active duration, which are not enough to place its end: a session with
  pauses ends later than start plus active time. Health Connect rejects a write
  whose route points are not *strictly* inside the session — an exclusive bound —
  so the real stop time from the footer had to stop being discarded at ingest.
  Rows written before v2 keep NULL, because the value cannot be reconstructed.
- `route.name_is_custom` — distinguishes an auto-generated name, which may be
  regenerated, from one the user chose, which may not.
- `route.priority` — see A.4b, and A.21 for the rescaling to −2..2. Like the
  name, preserved across re-clustering.
- `sample.active_ms` — §5.5 stores only `t_offset_ms`. Splits, ahead/behind and
  the profile all need elapsed *active* time, which cannot be reconstructed from
  the wall-clock offset once a session contains pauses.
- `meta(key, value)` — holds the route bundle version counter (and, since A.21,
  the peak heart rate setting).
- `route_profile.median_alt_m` — the route's elevation, one median per bin (A.20).

## Consequences

The full schema history, including the tables later ADRs added
(`session_events`, `heatmap`), is in the data model section of
[architecture.md](../architecture.md#data-model-phone-room-55).

`sample.active_ms` turned out to be stored wrong by the watch for most samples
— a stale checkpoint — and is repaired on read rather than trusted (A.24, A.31).
