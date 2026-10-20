# Hecate

A personal route-learning companion for a Pixel Watch 4 and an Android phone.
It records a workout on the watch, works out which of your learned routes you
are on while you run it, and shows you how today compares with every other time
you have done it. Single user, no cloud, no network, sideloaded.

Google Health records workouts well but never learns that today's run is last
Tuesday's run. Hecate does exactly that and not much else.

Since A.29 one thing does leave the phone, on request: a bounding box, to fetch
the OpenStreetMap streets drawn under your tracks. Since A.33 a second does:
dates and 2 km squares, to fetch each recording's weather from Open-Meteo
(switchable in Settings). The watch stays offline.

The full design is in [SPECIFICATION.md](SPECIFICATION.md); its Appendix A
records where the implementation departs from it, and why.
[.agents/brain/issues/](.agents/brain/issues/README.md) lists what still needs a
human, a device, or a decision — **read it before installing anything**, in
particular OP-1.1 on the
[release keystore](.agents/brain/issues/release-keystore-has-no-second-copy.md). All three modules build and lint clean, and the system has
completed one real round trip: a Pixel Watch 4 recorded, a Pixel 8 ingested and
acknowledged, Health Connect accepted the write, and a route bundle came back.
That is one workout's worth of device behaviour verified, and no more.

## Modules

| Module | What it is | Builds without an Android SDK |
|---|---|---|
| `:core` | Pure Kotlin: the matcher, clustering, profiles, TCX and takeout import, the backup format, replay harness | yes |
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

### Builds from CI

Every pull request runs `.github/workflows/android.yml`: all unit tests, lint,
and release builds of both apps, with the phone screenshots and the APKs kept
as workflow artifacts. Every push to `main` also publishes a GitHub release,
`hecate-v1.0.<run>`, with `hecate-phone-….apk` and `hecate-wear-….apk`; the run
number is the version code of both. Install the watch APK over adb as under
*Testing on the wrist*, and let the watch upload first.

The release keystore reaches CI as four repository secrets (Settings → Secrets
and variables → Actions), the same names Nexus uses:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 hecate-release.jks` — the existing keystore, never a new one |
| `ANDROID_KEYSTORE_PASSWORD` | `storePassword` from `keystore.properties` |
| `ANDROID_KEY_ALIAS` | `keyAlias` (`hecate`) |
| `ANDROID_KEY_PASSWORD` | `keyPassword` |

Without them CI still tests and builds, but the APKs are unsigned and no
release is published. A local build is version code 1, so once a CI build is
installed, build with `HECATE_VERSION_CODE` above its run number or Android
refuses the downgrade.

Versions are pinned in `gradle/libs.versions.toml`, every one of them, with the
reasoning beside anything that is not simply the newest release. §7 of the
specification asks for upgrades to be deliberate; that file is where you make
them.

## Testing locally

Everything that can be checked without a wrist:

```bash
./gradlew :core:test                                 # the algorithms, a few seconds
./gradlew :phone:testDebugUnitTest                   # Room, migrations, ingest, backup
./gradlew :phone:lintDebug :wear:lintDebug           # both should say "No issues found"
./gradlew :phone:assembleDebug :wear:assembleDebug   # both APKs
./gradlew :phone:assembleRelease :wear:assembleRelease
```

`:core:test` is the one that matters and the one to run constantly — the matcher,
the clustering, the profile maths, the archive format, the takeout import and the
CPU budget all live there, and none of it needs an SDK or a device. It does need
the network once, to resolve the build script classpath: see the note under
**Building**. A readable report lands in `core/build/reports/tests/test/index.html`.

`:phone:testDebugUnitTest` runs on the JVM too, under Robolectric, in about
twenty seconds. Robolectric is what makes Room, WorkManager and the
`ContentResolver` reachable from a laptop, which is the difference between "the
backup has never been restored" and four tests that restore one. What it covers:

| | |
|---|---|
| `MigrationTest` | every exported schema version, migrated to the current one through the real migration list |
| `SessionRepositoryTest` | ingest — idempotency on the session id, the start-time duplicate rule, cascades |
| `ArchiveRepositoryTest` | export → restore, including that route names and priorities come back |
| `TcxImporterTest` | US-9 end to end, time zones included |
| `StandoutsTest` | the one piece of arithmetic in the phone UI |

What it does not cover, and what §3.2 of the open points is still about: Compose
UI, the Wearable Data Layer, real permissions, a real `content://` Uri from the
system file picker, and `:wear` entirely. Lint and the compiler are the whole of
the safety net there, which is why both are expected to stay clean.

