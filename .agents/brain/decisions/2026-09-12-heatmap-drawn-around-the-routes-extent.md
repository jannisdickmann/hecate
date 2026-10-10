# ADR: A heatmap of everything, drawn around the routes' own extent

**Date:** 2026-09-12
**Status:** Accepted — the raster's cell size, counting and blur were reworked
in A.21; the extent was grown in A.22 and then split into two extents in A.23;
a basemap was put under it in A.29
**ID:** A.14 (formerly SPECIFICATION.md Appendix A)

## Context

Not in the specification. §4 rejected every maps SDK and §8.5 defers charts;
this is neither. It is the app's own tracks drawn against a ground of their own
— which for ground you have covered two hundred times is a more recognisable
picture than a street map would be, and costs no API key, no tiles and no
third-party dependency. NFR-8 is untouched.

*(Written when that ground was empty. A.29 put a dim street drawing behind it
and revised NFR-7 with it; what follows about the extent, the radius filter and
the colour is unchanged by that, because the basemap is drawn under the raster
and changes nothing about how the raster is built.)*

## Decision

**Precomputed, not drawn on demand.** The raster is O(samples) over the whole
history — the same pass the clusterer already makes over the same data — so
`ClusterWorker` builds it and stores it, and the routes screen reads a row. It
runs last in that worker, after the bundle is pushed, so a failure while drawing
can never cost the watch its routes. Schema v4 holds it as one gzipped blob:
written whole, read whole, never queried, and a raster's worth of counts as rows
would be a table nothing ever selects from. (A.21 made the raster a great deal
larger and run-length encoded the blob; everything else here still holds.)

**The extent is anchored on the routes, never on the recordings.** This is the
whole reason the feature needed thinking about. One trip to another city is a
recording, not a route, and if it got a vote on the bounding box then everything
you actually run would be squashed into a corner of the picture by a thing you
did once. So the box starts as the routes' box, padded, and is *grown* — never
cropped, never stretched. A sample outside it is dropped rather than clamped,
because clamping piles every distant point onto the border cells and draws a hot
frame around a map with nothing at its edges.

(A.22 stretched that box until it held the recordings too, because one that
stops at the routes cuts real tracks off at an invisible line. A.23 replaced
that compromise with two boxes: this one, unchanged and 16:9, for the card, and
a second covering everything inside the radius for the view that pans. This
paragraph and the next describe the card; the full-screen extent is
`Extent.EVERYTHING` and leaves nothing out at all.)

**The radius filter is about believing coordinates.** Any route or recording
with a point more than `HEATMAP_MAX_RADIUS_M` (100 km, configurable) from the
median start of all routes is left out of the drawing. A recording is in or out
whole: a track with one fix in the Gulf of Guinea is a track whose every point
is suspect, and half a recording drawn is worse than none. What it does **not**
do is delete anything — nothing leaves the database, no route stops existing,
and the caption under the picture says how many were left out so that a missing
route is never silently missing.

That median needed care, and a test caught it. It is a true order statistic and
deliberately does *not* average the two middle values the way a textbook median
does. With four routes of which two start abroad, the mean of the two middles
sits halfway to the outliers, at a place no route has ever been — and the radius
filter then rejects every route including the real ones. Taking the lower of the
two middles keeps the breakdown point at the 50% a median is supposed to have,
and guarantees the centre is somewhere a route actually started. A pleasant
consequence: the centre always lies on some route, so that route can never be
excluded by its distance from itself.

**Colour.** Density is a magnitude, so the ramp is one hue stepped light to dark
— blue, six steps of one scale — and never a rainbow, which encodes an order
nobody can read back. The scale is logarithmic: a commute ridden two hundred
times and a lane crossed twice differ by two orders of magnitude, and linearly
everything but the commute would be indistinguishable from empty. Dark mode is
its own ramp rather than an inversion, because on a dark ground "near zero" has
to recede *towards* the surface, so it runs dark to light. Empty cells are fully
transparent: no data is not the same as no activity.

**The route lines are warm, thin, and uncased**, and the first version was none
of those. It drew them as ink lines with a surface-coloured casing underneath —
the standard cartographic treatment — and rendering it showed why that is wrong
here: recordings follow routes, that being what makes them routes, so the lines
sat exactly on top of their own heat and erased it. The picture came out as
three black shapes with a faint blue fringe. A warm hue against the blue ramp
separates the two layers at every step of the scale without covering anything,
and needs no casing. The stroke is a real 1.2 dp rather than raw pixels, so it
is the same weight on every screen instead of a hairline on a dense one.

## Consequences

That was found by rendering it. `Heatmap.build` is pure JVM, so the raster and
the overlay were rasterised to PNG off-device, on synthetic data with a commute
ridden sixty times, a loop ridden twelve, a ride done twice, some wanderings
that never became routes, one holiday far outside the extent and one glitched
fix — and looked at. The wanderings are the case worth checking: they are heat
with no line on them, and they are the only place in the app where they appear
at all.

The blue ramp is also why water on the basemap is drawn in road ink rather than
blue under the heatmap (A.29). How it looks on a real phone is open point §3.8.
