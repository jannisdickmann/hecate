# ADR: Route identity survives re-clustering

**Date:** 2026-09-11
**Status:** Accepted
**ID:** A.5 (formerly SPECIFICATION.md Appendix A)

## Context

§5.7 recomputes clustering from scratch on each ingest, and US-3 makes routes
renameable. The specification did not say how a renamed route survives the next
re-clustering; recomputing from scratch would issue fresh ids and discard the
name.

## Decision

`RouteReconciler` matches each freshly computed cluster onto the existing
`route` rows by Jaccard overlap of their member sets, keeping the id, the
creation date and any user-given name when the overlap reaches
`ROUTE_REIDENTIFY_OVERLAP`. Routes that no longer correspond to any cluster are
deactivated rather than deleted.

## Consequences

- This needs the member set to be stored, hence the `route_member` table, which
  is not in §5.5 (A.6).
- `route.name_is_custom` separates a generated name, which may be regenerated,
  from a chosen one, which may not; `route.priority` (A.4b) is preserved the
  same way.
- Names and priorities are the only things in the database a human typed —
  which is why losing them was the argument for a backup archive (A.10).