A migration is the sharpest case for having these at all. Its SQL is a string
until something runs it, and Room runs pending migrations the first time the
database is opened — so a mistyped column name is not a bug that shows up
somewhere, it is an app that will not start, on a database that already has the
user's history in it. `MigrationTest` builds the database at each old version
from `phone/schemas/`, runs the real migrations, and reads the rows back.

To re-run something Gradle thinks is already done — after changing a version, say:

```bash
./gradlew :core:test --rerun-tasks --no-build-cache
```

## Replaying an activity on the emulator

The Wear emulator's synthetic provider emits one default route with identical
values every run, so the one thing it could never show is the part of this app
that is worth looking at: the screen finding a route and settling on it. A replay
fixes that. A stored activity is played into the recorder in place of the
sensors, and everything downstream — the matcher, the metrics, the deviation
haptic, the ongoing notification — runs exactly as it does on a wrist, because
none of it is told which of the two it is running in.

It writes nothing and uploads nothing. A replay produces no session file and
never offers anything to the phone, so it cannot reach your history, your routes
or Health Connect. The receiver that starts one exists only in debug builds.

**1. Put the routes there.** The watch matches against the bundle it holds, and
on a wrist the phone sends it one. The two emulators are not paired, so it has to
be put there by hand — without it every replay says "Unbekannt", correctly:

```bash
./gradlew :core:run --args="bundle --sessions ./fixtures --out bundle.json"
tools/emu bundle bundle.json
```

**2. Choose an activity by what it does**, not by the date on it:

```bash
tools/emu replay --list --archive ~/hecate-sicherung-20260911-1432.zip --bundle bundle.json
tools/emu replay --list --sessions ./fixtures --bundle bundle.json
```

**3. Replay it**, from a file or straight out of a backup, at ten times speed:

```bash
tools/emu replay fixtures/commute.jsonl 10
tools/emu replay --archive ~/hecate-sicherung-20260911-1432.zip a3f91b2c 10
tools/emu replay --stop
```

The watch app comes to the front, the Live page opens with `Wiedergabe ×10` under
the numbers, and the route name appears when the matcher commits — for a commute
that is usually a few hundred metres in, which at ×10 is about twenty seconds of
watching. Pause and stop work as they always do.

**Speed changes the wait and nothing else.** The matcher never reads a clock and
its hold windows are counted in samples rather than seconds, so a replay at ×10
commits on exactly the sample a replay at ×1 does, and on the sample the watch
did on the day. The one figure that is not faithful is the predicted arrival
time, which is "now plus the seconds remaining" and at ×10 those are real
seconds. The route name, the ahead/behind figure and the deviation alert are all
exactly what the wrist would have shown.

Gaps in the recording are replayed as gaps, divided by the speed: a session with
a 90 s GPS dropout in it sits there for nine seconds at ×10, doing what the watch
did. That is the point of replaying that particular fixture.

Both halves of this reach into the app's own storage with `adb shell run-as`, so
they need the **debug** build — `tools/emu install` — and will say so rather than
failing quietly. On a real watch, never: `run-as` needs a debuggable APK, which
is a signature change, which costs an uninstall and any session not yet uploaded
(§6.3).

If nothing happens, the watch says why:

```bash
adb -s emulator-5554 logcat -s ReplayReceiver:* ReplayFeed:* RecorderService:*
```

The one failure worth knowing in advance: a broadcast cannot start a foreground
service while the app is in the background, which is why `tools/emu replay`
brings it to the front first. If it still refuses, the replay stays armed —
press any exercise on the watch and it starts the replay instead of a recording.

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

A Wear emulator can exercise service lifecycle, permissions and layout, and its
synthetic data still emits a single default route with identical values every
run, so it can never *match* anything. What it can now do is replay one of the
files you just pulled — see **Replaying an activity on the emulator** below,
which is where the matcher becomes something you can watch rather than read.

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

## Importing a Google Health takeout

A single TCX file at a time through the phone's file picker is fine for a
handful of workouts and hopeless for a history. Google Takeout hands you the
whole thing in one ZIP, and `:core` turns that into a backup archive the app
restores like any other:

```bash
./gradlew :core:run --args="takeout"
```

With no arguments that reads every `*.zip` in `takeout/` and writes
`takeout/parsed/hecate-takeout-<stamp>.zip`. Move that file to the phone and use
**Sicherung einlesen**. The restore pulls the sessions in and then runs the
normal clustering pass, so the routes that come out are the ones the app would
have learned had it been installed all along — same code, same thresholds.

