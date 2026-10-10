# The dense live page has been measured, not seen

**Status:** Open
**Kind:** Gap against the specification
**Discovered:** 2026-09-12
**ID:** OP-3.6 (formerly OPEN-POINTS.md §3.6)

## Problem

The Material 3 rework replaced the Live and Route pages with one page carrying
eight values (spec A.11). Whether all eight are *readable* at speed is the only
question that matters about it, and it cannot be answered at a desk.

Checked: both modules compile and lint clean, and the layout was sized against
the type scale rather than guessed. Wear Material 3 sets `numeralSmall` at 30 sp,
`labelMedium` at 18 sp and `bodyExtraSmall` at 12 sp; the six rows plus spacers
come to roughly 175 dp, against 204 dp on a 41 mm face less about 26 dp for
`TimeText`. It fits, with more room on a 45 mm face.

## Impact

Not checked: how it looks on glass, in daylight, on a moving bike. Look for:

- **Whether the secondary pair is too small.** Heart rate and delta are 18 sp
  against 30 sp for distance and speed. The delta is the app's whole point, and
  it was given colour instead of size on the theory that red-versus-green reads
  faster than another large numeral. If that is wrong: raise both to
  `numeralSmall` and drop the ETA line, the least urgent row.
- **Whether the round face crops the first and last rows.** Route name and status
  line sit in the narrow bands at top and bottom — single lines with horizontal
  padding, but padding chosen by arithmetic, not by looking.
- **Whether it ever scrolls.** It should not. If it does on the watch in hand,
  the budget is wrong and a row has to go — the point of the change was to stop
  hands leaving the bars.

Cheap to check at the same time: `TimeText` now draws a clock the app never had,
and the `EdgeButton` on the control page is positioned by the scaffold, not by
this code.

## Workaround

None; look at it on a wrist.
