# ADR: Commitment is gated on geometry, not only on weights

**Date:** 2026-09-11
**Status:** Accepted — the separation rule stands; how a route is chosen among
contenders is superseded by
[2026-10-10-one-named-route-and-everything-read-off-it.md](2026-10-10-one-named-route-and-everything-read-off-it.md)
**ID:** A.2 (formerly SPECIFICATION.md Appendix A)

## Context

[§5.6](../matcher.md#commitment) committed when the top weight held above
`COMMIT_WEIGHT` for `COMMIT_HOLD_SAMPLES` samples.
[US-4](../product.md#us-4--the-current-route-is-identified-during-the-activity-must)
additionally requires that "commitment must not occur while two or more routes
remain plausible; a shared start segment leaves the display in `Suche…` for as
long as the paths coincide".

Weights cannot deliver that second requirement, and the reason is not a tuning
problem. Where two routes share a street they fit every sample equally well, so
the ratio of their weights measures the last minute's GNSS bias and nothing
else. GNSS error is strongly correlated over a minute or more, so that bias
holds steady for long enough to satisfy any consecutive-sample test one cares to
write. On the fixture set the matcher committed to the wrong branch 1.5 km
before the paths diverged.

## Decision

Commitment additionally requires every other live, tracking candidate to be at
least `COMMIT_SEPARATION_M` further from the current position than the leader
is. While a rival is still on the same ground, nothing is decided — which is
what US-4 asks for, stated in the units the question is actually about.

## Consequences

- On a route family sharing most of its length, the watch decides nothing for
  most of the workout. That is correct and unhelpful, and is what
  [A.4b](2026-09-11-manual-route-priority.md) (priority) and later
  [A.31](2026-10-10-one-named-route-and-everything-read-off-it.md) (seniority)
  answer.
- "Within `COMMIT_SEPARATION_M` of the nearest" became the definition of a
  *contender*, used by A.4b, [A.27](2026-09-19-the-watch-says-what-it-knows.md)
  and A.31.
- A.31 found the weaker half of this: once the tie ended, weight still chose
  between branches, which is GNSS noise again. Weight is now the last
  tie-breaker, not the first.