```bash
# one part of a split export, or all of them
./gradlew :core:run --args="takeout --input takeout/takeout-20260912T095433Z-1-001.zip"
./gradlew :core:run --args="takeout --input 'takeout/takeout-*.zip'"

# somewhere else, and against what the app already has
./gradlew :core:run --args="takeout --input ~/exports --out ~/hecate.zip --existing sicherung.zip"
```

| Flag | |
|---|---|
| `--input` | a ZIP, a directory, or a quoted glob. Default: every `*.zip` in `takeout/` |
| `--out` | a file, or a directory to name one in. Default: `takeout/parsed/` |
| `--existing` | a backup exported from the phone; anything already in it is left out |
| `--map` | `"Rudern=WALKING,Tennis=RUNNING"`, for sports this app has no type for |
| `--zone` | overrides the zone from the takeout's own profile |
| `--no-auto` | ignore auto-detected activities, even ones carrying a track |
| `--verbose` | list every workout that was not imported, not just the first twenty |

Takeout splits a large export into `-001.zip`, `-002.zip` and so on. Those are
not one spanned archive — each is a complete ZIP holding a different slice — so
point the glob at all of them; reading only the first silently loses whatever
landed in the others.

**What it reads.** `UserExercises_<date>.csv` is the authoritative list of
workouts, with UTC start and end and an explicit `utc_offset` per exercise.
`gps_location_<date>.csv` and `heart_rate_<date>.csv` hold the day's positions
and heart rate, which the import segments by exercise window. The prettier
`exercise-0.json` next to them is deliberately ignored: it is capped at a few
hundred entries, its `hasGps` flag disagreed with the position files on fifteen
days of the export this was written against, and its `startTime` is a bare local
timestamp in no stated zone.

**What it leaves out**, all of it reported by name and reason at the end of the
run:

- Workouts with no positions in the export. Google keeps the exercise long
  after it has dropped the day's position file, so the early history is summary
  rows and nothing else.
- Sports with no type here — rowing, tennis, and anything indoor, which has no
  route in it whatever it is called. `--map` forces one through if you want the
  track anyway. The names it does recognise are generous and shared with the TCX
  reader: `Mountainbiking`, `Rennrad` and `Ride` are all rides, `Bergwandern`
  and `Trekking` are hikes, `Langlauf` is skiing and not — despite ending in
  "lauf" — a run.
- Auto-detected activities that overlap a workout you started yourself. The
  watch routinely "notices" a walk that is the first ten minutes of a ride it
  was already recording.

**Duplicates, in three flavours.** Session ids are derived from Google's own
exercise id, so importing the same takeout twice — or next year's takeout, which
still contains this year's workouts — produces the same ids, and the restore
skips what it already has. That covers two of the three. The third is a workout
Hecate recorded itself: the watch logged the same journey to Google Health
independently, so it comes back in the takeout under an id that matches nothing,
and only the clock relates the two. Both `--existing` and the restore inside the
app test for that with the same start-time-and-duration rule the ingest path
uses for the watch's own double recordings. `--existing` is optional; the check
in the app is not.

**Time zones.** Every exercise in the takeout names its own UTC offset, which is
what gets stored — so a January walk keeps +01:00 and a July ride +02:00,
regardless of when or where the import runs. Only a row that leaves the column
empty falls back to the zone in the export's own `Profile.csv`, and only a
takeout with no profile falls back to Europe/Berlin.

The same question was a bug in the TCX import, which stamped every file it read
with offset 0 — a TCX from Google Health is written in UTC and names no zone —
so imported workouts displayed two hours early in summer. It now resolves the
offset from the phone's zone at the instant of the workout, and believes the
file instead whenever the file spells an offset out. Sessions imported before
that fix are repaired in place by database migration v5.

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

# What is there to replay, and which of them find a route?
./gradlew :core:run --args="sessions --archive backup.zip --bundle bundle.json"

# Take one out of a backup, in the same JSONL the watch writes
./gradlew :core:run --args="extract --archive backup.zip --session a3f91b2c"

