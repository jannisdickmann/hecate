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
decision — **read it before installing anything**, in particular §1.1 on the
release keystore. All three modules build and lint clean, and the system has
completed one real round trip: a Pixel Watch 4 recorded, a Pixel 8 ingested and
acknowledged, Health Connect accepted the write, and a route bundle came back.
That is one workout's worth of device behaviour verified, and no more.

## Modules

| Module | What it is | Builds without an Android SDK |
|---|---|---|
| `:core` | Pure Kotlin: the matcher, clustering, profiles, TCX import, replay harness | yes |
| `:wear` | The recorder. Health Services, append-only storage, two watch screens | no |
| `:phone` | The companion. Room, ingest, Health Connect write-back, history UI | no |

`:core` has no Android dependency at all, which is enforced by its dependency
list rather than by good intentions. Everything algorithmic lives there so it
can be tested on a laptop in thirteen seconds instead of on a wrist in the rain.

## Setting up a development machine

From a clean Ubuntu install, assuming nothing but `sudo`. Everything below is
what the current build is actually verified against — no Android Studio needed,
though it will happily open the project if you want it.

**1. JDK and the basics.** Every module declares `jvmToolchain(25)`, so 25 is
the JDK that compiles this project and Gradle will not quietly substitute
another. It is in Ubuntu's own repositories.

```bash
sudo apt update
sudo apt install -y openjdk-25-jdk unzip curl git
javac -version         # expect 25.x — javac, not java
```

Check `javac` rather than `java`, and install `-jdk` rather than `-jre`. A JRE
arriving as some other package's dependency can end up ahead of every real JDK
on the `PATH`, giving you `java` and no compiler, and the resulting Gradle
failure names a capability instead of a version: `Toolchain installation
'/usr/lib/jvm/java-25-openjdk-amd64' does not provide the required
capabilities: [JAVA_COMPILER]`. If the JDK is missing entirely, the toolchain
says so plainly — `Cannot find a Java installation … matching:
{languageVersion=25}` — and it cannot fetch one, because no toolchain download
repository is configured here. Install the package.

**2. The Android SDK command-line tools.** No GUI installer, no login. The
nested `latest/` directory is not decoration — the tools refuse to run
without it.

```bash
sudo mkdir -p /usr/local/android-sdk/cmdline-tools
sudo chown -R "$USER":"$USER" /usr/local/android-sdk
cd /tmp
curl -O https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*.zip
mv cmdline-tools /usr/local/android-sdk/cmdline-tools/latest
```

That build number ages. If the link 404s, take the current "Command line tools
only" Linux zip from <https://developer.android.com/studio#command-line-tools-only>;
nothing else about these steps changes.

The `chown` is not optional. The SDK tools write into the root of the SDK — the
accepted licences, the package index, their own bookkeeping — so a tree left
owned by `root` fails in a way that reads like something else entirely. With the
older `sdkmanager` it is a licence prompt that reappears however often you
accept it, then `Warning: Failed to read or create install properties file.` and
a silent stop. Put the SDK under `$HOME` instead if you prefer; nothing below
cares which, only that you can write to it.

**3. Put both toolchains in the environment**, in `~/.bashrc` so it survives a
new shell:

```bash
cat >> ~/.bashrc <<'EOF'
export JAVA_HOME="/usr/lib/jvm/java-25-openjdk-amd64"
export ANDROID_HOME="/usr/local/android-sdk"
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
EOF
source ~/.bashrc
```

`JAVA_HOME` here decides only which JVM the Gradle daemon itself runs on, and
Gradle 9.7 accepts anything from 17 to 26 for that. The compiler comes from the
`jvmToolchain(25)` in each module either way — the build succeeds with
`JAVA_HOME` pointing at 21 — so this line is about keeping the daemon and the
toolchain on one JVM rather than about making the build work.

**4. Install the three packages this project needs.** Nothing else is required —
in particular no emulator image, which is a large download that §6.5 explains
cannot test the interesting part anyway.

```bash
android sdk install platform-tools platforms/android-37.0 build-tools/37.0.0
```

