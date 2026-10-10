# ADR: Speed in km/h, never pace

**Date:** 2026-09-11
**Status:** Accepted
**ID:** A.9 (formerly SPECIFICATION.md Appendix A)

## Context

US-6 described the ETA as coming from "the per-position median **pace**
profile", and the session detail screen originally showed a `Ø Pace` row in
min/km, with the per-kilometre splits in the same units. Running apps
conventionally speak pace, so that is what got built.

The user does not.

## Decision

**Every speed the UI shows is km/h, and nothing anywhere shows pace.**

- The watch's metrics page shows `10,8 km/h` where it showed `5:32 /km`.
- The phone's session detail shows `Ø Geschwindigkeit … km/h`.
- The per-kilometre splits table was headed `Kilometer-Splits (km/h)` and its
  Median / Beste / Schlechteste columns were speeds, "Beste" still meaning the
  fastest kilometre. That table has since been removed altogether (A.21); the
  rule it illustrated holds for everything that remains.

## Consequences

This is a presentation change and nothing more. The stored measurement is
unchanged — the route profile holds cumulative *time* per bin (§5.7), and
`deltaVsMedianS` is still seconds against the median (open point §2.4 has the
sign convention). Speed is derived at the formatter and never persisted, so no
database column, no bundle field and no wire constant moves, and existing
sessions need no migration.

`Tuning.ETA_RATIO_CLAMP`'s documentation still calls its `k` an
"observed-vs-median pace ratio". That is deliberate: `k` is a dimensionless
ratio internal to the ETA calculation, not a displayed quantity, and it lives in
`:core`, which has no opinion about units and is not going to acquire one.