# What the auto-stop would have done to activities you actually did
./gradlew :core:run --args="autostop --archive backup.zip"
```

Sessions live on the watch at `/data/data/de.hecate/files/sessions/` and
are plain JSONL — one line per sample, readable with `cat`.

`sessions` is the one to run before choosing anything to replay. Its last column
is what each activity matches, and with `--bundle` that answer comes from
actually replaying it against that bundle rather than from what the phone
concluded on the day:

```
id         started          type      distance    active  locks onto
a3f91b2c   2026-03-11 07:42 RUNNING    8.42 km     42:15  Weg zur Arbeit @ 1.2 km, t=131s
b71c40de   2026-03-14 18:05 BIKING    22.10 km   1:04:33  unknown
```

The id column is only as long as it needs to be to name one session, and is what
`extract --session` and `tools/emu replay` take. Without `--bundle`, a backup is
listed from its database part alone — no samples are parsed, which for a few
hundred sessions is the difference between instant and not.

## When a recording ends itself

A workout that is not stopped keeps recording — the walk to the car, the drive
home, the evening — and everything downstream believes it, the route clusterer
included. So the watch ends the recording itself in three situations, and throws
away the part that was not the activity (Appendix A.30):

| It stops when | after | and cuts back to |
|---|---|---|
| you have not left a 30 m circle | 45 min | the moment you stopped moving |
| fixes keep arriving and none is good enough to use | 15 min | the last usable fix |
| you are faster than the sport can be | 90 s | the last plausible position |

The speed ceilings are 30 km/h for Laufen, 20 for Spaziergang and Wandern, 80
for Fahrradfahren, and **none for Skifahren** — a lift moves like a car and a
descent moves faster, so that rule is switched off there rather than made
unreliable. The other two still apply.

Two things it will not do. It never ends a recording that has not yet travelled
50 m, so starting the watch early and standing around is safe, and so is a
recording made deliberately without GPS. And a **manual** pause suspends all
three rules for as long as it lasts, with both clocks starting over on resume —
an auto-pause does not, because that is the standing still the rules are looking
for.

When it fires, the watch buzzes twice and says `Automatisch beendet — Stillstand`
(or `— Kein GPS`, or `— Zu schnell`), and the session detail on the phone lists
the reason under "Verlauf der Aufzeichnung". The watch's own file keeps every
position it recorded; the cut is a line in the footer, and the phone applies it
on the way in, so the discarded tail never reaches the database, Health Connect
or a backup.

The thresholds live in `Tuning` with the rest (`AUTO_STOP_*`). Before changing
one, run it over a real history — the useful answer is that nothing fires:

```bash
./gradlew :core:run --args="autostop --archive takeout/hecate-sicherung-*.zip"
```

```
id        started          type      length  auto-stop
a3f91b2c  2026-03-11 07:42 RUNNING    42:15  -
b71c40de  2026-03-14 18:05 BIKING   1:04:33  -
---
2 sessions, 0 would be auto-stopped, 0 already carry a cut
```

## The map behind the tracks

Track drawings sit on a dim OpenStreetMap street network with town names
(Appendix A.29). The data is fetched from the Overpass API, simplified and
stored as local tiles under `filesDir/karte`, and **never fetched twice**.

This is the one place the phone touches the network. The watch does not, and its
manifest still declares no `INTERNET`. Requests carry a tile-grid-aligned
bounding box and a generic `User-Agent` and nothing else — never a track, never
a position, never a time.

The app fills the map by itself: opening a route fetches the ground under it in
the background, and „Einstellungen → Karte → Karte vorladen" does the whole
history over WLAN. Doing it on a laptop instead is kinder to a volunteer service
and to a mobile plan, and it is the only way to *look* at the result:

```bash
# What would be fetched, and what it would cost. Queries nothing.
./gradlew :core:run --args="karte --archive backup.zip --dry-run"

# Fetch it. Interrupting is safe: every finished tile is on disk, and
# running it again fetches only what is still missing.
./gradlew :core:run --args="karte --archive backup.zip --out map/"

# One region by hand, without a backup
./gradlew :core:run --args="karte --bbox 49.50,10.90,49.72,11.15 --out map/"

# Look at it: one SVG per region, your own recordings in red over the map
./gradlew :core:run --args="karteinfo --store map/ --archive backup.zip --svg map/preview --stats"