Platform **37** because `compileSdk = 37`, which Compose BOM 2026.09 requires.
`targetSdk` stays at 36 deliberately — see the note in `phone/build.gradle.kts`.

No licence step is needed: `android sdk install` accepts the SDK licence itself
and writes it to `$ANDROID_HOME/licenses`, which is where Gradle looks later.
The `sdkmanager` incantation you may have seen — `yes | sdkmanager --licenses`
then `sdkmanager --install "platforms;android-37.0" …` — still works and takes
the same package names with `;` in place of `/`, but current cmdline-tools
deprecate that tool in favour of `android sdk` and say so on every run. Two
things to know about `android`: the first invocation downloads an ~85 MB CLI into
`~/.android/bin` and prints Google's terms plus a note that it reports usage
metrics, which `--no-metrics` on any `android` command turns off; and `android
sdk install` exits `0` even when it installed nothing, so read its output rather
than trusting the status — a mistyped package gets you `Package … not found.`
and a successful exit.

**5. Point the build at the SDK.** Either the `ANDROID_HOME` you just exported
is enough, or be explicit — `local.properties` is git-ignored and wins:

```bash
cd /path/to/hecate
echo "sdk.dir=/usr/local/android-sdk" > local.properties
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

The two `installRelease` tasks **do not exist until `keystore.properties` does**.
AGP only creates an install task for a variant it can sign, so without that file
Gradle answers `Task 'installRelease' not found in project ':phone'` — which
reads like a typo rather than a missing keystore. `installDebug` is always there;
§1.1 of the open points is why you should not reach for it.

`:wear` and `:phone` are only configured when an Android SDK is found —
`sdk.dir` in `local.properties`, or `ANDROID_HOME` / `ANDROID_SDK_ROOT`. Without
one, Gradle says so and configures `:core` alone, so the test suite runs without
an SDK installed. It still needs the plugins: the root project declares AGP, so
resolving the build script classpath reaches Google's Maven even for a
`:core`-only run. Force the matter with `-Phecate.androidModules=true|false`.

Do not ship with the debug keystore. See §6.3 of the specification and §1.1 of
the open points: a signature mismatch means an uninstall, and an uninstall means
losing any session the watch has not uploaded yet.

Versions are pinned in `gradle/libs.versions.toml`, every one of them, with the
reasoning beside anything that is not simply the newest release. §7 of the
specification asks for upgrades to be deliberate; that file is where you make
them.

## Testing locally

Everything that can be checked without a wrist:

```bash
./gradlew :core:test                                 # the algorithms, a few seconds
./gradlew :phone:testDebugUnitTest                   # the backup archive's format
./gradlew :phone:lintDebug :wear:lintDebug           # both should say "No issues found"
./gradlew :phone:assembleDebug :wear:assembleDebug   # both APKs
./gradlew :phone:assembleRelease :wear:assembleRelease
```

`:core:test` is the one that matters and the one to run constantly — the matcher,
the clustering, the profile maths and the CPU budget all live there, and none of
it needs an SDK or a device. It does need the network once, to resolve the build
script classpath: see the note under **Building**. A readable report lands in
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

**Connect both devices.** The watch has no USB port, so it is wireless debugging
or nothing. The phone can go either way: USB is faster for repeated installs of a
30 MB APK and gives a stable serial, wireless is one less cable and survives a
flaky port. The flow is identical.

The toggles are named differently on the two, which costs more time than it
should. The watch says **ADB debugging** and **Wireless debugging**; a phone says
**USB-Debugging** and **Drahtloses Debugging**, under a *Debugging* heading a long
way down Developer options. Searching the settings for `Debug` beats scrolling.
Then *Pair new device* on either, for the pairing port and a six-digit code.

```bash
adb pair <ip>:<pair-port>       # asks for the six-digit code
adb connect <ip>:<debug-port>   # a different port from the pairing one
adb devices -l                  # both "device", not "unauthorized"
```

Both ports change on every reboot; re-read them off the device each time. A
wireless serial is the unlovely `adb-<serial>-XXXXXX._adb-tls-connect._tcp`, and
**every** command below needs it after `-s`. With two devices attached nothing is
implicit, and the phone module's `minSdk 34` is perfectly installable on an API
36 watch — so an unpinned install will cheerfully put the phone app on your
wrist. Tell them apart without guessing:

```bash
adb -s <serial> shell getprop ro.build.characteristics    # "nosdcard,watch" is the watch
```

**Install.** Release builds, both of them, and note that both modules share the
`applicationId` `de.hecate` — that is not tidiness, it is what makes the Data
Layer work at all (§1.2). Set up `keystore.properties` (§1.1) before the first
install, not after.

```bash
adb -s <phone> install -r phone/build/outputs/apk/release/phone-release.apk
adb -s <watch> install -r wear/build/outputs/apk/release/wear-release.apk
```

`./gradlew :phone:installRelease` works too, but the APKs are already built by
then and `adb` is explicit about which device it is talking to.

Confirm the two carry one signature, which is the other Data Layer requirement
and the first thing §1.2 tells you to check:

```bash
apksigner verify --print-certs phone/build/outputs/apk/release/phone-release.apk
apksigner verify --print-certs wear/build/outputs/apk/release/wear-release.apk
```

**Grant the permissions** (§6.4) from here rather than tapping through them all
on a 480×480 screen:

```bash
for p in ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION \
         ACTIVITY_RECOGNITION POST_NOTIFICATIONS ; do
  adb -s <watch> shell pm grant de.hecate android.permission.$p
