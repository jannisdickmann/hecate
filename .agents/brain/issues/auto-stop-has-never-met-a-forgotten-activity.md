# The auto-stop has never met a forgotten activity

**Status:** Open
**Kind:** Gap against the specification
**Discovered:** 2026-09-21
**ID:** OP-3.16 (formerly OPEN-POINTS.md §3.16)

## Problem

The three rules that end a recording by themselves (spec A.30) do the two most
irreversible things in the app: they stop a workout, and they throw away its end.
Their thresholds — three quarters of an hour inside a 30 m circle, a quarter of an
hour without a usable fix, ninety seconds above the sport's ceiling — come from
the geometry, from what the two mistakes cost, and from one run over the real
history.
[tuning-constants-have-only-met-synthetic-gps.md](tuning-constants-have-only-met-synthetic-gps.md)
says the same about every other constant; these are the ones where being wrong is
visible and cannot be undone from the wrist.

**How often does it fire on workouts that were not forgotten?** The answer has to
be zero. Answer it on a laptop:

```bash
./gradlew :core:run --args="autostop --archive backup/hecate-sicherung-*.zip"
```

It prints a line per session and a count. A hike with a long lunch, a ride with a
café stop, a ski day full of lifts and a city run with poor reception are the
cases that would say otherwise, and the real history is the only place they exist.

Over the 73 sessions of the September 2026 backup, the first guess of fifteen
minutes stationary fired on ten — six of them walks and hikes ended at a break
with hours still to come (a half-hour summit rest, a 25-minute stop four hours
into a seven-hour walk). At forty-five it fires on four: three drives home, caught
by the speed rule, and one recording (gh-31923) whose 75-minute gap without
samples mid-walk is ended as Stillstand with 0.75 km of walking after it. That one
is the remaining open case: a gap that long in a Google recording is most likely a
pause the export does not mark, which a manual pause on the watch would shield.

**Does it fire when it should, on a wrist?** Nobody has left a recording on a
kitchen table for twenty minutes, or driven home with a ride running, and looked
at what came back: the `AUTO_STOP` event and its reason in session detail, the
distance and duration cut back to where the activity ended, and the positions
after it absent from the database. The replay harness reaches the same verdicts on
the same samples — the rules read sample offsets, never a clock — so the emulator
can rehearse it (`tools/emu replay` of a session that ends in a car ends the
replay), but the ninety-second watchdog and the haptic have only been read, not
felt.

## Impact

A false stop is applied at ingest, so the part before it is safe — but the watch
has *stopped recording*. Whatever the user does after a false stop is not in the
file at all unless they start a new one.

## Workaround

Until the wrist test is done, treat a stop that surprises you as a threshold to
change in `Tuning`, not as a fault.
