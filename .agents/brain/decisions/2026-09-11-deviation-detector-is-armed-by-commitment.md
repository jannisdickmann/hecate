# ADR: The deviation detector is armed by commitment, not gated on it

**Date:** 2026-09-11
**Status:** Accepted — arming stands; measuring against the armed route alone is
superseded by
[2026-09-11-deviation-is-measured-against-the-route-network.md](2026-09-11-deviation-is-measured-against-the-route-network.md)
**ID:** A.4 (formerly SPECIFICATION.md Appendix A)

## Context

Related to [A.3](2026-09-11-null-distance-exceeds-deviation-threshold.md) but
separate. Even with the null pushed out of the way, a sustained departure
eventually demotes the match — correctly, since the user really is no longer on
a known route. If the detector is reset on demotion, a sufficiently decisive
departure is never reported.

## Decision

Commitment *arms* the detector on that route, and it then measures the distance
to that route directly until the user is back on it or a different route is
committed. Demotion and even dropping the candidate do not disarm it.

## Consequences

This is a deliberate reading of
[US-7](../product.md#us-7--deviation-alert-must)'s intent ("if I leave the
route I am told") over its literal wording ("never fires ... while the null
hypothesis dominates"); US-7 now states the implemented behaviour.

Measuring against the armed route *alone* narrowed what [§5.6](../matcher.md#deviation)
had right, and became visible once A.4b allowed commitment inside a shared
prefix — A.4c restored the network-wide minimum.
