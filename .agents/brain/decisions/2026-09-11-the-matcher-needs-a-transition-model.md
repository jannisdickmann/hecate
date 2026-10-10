# ADR: The matcher needs a transition model, not only an emission model

**Date:** 2026-09-11
**Status:** Accepted
**ID:** A.1 (formerly SPECIFICATION.md Appendix A)

## Context

[§5.6](../matcher.md) step 2 described the projection window as
"±`SEARCH_WINDOW_M` around the predicted position", and said this "keeps loops
and out-and-backs unambiguous, since position advances monotonically".

A symmetric window does not make position advance monotonically. It lets the
projection move backwards by up to `SEARCH_WINDOW_M` per sample, which at 1 Hz
is far more than anyone travels in a second. The consequence is specific and
fatal to [US-3](../product.md#us-3--routes-are-learned-from-history-must): a
route's own reverse occupies exactly the same ground, so a forward run tracks
the reverse route perfectly, just with the position counting down. Tightening
the window until it is narrower than the per-sample travel is not a fix either —
the travel per sample differs by a factor of six between walking and cycling,
and goes to zero whenever the user stops.

## Decision

- The window is asymmetric: `[position − BACKTRACK_SLACK_M, position + travel +
  SEARCH_WINDOW_M]`.
- The per-sample log-likelihood gains a transition term, `−(advance − travel)² /
  (2 σ_adv²)`, where `advance` is how far the projection moved along the route.
  A route being run the wrong way round advances by minus the travelled
  distance, and pays for it on every single sample.
- Each candidate carries a smoothed `advance / travel` ratio. About 1 means
  "being followed", about −1 means "being followed backwards", about 0 means
  "left behind". A candidate below `MIN_TRACKING_RATIO` is not treated as being
  on the route at all.

## Consequences

- Direction sensitivity is a property of the matcher, not only of the
  clusterer. "Tracking" — ratio above `MIN_TRACKING_RATIO` — became the
  vocabulary every later rule is written in
  ([A.2](2026-09-11-commitment-is-gated-on-geometry.md),
  [A.4c](2026-09-11-deviation-is-measured-against-the-route-network.md),
  [A.31](2026-10-10-one-named-route-and-everything-read-off-it.md)).
- `BACKTRACK_SLACK_M` is small (10 m), which is what makes a ski lift ride back
  up a piste look like a long run backwards
  ([A.18](2026-09-13-hiking-and-skiing-and-exercise-types-as-enums.md)).
