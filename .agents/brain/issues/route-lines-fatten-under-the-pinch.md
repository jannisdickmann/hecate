# Route lines still fatten under the pinch; the basemap does not

**Status:** Open
**Kind:** Gap against the specification
**Discovered:** 2026-09-20
**ID:** OP-3.14 (formerly OPEN-POINTS.md §3.14)

## Problem

In the full-screen heatmap the basemap is built for the pinched viewport and its
strokes are divided by the zoom, so a 1 dp road stays 1 dp at 8×. The route lines
over the raster ride inside the `graphicsLayer` and are eight times thicker at
full zoom, as they always have been.

## Impact

An inconsistency inside one picture. Cosmetic; the fat lines have never been
complained about.

## Workaround

Left alone deliberately — spec A.29 changed enough at once without also changing
how existing lines behave.

## What closes it

Move the route drawing out of the scaled layer the same way the basemap was. It
changes a picture that currently works, so make it on its own.