# Pack it for the phone — „Einstellungen → Karte → Karte einlesen"
./gradlew :core:run --args="karteinfo --store map/ --zip map/karte.zip"
```

`--dry-run` is the one to run first, and it costs nothing. On the real history it
prints eleven regions and 3,334 km² — the recordings are not one area, and a
single box round all of them would be fifty times larger.

`karteinfo --svg` is how the map gets checked without a device. Open the SVG in a
browser (not ImageMagick, whose built-in renderer drops `<polyline>` strokes):
the streets, the town names, and your own tracks in red over them. Coverage,
alignment and dimness are all visible there.

Other flags worth knowing: `--level grob|fein|beide`, `--margin` and
`--detail-radius` in kilometres, `--refresh` to re-fetch ground already stored,
and `--offline`, which fails rather than reaching for the network. Several
boxes go in **one** `--bbox`, separated by semicolons; a repeated `--bbox` is
refused rather than silently reduced to its last value.

`/map/` is git-ignored.

## Permissions to grant by hand after install

- **Watch**: location, heart rate, physical activity, notifications — the app
  asks for these on first launch. Then two more that no app is allowed to ask
  for in that dialog, both in Settings → Apps → Hecate → Permissions:
  - **Location → "Immer zulassen"**. The first dialog can only grant "while
    using", which stops being true the moment the screen goes ambient.
  - Confirm **heart rate** is actually on. On Wear OS 6 this is
    `health.READ_HEART_RATE`, not the old `BODY_SENSORS`; see below.
- **Phone**: Health Connect → app permissions → write exercise, exercise route,
  heart rate, distance.
- Turn **off** the watch's automatic activity detection, or Health Connect gets
  every workout twice.

**Check them after every watch install, and check heart rate in particular.**
Health Services refuses an exercise configuration *as a whole* when it names a
data type the app has no permission for, so a missing heart-rate grant does not
cost you heart rate — it costs you the exercise, GNSS included. That failure is
silent on the wrist: the service runs, the notification says it is recording, and
nothing is recorded. The app now builds its configuration from what is actually
granted, warns on screen, and starts the session after 90 s with or without a
fix — but a run with no heart rate and no route is still a run you have to do
again. Appendix A.17 of the specification has the post-mortem.

```bash
# what the watch actually holds, after any install
adb -s <watch> shell dumpsys package de.hecate | grep -A8 "runtime permissions"
```

## Route priority

Shortcuts and variants are independent routes that happen to share a prefix
(§8.1), and while two routes are on the same ground the matcher will not guess
between them — it shows both names, with no ETA, until the paths part. On a
route family that shares most of its length, that is most of the run.

Each route therefore carries a priority from −2 to +2, set on its detail screen
as a row of five buttons — read them as ganz hinten, eher nicht, keine Meinung,
eher schon, ganz vorn, though the app shows only the numbers. Where several
routes currently coincide and exactly one outranks the rest, the watch assumes
that one and gives you its name, remaining distance and ETA from the start. A
tie decides nothing, so leaving everything at the default 0 keeps the cautious
behaviour — the feature turns itself on when you first use it.

It is a tie-breaker and nothing more. It cannot put you on a route you are not
on: the moment the ground contradicts it, the evidence wins and the label
switches. An assumed route shows blue on the watch, a worked-out one green.

## Intensity colours and your peak heart rate

The track drawing and the three charts on both detail screens — altitude against
distance, speed against time, heart rate against time — are coloured by how hard
you were working: **Leicht** (blue), **Mittel** (green),
**Intensiv** (orange) and **Höchstleistung** (red), at 70 / 80 / 90 % of your
peak heart rate. A recording is coloured from its own samples, a route from the
median heart rate of its members at each point along it, and a stretch with no
reading at all is drawn plain rather than as an easy one.

On a recording the charts are its own samples. The heart rate is recorded as its
own series, at whatever moments the sensor measured, and joined to the positions
when the phone reads the session in; on a chart a reading is then held for up to
half a minute after it was taken, since a rate half a minute old is still the
right band for the effort. A stretch with no reading at all is drawn in a quiet
grey rather than in a colour, because nothing measured should be the quietest
thing on the picture. On a route they are the median over everything you have done there, so "Geschwindigkeit (Median)" is the speed
that route is usually ridden at rather than any one ride. Under each chart is the
range it covers, because the vertical axis is exaggerated — deliberately, since
an hour held between 130 and 150 bpm drawn honestly is a straight line — and the
range is what keeps that honest. Speed is smoothed before it is drawn: it is a
derivative, and one second of GNSS noise is an imaginary sprint.

The peak rate is under Einstellungen → Belastung and starts at 180 bpm. Set your
own: `220 − age` is out by more than a whole band for about one person in three,
and the app has nothing else to go on — it has never measured a resting rate.
The setting only decides which colour a stretch is drawn in; nothing recorded
changes, and it is carried in the backup archive.

## Tuning

Every constant in the system is in `core/src/main/kotlin/de/hecate/core/Tuning.kt`,
each with its unit and the reasoning behind its value. There are no magic
numbers anywhere else. They have only ever met synthetic GPS — see §2.1 of the
open points.
