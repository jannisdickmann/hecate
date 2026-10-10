# ADR: The deviation alert is about the route network, not one route

**Date:** 2026-09-11
**Status:** Accepted
**ID:** A.4c (formerly SPECIFICATION.md Appendix A)

## Context

A.4 armed the detector on the committed route and measured against that route
alone. With A.4b making commitment possible inside a shared prefix, the
consequence became visible: commit to the long route, take a learned shortcut,
and the watch buzzes "Abweichung" for the twenty seconds it takes the shortcut
to commit — an alarm for doing something entirely familiar.

## Decision

§5.6 already had this right — "min distance across live candidates" — and A.4
had narrowed it. Restored: the detector measures the minimum across the armed
route and every live, tracking candidate.

## Consequences

Leaving one known route for another is not leaving the route. The alert is
about the network of learned routes; arming (A.4) still decides *whether* it
can fire, this decides *what* it measures against.