done
adb -s <watch> shell pm grant de.hecate android.permission.health.READ_HEART_RATE
for p in WRITE_EXERCISE WRITE_EXERCISE_ROUTE WRITE_HEART_RATE WRITE_DISTANCE ; do
  adb -s <phone> shell pm grant de.hecate android.permission.health.$p
done
adb -s <phone> shell pm grant de.hecate android.permission.POST_NOTIFICATIONS
```

An adb grant is indistinguishable from a tapped one. Check what took:

```bash
adb -s <serial> shell dumpsys package de.hecate | sed -n '/runtime permissions:/,/^$/p'
```

The one thing that cannot be done from here is turning **off** the watch's own
automatic activity detection, which otherwise files a second session for the same
workout.

**Watch a session cross.** Both apps log in German under their own tags, and a
healthy round trip reads like this across the two devices:

```bash
adb -s <watch> logcat -s RecorderService:* Uploader:* BundleStore:* PhoneListener:*
adb -s <phone> logcat -s IngestService:* SessionRepository:* HealthConnectWriter:* BundlePusher:*
```

```
watch  Uploader: Sitzung <id> angeboten (2435 B gzip)
phone  IngestService: Sitzung <id> bestätigt
watch  Uploader: Sitzung <id> bestätigt und gelöscht
watch  BundleStore: Neues Routen-Bundle gespeichert: Version 4
```

The watch's home screen says `Keine Routen` until that last line, then `Routen vN`.
If the phone side stays silent, look for `W WearableService: Failed to deliver
message to AppKey[<hidden#…>,<cert-sha1>]` in the *phone's* log: a hashed package
name there means the Data Layer is looking for an app that is not installed, which
is what a mismatched `applicationId` looks like from the outside. A named package
in that warning — Fitbit's, say — is routine noise and not about you.

**Record something**, then get the file off the watch. Sessions are JSONL, one
line per sample, readable with `cat` — but they live in the app's private
storage, so **a plain `adb pull` will not reach them on a release build**. That
needs either a rooted watch or a debuggable build:

```bash
adb -s <watch> shell run-as de.hecate ls files/sessions/
adb -s <watch> shell run-as de.hecate cat files/sessions/session-<id>.jsonl > fixtures/a.jsonl
```

`run-as` only works against a debuggable APK, and swapping a release build for a
debug one is a signature change, which means an uninstall, which means losing any
session the watch has not uploaded yet (§6.3). Upload first, every time. On a
rooted watch `adb -s <watch> root && adb -s <watch> pull /data/data/de.hecate/files/sessions/`
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
adb -s <watch> shell am broadcast -a "whs.AUTO_PAUSE_DETECTED" com.google.android.wearable.healthservices
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

Sessions live on the watch at `/data/data/de.hecate/files/sessions/` and
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
