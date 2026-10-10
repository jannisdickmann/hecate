# The heatmap has been seen on an emulator, not on a phone

**Status:** Open
**Kind:** Gap against the specification
**Discovered:** 2026-09-15
**ID:** OP-3.8 (formerly OPEN-POINTS.md §3.8)

## Problem

The picture above the routes list (spec A.14, rebuilt in A.21, corrected in A.22)
was first checked by rasterising it off-device — `Heatmap.build` and
`Heatmap.blur` are pure JVM — against synthetic data; tests pin the extent, the
radius filter, the cell size, the line drawing, the blur and which way up the
raster is. It has since been looked at on a phone emulator with 66 real
recordings and four real routes, in both themes, which found the two faults A.22
fixes: an extent that cut a fifth of the sample points off at an invisible line,
and a bottom step of the colour ramp that could not be seen.

## Impact

What is left, each a judgement only a phone or more histories can make:

- **Whether the log scale separates the right things.** On this history a lane
  crossed once and a commute ridden forty times are both visible and plainly
  different — the whole requirement. Two orders of magnitude between them may
  want the other direction. One line in `rasterOf`.
- **Whether 15 m cells and σ = 1.5 are the right pair** — meant to make a track
  as wide as the uncertainty about where it was, roughly 22 m of ground. Too wide
  and two streets a block apart merge; too narrow and it is a line drawing again.
  They hold at 20 × 20 km on the emulator; a denser town would break them. Two
  constants in `Tuning` and a redraw, not a re-clustering.
- **Whether the reach is the right bound.** `HEATMAP_EXTENT_REACH` is one
  route-span, which here puts a ride 17 km from home outside the picture and says
  so in the caption. Too far and what you do daily is squashed to fit something
  done once; too near and the caption keeps mentioning recordings nobody can find.
- **What the cap costs.** Past about 35 × 20 km of extent the cell doubles, and
  nothing has been looked at in that state. A history with two homes is the case —
  also the case where the picture is of two towns, so coarser cells may be right.
- **Whether full-screen is usable one-handed and the pinch feels right.** Pinch to
  8×, drag to pan, tap to close. The gesture holds the ground under the fingers
  (spec A.23) and `ViewportTest` proves the arithmetic, but a pinch cannot be
  injected into these emulators: `adb shell input` has no second finger, and
  SELinux refuses `sendevent` on `/dev/input/*` on a production system image,
  which every Play-enabled AVD is. Clamp, anchoring and feel have been reasoned
  and unit-tested, never felt. **First thing to try on the phone.**
- **Whether the two extents are the right two.** The card is the routes' box;
  full-screen is everything inside the radius (spec A.23). With a second home 60 km
  away the full picture becomes two towns and a great deal of nothing, at a
  doubled cell size. Nobody has seen that.
- **Load time.** The bitmap is built on `Dispatchers.Default`; the blur alone is
  about 300 ms on a laptop at the cap, so the card may show its empty extent for a
  visible moment on a large history. If that reads as a bug rather than loading,
  the answer is probably a placeholder, not a faster blur.

Undecided, and only answerable with a real history: **should the extent follow
the routes of the selected activity type?** A 60 km cycling route and a 5 km
running loop in one picture makes the loop a smudge. The recordings list has an
activity filter (spec A.21); the routes list has none, and this is why it might
want one.

## Workaround

None; look at it on a phone.
