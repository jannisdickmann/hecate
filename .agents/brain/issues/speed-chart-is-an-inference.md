# The speed chart is an inference, not a measurement

**Status:** Open
**Kind:** Gap against the specification
**Discovered:** 2026-09-15
**ID:** OP-3.10 (formerly OPEN-POINTS.md §3.10)

## Problem

Spec A.23 added speed and heart rate against time to both detail screens. Heart
rate is a column being drawn. Speed is a derivative of the positions, and every
decision behind it is visible only as a shape.

Spec A.24 found the first thing this predicted: the chart was fine on imports and
nonsense on watch recordings, because the clock underneath it was. What follows
is what is left.

## Impact

- **Whether smoothing to the drawn scale hides something worth seeing.** A long
  recording is median-filtered at the width of one drawn point before thinning
  (spec A.25), so a seven-hour walk is a line rather than a solid band. A
  sustained effort survives a median and a one-second excursion does not — the
  intent — but the width follows the *duration*, so the same sprint is drawn on a
  40-minute ride and smoothed away on a six-hour one.
- **Whether 15 s of rolling median is right for a recording.** Narrower and one
  wandering fix becomes a sprint; wider and a real sprint becomes the average of
  its surroundings. `Tuning.SPEED_MEDIAN_WINDOW`; a redraw, nothing stored.
- **Whether 150 m is the right baseline for a route.** Per-bin median times are
  monotone only on average, and differencing neighbours produced 4418 km/h on the
  first real route. `ROUTE_SPEED_WINDOW_BINS` fixed it and gave 40.8 km/h — a
  bicycle downhill, but unchecked against what that descent actually is. A route
  with a genuinely fast short stretch would say whether the window is too wide.
- **Whether a route's *typical* speed is a chart anybody wants.** It is the median
  of every ride there, so it is nobody's ride. It may be the most useful chart on
  the screen — it says where the route is slow — or a shape with no reader.

## Workaround

None; judge it on long and fast real rides.
