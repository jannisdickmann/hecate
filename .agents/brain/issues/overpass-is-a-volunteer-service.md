# Overpass is a volunteer service, and a full prefetch leans on it

**Status:** Workaround in place
**Kind:** Gap against the specification
**Discovered:** 2026-09-20
**ID:** OP-3.15 (formerly OPEN-POINTS.md §3.15)

## Problem

„Karte vorladen" over the whole history is about forty Overpass requests. If all
three instances are down, nothing arrives — the price of this data source, not a
bug to be fixed.

Two smaller simplifications under the same heading:

- **Nothing warns that a two-year-old tile is two years old.** Deliberate: a nag
  about scenery would be worse than stale scenery. The per-tile timestamp is
  stored, so a refresh is possible when wanted; `karte --refresh` is the
  laptop-side way.
- **Water relations are not resolved.** A large lake is an OSM multipolygon and
  member ways are treated as independent rings, so a large lake may show a seam
  and an island renders as water. Acceptable on a dim backdrop; recorded so it
  arrives as a known simplification rather than a bug report.

## Impact

A prefetch can stall for as long as the public instances are unavailable.

## Workaround

The client is polite by construction — one request at a time, two seconds apart,
`Retry-After` honoured, backoff on 429 and 504, three instances tried in turn —
and the job is resumable, because every completed tile is on disk before the next
request goes out.
