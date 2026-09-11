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
decision — **read it before installing anything**, in particular §1.2 on the
release keystore. All three modules build and lint clean, but nothing has ever
run on a watch, so none of the device behaviour is verified.

## Modules

| Module | What it is | Builds without an Android SDK |
|---|---|---|
| `:core` | Pure Kotlin: the matcher, clustering, profiles, TCX import, replay harness | yes |
| `:wear` | The recorder. Health Services, append-only storage, three watch screens | no |
| `:phone` | The companion. Room, ingest, Health Connect write-back, history UI | no |

`:core` has no Android dependency at all, which is enforced by its dependency
list rather than by good intentions. Everything algorithmic lives there so it
can be tested on a laptop in thirteen seconds instead of on a wrist in the rain.

## Setting up a development machine

From a clean Ubuntu install, assuming nothing but `sudo`. Everything below is
what the current build is actually verified against — no Android Studio needed,
though it will happily open the project if you want it.

**1. JDK and the basics.** Gradle 9.7 and AGP 9.4 want a modern JDK; 21 is what
this is built and tested with, and it is in Ubuntu's own repositories.

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk unzip curl git
java -version          # expect 21.x
```

**2. The Android SDK command-line tools.** No GUI installer, no login. The
nested `latest/` directory is not decoration — `sdkmanager` refuses to run
without it.

```bash
mkdir -p ~/android-sdk/cmdline-tools
cd /tmp
curl -O https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*.zip
mv cmdline-tools ~/android-sdk/cmdline-tools/latest
```

That build number ages. If the link 404s, take the current "Command line tools
only" Linux zip from <https://developer.android.com/studio#command-line-tools-only>;
nothing else about these steps changes.

**3. Put the tools on your PATH**, in `~/.bashrc` so it survives a new shell:

```bash
cat >> ~/.bashrc <<'EOF'
export ANDROID_HOME="$HOME/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
EOF
source ~/.bashrc
```

**4. Accept the licences and install the three packages this project needs.**
Nothing else is required — in particular no emulator image, which is a large
download that §6.5 explains cannot test the interesting part anyway.

```bash
yes | sdkmanager --licenses
sdkmanager --install "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
```

Platform **37** because `compileSdk = 37`, which Compose BOM 2026.09 requires.
`targetSdk` stays at 36 deliberately — see the note in `phone/build.gradle.kts`.

**5. Point the build at the SDK.** Either the `ANDROID_HOME` you just exported
is enough, or be explicit — `local.properties` is git-ignored and wins:

```bash
cd /path/to/hecate
echo "sdk.dir=$HOME/android-sdk" > local.properties
```

**6. USB access to a phone**, if you plan to plug one in. Ubuntu needs the udev
rules and your user in `plugdev`; the group change needs a logout to take
effect. Skip this if you are only using wireless debugging on the watch.

```bash
sudo apt install -y android-sdk-platform-tools-common
sudo usermod -aG plugdev "$USER"
```

**7. Check it works.** The first run downloads Gradle itself and the whole
dependency graph, so give it a few minutes:

```bash
./gradlew :core:test :phone:assembleDebug :wear:assembleDebug
```

## Building

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

Versions are pinned in `gradle/libs.versions.toml`, every one of them, with the
reasoning beside anything that is not simply the newest release. §7 of the
specification asks for upgrades to be deliberate; that file is where you make
them.

## Testing locally

Everything that can be checked without a wrist:

```bash
./gradlew :core:test                                 # 77 JVM tests, a few seconds
./gradlew :phone:testDebugUnitTest                   # 11 JVM tests of the backup archive
./gradlew :phone:lintDebug :wear:lintDebug           # both should say "No issues found"
./gradlew :phone:assembleDebug :wear:assembleDebug   # both APKs
./gradlew :phone:assembleRelease :wear:assembleRelease
```

`:core:test` is the one that matters and the one to run constantly — the matcher,
the clustering, the profile maths and the CPU budget all live there, and none of
it needs an SDK, a device or a network. A readable report lands in
`core/build/reports/tests/test/index.html`.

`:phone` has exactly one test class, over the backup archive's ZIP and JSON
layer — the one place in that module where a silent bug costs you your history.
Everything else in `:wear` and `:phone` has no test of its own (§3.2 of the open
points): lint and the compiler are the whole of the automated safety net there,
which is why both are expected to stay clean.

To re-run something Gradle thinks is already done — after changing a version, say:

```bash
./gradlew :core:test --rerun-tasks --no-build-cache
```

## Testing on the wrist

The layer nothing above can substitute for: real GNSS noise, ambient batching,
battery, and whether any of this survives an hour outdoors. No debugger survives
a run, so the method is to record, come back, and pull the files.

**Connect to the watch.** There is no USB port; it is wireless debugging only.
On the watch: Settings → Developer options → ADB debugging **on**, Wireless
debugging **on**, then "Pair new device" for the pairing port and code.

```bash
adb pair <watch-ip>:<pair-port>        # asks for the six-digit code
adb connect <watch-ip>:<debug-port>    # a different port from the pairing one
adb devices                            # expect the watch, "device", not "unauthorized"
```

Both ports change; re-read them off the watch each time. With a phone also
attached, every command below needs `-s <serial>` from `adb devices`.

**Install.** Release builds, both of them, signed with the *same* key — the
Wearable Data Layer will not deliver between differently signed apps, and
§1.3 of the open points lists that first when sessions fail to arrive. Set up
`keystore.properties` (§1.2) before the first install, not after.

```bash
./gradlew :phone:installRelease
./gradlew :wear:installRelease
```

Then grant the permissions listed under "Permissions to grant by hand" below,
launch the phone app at least once, and check the watch has received a route
bundle.

**Record something**, then get the file off the watch. Sessions are JSONL, one
line per sample, readable with `cat` — but they live in the app's private
storage, so **a plain `adb pull` will not reach them on a release build**. That
needs either a rooted watch or a debuggable build:

```bash
adb shell run-as de.hecate.wear ls files/sessions/
adb shell run-as de.hecate.wear cat files/sessions/session-<id>.jsonl > fixtures/a.jsonl
```

`run-as` only works against a debuggable APK, and swapping a release build for a
debug one is a signature change, which means an uninstall, which means losing any
session the watch has not uploaded yet (§6.3). Upload first, every time. On a
rooted watch `adb root && adb pull /data/data/de.hecate.wear/files/sessions/`
avoids the whole problem.

The phone is not a shortcut here: it ingests sessions into Room and does not keep
the raw JSONL, so the watch is the only place a fixture exists.

Those files are the fixture set §6.5 asks you to build first — work route, two
shortcuts, the reverse direction, a novel path, one with a 90 s GPS dropout, one
with a long pause — and they are what makes the replay harness below useful.
Commit the interesting ones.

**Check the watch agreed with itself.** Every decision the watch made is stored
in the session file, so a laptop can re-derive them and compare. Any difference
is a bug by definition:

```bash
./gradlew :core:run --args="bundle --sessions ./fixtures --out bundle.json"
./gradlew :core:run --args="onwrist --bundle bundle.json --session fixtures/a.jsonl"
```

**Inject an auto-pause**, which is hard to produce on purpose and easy to get
wrong (§3.1 of the open points — this has never actually been run):

```bash
adb shell am broadcast -a "whs.AUTO_PAUSE_DETECTED" com.google.android.wearable.healthservices
```

**Watch the logs live** while it is on the bench rather than on a wrist:

```bash
adb logcat -s RecorderService:* ExerciseSource:* Uploader:* BootReceiver:*
```

A Wear emulator can exercise service lifecycle, permissions and layout, but §6.5
is blunt about its limit: its synthetic data emits a single default route with
identical values every run, so it can never test matching. Use it for plumbing,
never for the matcher.

## Backup and restore

The phone's database is the only complete copy of anything you have recorded.
The watch deletes each session once the phone confirms it, Health Connect is
written to and never read back, and it never receives your route names or
priorities. So there is an export.

On the phone, sessions screen, the **⋮** menu:

- **Sicherung exportieren** — writes a ZIP wherever you point the file picker.
- **Sicherung einlesen** — reads one back.

No network is involved and no storage permission is asked for; the system file
picker hands the app one file. Putting the result on Google Drive, a laptop or
an SD card is something you do afterwards, outside the app — which is why
neither manifest declares `INTERNET` (NFR-7) and why there is no cloud backup:
see Appendix A.10 of the specification for the measurements behind that.

**Restore merges, and never replaces.** Anything already present is left exactly
as it is, so reading a backup into a database that still has data cannot cost
you anything. Into an empty database — a new phone, a reinstall — it is exact,
route names and priorities included.

Inside the archive:

```
hecate-sicherung-20260911-1432.zip
├── manifest.json          format version, export time, counts
├── database.json          every table except samples
└── sessions/<id>.jsonl    one file per session
```

Those session files are the watch's own format, so the archive doubles as a
fixture source — unzip and replay, no converter:

```bash
unzip hecate-sicherung-*.zip 'sessions/*' -d .
./gradlew :core:run --args="bundle --sessions ./sessions --out bundle.json"
```

Roughly 15 MB for 300 sessions of 45 minutes. Copying the SQLite file instead
would be 145 MB, which is why the samples live in the per-session files and not
in `database.json`.

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
