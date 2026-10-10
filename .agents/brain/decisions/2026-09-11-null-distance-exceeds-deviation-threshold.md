# ADR: `NULL_DISTANCE_M` must exceed `DEVIATION_M`

**Date:** 2026-09-11
**Status:** Accepted
**ID:** A.3 (formerly SPECIFICATION.md Appendix A)

## Context

[§5.6](../matcher.md#per-sample) gives the null hypothesis a fixed
log-likelihood equivalent to `d = NULL_DISTANCE_M`, and
[US-7](../product.md#us-7--deviation-alert-must) fires the deviation alert after
sustained divergence beyond `DEVIATION_M`, "never while the null hypothesis
dominates".

If `NULL_DISTANCE_M` is below `DEVIATION_M`, these two rules cancel each other
out. Leaving the route by more than `NULL_DISTANCE_M` makes "unknown" the better
explanation, the match is demoted, and the alert is suppressed — by the very
departure it exists to report. With the originally suggested ordering (45 m null
distance, 60 m deviation threshold) the alert can never fire at all.

## Decision

`NULL_DISTANCE_M` is 120 m, comfortably above the 60 m deviation threshold, and
`MAX_ABS_LOG_LIK` is raised to 60 so that the null and a route a kilometre away
do not both clamp to the same value (which would stop the matcher ever reporting
`Unbekannt`). Both constraints are documented at the constants in `Tuning`.

## Consequences

The ordering is a constraint between two constants, not a value of either;
retuning one without the other reintroduces the cancellation. Even so, a
sustained departure eventually demotes the match — which is
[A.4](2026-09-11-deviation-detector-is-armed-by-commitment.md).
