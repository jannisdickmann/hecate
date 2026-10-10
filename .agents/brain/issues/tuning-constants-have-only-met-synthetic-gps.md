# Tuning constants have only ever met synthetic GPS

**Status:** Open
**Kind:** Needs a decision
**Discovered:** 2026-09-11
**ID:** OP-2.1 (formerly OPEN-POINTS.md §2.1)

## Problem

Every constant in `de.hecate.core.Tuning` is reasoned from the geometry of the
problem and validated against synthetic tracks. Those tracks use one noise model —
an Ornstein-Uhlenbeck process, 100 s correlation time, 4 m standard deviation —
and real GNSS is not that. Treat these as first guesses:

| Constant | Value | What to watch for |
|---|---|---|
| `GPS_SIGMA_M` | 15 m | Too low and the matcher is overconfident between buildings |
| `WEIGHT_DECAY` | 0.97 | Too high and an early mistake sticks; too low and nothing ever commits |
| `COMMIT_SEPARATION_M` | 50 m | Too high and parallel streets never separate |
| `COMMIT_MIN_M` | 400 m | Followed along the route itself (spec A.31); too low and a route's last stretch is named at the front door |
| `FOCUS_RELEASE_M` | 100 m | How long a name survives not fitting; too low and it flickers where a medoid is a street off |
| `NULL_DISTANCE_M` | 120 m | Must stay above `DEVIATION_M` (spec A.3) |
| `CLUSTER_CUT` | 0.25 | Too low and every variation is a new route; too high and shortcuts merge |
| `DEVIATION_HOLD_SAMPLES` | 15 | 15 s of alert latency; shorter risks false alarms at junctions |

## Impact

Wrong values show up as wrong names, late or false deviation alerts, and routes
that split or merge — all of it plausible-looking, none of it an error.

## Workaround

Revisit them against real recordings with the replay harness (spec US-10):

```bash
./gradlew :core:run --args="bundle --sessions ./fixtures --out bundle.json"
./gradlew :core:run --args="replay --bundle bundle.json --session fixtures/x.jsonl"
./gradlew :core:run --args="onwrist --bundle bundle.json --session fixtures/x.jsonl"
./gradlew :core:run --args="timeline --archive backup/hecate-sicherung-<stamp>.zip"
```

- `onwrist` compares the decisions the watch actually made, stored in the session
  file, against a fresh replay. Any difference is a bug by definition — for
  recordings made by the same build, with the watch on one lane (spec A.31).
  Before that, deliveries could reach the matcher out of order, and a backup
  stores the samples sorted.
- `timeline` replays a whole backup against its own routes (any `--bundle` may be
  a backup ZIP) and prints, per recording, what the watch would name along it and
  how ahead/behind behaved. It is how A.31 was measured, and the quickest way to
  see what a tuning change does to every workout.

Real fixtures are still missing — see
[fixture-set-does-not-exist-yet.md](fixture-set-does-not-exist-yet.md). The
auto-stop thresholds are the same problem with an irreversible failure; see
[auto-stop-has-never-met-a-forgotten-activity.md](auto-stop-has-never-met-a-forgotten-activity.md).
