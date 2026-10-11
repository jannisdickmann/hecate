# ADR: A detour is a new route, and a recording can be a route before there are three

**Date:** 2026-10-11
**Status:** Accepted
**ID:** A.32

## Context

On 11 Oct a walk of "Abendrunde" took a 580 m excursion in the middle. The watch
did what it should: the deviation alert fired, the name went to "Neue Route",
and it came back to "Abendrunde" where the walk rejoined it. But the clusterer
filed the whole recording as an Abendrunde member. Its coverage against every
member was 0.84 — inside `CLUSTER_CUT` (0.75 needed) — because the walk
contains the whole route and the excursion is only 17 % of its own length.

A cut alone could not fix it. On the 2026-10-11 backup the walk merges at
distance 0.157 and two members of "Arbeit hin Kanal" merge at 0.151: a cut
between them would rest on 0.006. What does tell them apart is shape. The Kanal
pairs are symmetric — each takes a different 1.7–2 km branch of a 17 km
commute, about 11 % of its length. The walk is the route plus one stretch away
from it.

Separately: a new route needs `MIN_MEMBERS` (3) recordings before the watch can
match it. Someone who knows a walk will become a regular has no way to say so.

## Decision

**A detour can never merge.** `CoverageMetric.longestDetour` is the longest
interior stretch of one track off the other, as a fraction of its own points;
above `Tuning.DETOUR_MAX_FRACTION` (0.15) in either direction, `distance` is
1.0, like a reversal. Only interior stretches count: a different start or
finish is what the cut's slack is for. On the real history this changes
exactly two things: the 11 Oct walk leaves Abendrunde, and an already-inactive
pair with a 2.1 km (19 %) excursion splits. Every named route keeps its members.

**Temporary routes.** A recording outside every route can be promoted on its
detail screen ("Als vorläufige Route verwenden"; `session.temporary_route`,
schema v9). `BundleBuilder.computeRoutes(temporarySeeds = …)` turns a cluster
too small to be a route that holds a seed into a route flagged `temporary`:
geometry and profile are the earliest seed's alone, every cluster member is
filed under it, and it goes to the watch like any route. Since clusters of
three are routes already, a temporary route holds the seed and at most one
match; the third makes it a real route, which keeps the id (Jaccard
re-identification) and any name the user gave it. After every pass the worker
keeps the flag only on seeds that still head a temporary route
(`keepTemporaryRouteSeeds`), so a promotion overtaken by a real route, or by an
earlier promoted recording it matches, is gone — two temporary routes never
match each other. Withdrawing it is a button on both detail screens. The
archive carries both flags.

## Consequences

- 0.15 was set on one history. A shorter loop with a proportionally similar
  excursion, or a commute with a longer branch, would sit near it; that is
  open point §2.1 again.
- An excursion that crosses the route halfway counts as two shorter ones.
- The watch does not know a route is temporary; it shows the auto-name.
