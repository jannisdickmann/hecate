# Route priority in use

**Status:** Open
**Kind:** Needs a decision
**Discovered:** 2026-09-11
**ID:** OP-2.3 (formerly OPEN-POINTS.md §2.3)

## Problem

Priority (spec A.4b, A.31) breaks ties between routes that share the same ground:
one integer per route, −2..2 since schema v7 (spec A.21), compared only between
*contenders* joined at about the same point — a route followed since the start
always beats one the ride left and came back to. It is not context-aware; that is
settled, not pending (see `../scope.md`, OP-4.1).

## Impact

Three behaviours to watch in use:

- **It takes effect on the next workout.** Setting a priority rebuilds and pushes
  the bundle immediately; if the watch is out of range, it applies whenever the
  watch next syncs.
- **Preferring a route costs a wrong label on the days you take the other one** —
  on the shared part, until the paths part and then `FOCUS_RELEASE_M` (100 m)
  further. Ahead/behind is unaffected (it is carried across a name change, spec
  A.31), and the label is drawn as an assumption until then. The trade is right
  only if you take the preferred route most of the time.
- **With equal priorities a route is still named on shared ground** (spec A.31):
  the one already named, else the one with the most weight. Rank the variants if
  the name matters.

## Workaround

None needed; this is a watch-list for real use.
