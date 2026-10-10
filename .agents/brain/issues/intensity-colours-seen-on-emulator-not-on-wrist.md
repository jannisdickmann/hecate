# The intensity colours have been seen on an emulator, not on a wrist-worn history

**Status:** Open
**Kind:** Gap against the specification
**Discovered:** 2026-09-15
**ID:** OP-3.9 (formerly OPEN-POINTS.md §3.9)

## Problem

Both drawings on both detail screens are coloured by effort (spec A.21). Every
part of that is arithmetic that produces a picture rather than an error: a wrong
boundary still colours something. `IntensityTest` pins the boundaries, the
saturation above the peak and the difference between "no reading" and "an easy
one"; it cannot say whether the result is legible.

Spec A.26 found what this issue could not: the colours were fine and the data was
not — the recorder kept one heart rate per delivery and threw the rest away. See
[backfilling-heart-rate-the-recorder-never-wrote.md](backfilling-heart-rate-the-recorder-never-wrote.md).

## Impact

A phone emulator with 66 real recordings answered one of three questions:

- **Whether the 15 s median is enough at a boundary.** An effort a few beats under
  80 % settles to one colour after smoothing; one balanced exactly on the line
  still alternates, because it genuinely has no band. If that reads as noise on a
  real ride, the answer is hysteresis — remember the previous band and require a
  margin to leave it — a change to `Intensity` and its tests and nothing else.
- ~~Whether the full-height backdrop is too much.~~ **Answered on the emulator:**
  yes, and worse than stripes — it coloured the sky above the line, where nobody
  was. Spec A.22 removed it; the effort is now the line and the fill under it.
- **Whether 180 bpm is a reasonable default peak.** Plausible for a fit adult in
  their thirties and nothing more. The setting is under Einstellungen → Belastung,
  with the bands listed in bpm so that a wrong default is obvious, not invisible.

Worth knowing: a route's colours come from `route_profile.median_hr`, filled
since the first schema but null for any bin whose members carried no heart rate.
A route whose recordings all predate a strap draws plain, as such a recording
does.

## Workaround

None; judge it on a wrist-worn history.
