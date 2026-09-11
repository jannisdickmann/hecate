# Hecate

A personal route-learning companion for a Pixel Watch 4 and an Android phone.
It records a workout on the watch, works out which of your learned routes you
are on while you run it, and shows you how today compares with every other time
you have done it. Single user, no cloud, no network, sideloaded.

Google Health records workouts well but never learns that today's run is last
Tuesday's run. Hecate does exactly that and not much else.

The full design is in [SPECIFICATION.md](SPECIFICATION.md); its Appendix A
records where the implementation departs from it, and why.
[OPEN-POINTS.md](OPEN-POINTS.md) lists what still needs a human, a device, or a
decision — **read it before installing anything**, in particular the parts about
the release keystore and the fact that the Android modules have not yet been
compiled.

## Modules

| Module | What it is | Builds without an Android SDK |
|---|---|---|
| `:core` | Pure Kotlin: the matcher, clustering, profiles, TCX import, replay harness | yes |
| `:wear` | The recorder. Health Services, append-only storage, three watch screens | no |
| `:phone` | The companion. Room, ingest, Health Connect write-back, history UI | no |

`:core` has no Android dependency at all, which is enforced by its dependency
list rather than by good intentions. Everything algorithmic lives there so it
can be tested on a laptop in thirteen seconds instead of on a wrist in the rain.

## Building

Needs JDK 17 or newer and an Android SDK with platform 36.

```bash
./gradlew :core:test                    # the algorithms — no SDK needed
./gradlew :phone:installRelease
./gradlew :wear:installRelease          # with the watch connected over adb
```

`:wear` and `:phone` are only configured when an Android SDK is found —
`sdk.dir` in `local.properties`, or `ANDROID_HOME` / `ANDROID_SDK_ROOT`. Without
one, Gradle says so and configures `:core` alone, so the test suite runs
anywhere. Force the matter with `-Phecate.androidModules=true|false`.

Do not ship with the debug keystore. See §6.3 of the specification and §1.2 of
the open points: a signature mismatch means an uninstall, and an uninstall means
losing any session the watch has not uploaded yet.

## The replay harness

The matcher is deterministic and clock-free, so a decision made on the wrist can
be reproduced exactly on a laptop. That is what makes tuning tractable.

```bash
# Cluster a directory of session files into a route bundle
./gradlew :core:run --args="bundle --sessions ./fixtures --out bundle.json"

# Replay one session and print the decision timeline
./gradlew :core:run --args="replay --bundle bundle.json --session fixtures/a.jsonl"

# Compare a replay against the log the watch itself stored — any difference is a bug
./gradlew :core:run --args="onwrist --bundle bundle.json --session fixtures/a.jsonl"

# Assert that ambient batching cannot change a decision
./gradlew :core:run --args="selfcheck --bundle bundle.json --session fixtures/a.jsonl"
```

Sessions live on the watch at `/data/data/de.hecate.wear/files/sessions/` and
are plain JSONL — one line per sample, readable with `cat`.

## Watch debugging

```bash
# Watch: Settings → Developer options → ADB debugging ON, Wireless debugging ON
adb pair <watch-ip>:<pair-port>
adb connect <watch-ip>:<debug-port>
adb devices
```

## Permissions to grant by hand after install

- **Watch**: location (allow all the time), heart rate, physical activity,
  notifications.
- **Phone**: Health Connect → app permissions → write exercise, exercise route,
  heart rate, distance.
- Turn **off** the watch's automatic activity detection, or Health Connect gets
  every workout twice.

## Route priority

Shortcuts and variants are independent routes that happen to share a prefix
(§8.1), and while two routes are on the same ground the matcher will not guess
between them — it shows `Suche…`, with no ETA, until the paths part. On a route
family that shares most of its length, that is most of the run.

Each route therefore carries a priority from 1 to 10, set on its detail screen.
Where several routes currently coincide and exactly one outranks the rest, the
watch assumes that one and gives you its name, remaining distance and ETA from
the start. A tie decides nothing, so leaving everything at the default 5 keeps
the cautious behaviour — the feature turns itself on when you first use it.

It is a tie-breaker and nothing more. It cannot put you on a route you are not
on: the moment the ground contradicts it, the evidence wins and the label
switches. An assumed route shows blue on the watch, a worked-out one green.

## Tuning

Every constant in the system is in `core/src/main/kotlin/de/hecate/core/Tuning.kt`,
each with its unit and the reasoning behind its value. There are no magic
numbers anywhere else. They have only ever met synthetic GPS — see §2.1 of the
open points.
