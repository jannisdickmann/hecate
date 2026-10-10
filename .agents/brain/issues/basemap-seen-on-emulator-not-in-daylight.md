# The basemap has been seen on an emulator, not on a phone in daylight

**Status:** Open
**Kind:** Gap against the specification
**Discovered:** 2026-09-20
**ID:** OP-3.13 (formerly OPEN-POINTS.md §3.13)

## Problem

What the OpenStreetMap ground under the track drawings (spec A.29) has been put
through:

- `karteinfo --svg` renders the store from its decoded tiles with the user's
  recordings in red over it — coverage and alignment, answered correctly on a real
  fetch of the Regnitz valley north of Erlangen.
- The codec, viewport query, label placer, coverage clusterer, fetch planner and
  level-of-detail table are unit-tested; import, delete, a corrupt import and an
  empty store are tested under Robolectric.
- **On the Pixel 8 emulator, against the real history**: a route drawing, the
  heatmap card and the full-screen heatmap all draw the map, light and dark, with
  the track aligned and the level of detail visibly different between coarse and
  close. The on-demand fetch filled twenty-five tiles by itself, silently, while
  the heatmap was open — the only part of the network path a test cannot reach.

That run found two defects no test would have: unreadable place labels, because
the halo was measured with the stroke baked into its `TextStyle`, which fattens
the glyphs rather than outlining them; and a card saying "1 Gebiete".

## Impact

Three things only a real screen can judge, each with a specific way of being
wrong:

- **Whether "dim" is dim enough in daylight.** The palette is one neutral ink from
  0.40 down to 0.10 alpha, chosen against a track at full opacity. It reads right
  on an emulator in both themes — but an emulator is a monitor. At midday with the
  brightness up the whole map may vanish; in a dark room a street grid at 0.23
  may compete with the track. The alphas are all in `BasemapColors` in
  `Theme.kt` and should be expected to change after the first walk outside — as
  should the 11 sp place names, never read at arm's length in the sun.
- **Whether the pinch holds 60 fps across a zoom-key crossing.** The scene is
  rebuilt six times over the 1…8 range, off the main thread, each a few thousand
  `lineTo` calls. The risk is a hitch when a key changes. If it stalls, the fix is
  local: split each class's `Path` by tile inside the scene builder.
- **Whether the map lines up with a recording made on the ground it draws.** The
  SVG shows alignment at the scale of a region; a systematic ten-metre offset
  would be invisible there and obvious on a route drawing of one street.

## Workaround

None; take the phone outside.
