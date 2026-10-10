# Open points

Things that need a human, a device, or a decision before Hecate can be trusted
with a workout. Ordered by how much damage they can do.

The short version: all three modules build and lint clean, and the system has
completed one real round trip — a Pixel Watch 4 recorded, a Pixel 8 ingested and
acknowledged, Health Connect accepted the write, and a route bundle came back.
That first session found three defects the JVM suite could not see (§3.2), which
is the honest measure of how much of the device behaviour is still unverified:
one workout's worth.

---

## 1. Blocking — what can still cost you data

### 1.1 The release keystore is the one thing with no second copy

A keystore now exists and both modules install signed with it. It stays at the
top of this list because it is the only artefact here that cannot be
regenerated: lose it and every future build is a different app, which means an
uninstall, which means losing whatever the watch has not yet uploaded (§6.3).

Its coordinates go in `keystore.properties` at the repository root:

```properties
storeFile=/absolute/path/to/hecate-release.jks
storePassword=…
keyAlias=hecate
keyPassword=…
```

Both modules pick it up automatically when the file exists and fall back to an
unsigned release build when it does not. `keystore.properties` and `*.jks` are
git-ignored. **Upload before reinstalling, every time.**

Two things learned creating one. An incomplete `keystore.properties` fails as
`Keystore file not set for signing config release` even when `storeFile` is
perfectly set — the missing key was `storePassword`, and the message points at
the wrong field. And a missing file does not fail at all: `assembleRelease`
succeeds and quietly produces `phone-release-unsigned.apk`, which only refuses at
install time.

For the backup: a `.jks` is binary, so it is not an "SSH key" entry in a password
manager — it is an attachment, or `base64 -w0` into a secure note. Store the
alias and store type beside it, and the certificate fingerprint, which is what
Android actually compares on upgrade:

```bash
keytool -list -keystore <path>.jks -alias hecate       # SHA-256 fingerprint
apksigner verify --print-certs <apk>                   # same value, from the APK
```

Then restore it once, into a scratch path, and check the fingerprints match. The
restore is the only part of a backup that can be wrong in a way you cannot see.

### 1.2 The watch↔phone link has no retry but the next workout

Delivery works — sessions cross, are acknowledged, and the bundle comes back
(the diagnosis that got it there is in the build scripts, which explain why both
modules share `applicationId = "de.hecate"`, and in README's "Testing on the
wrist"). Two gaps that first contact exposed are still open:

- **The phone never asks for a resend.** `PATH_RESEND` is defined in both `Wire`
  objects and handled on the watch — `PhoneListenerService` calls
  `uploadPending()` — but nothing in `phone/` ever sends it. The recovery path is
  dead code.
- **Nothing reconciles missed items.** The phone acts only on the live
  `onDataChanged` callback and never scans for `DataItem`s that arrived while it
  was unbound. Combined with the above, a session whose delivery event is missed
  is re-offered only on a watch reboot or at the end of the *next* recording.

Neither has cost data — the watch keeps its copy until acked, exactly as
designed — but together they mean the system's only retry is another workout.

---

### 1.3 The backup archive has never been restored

**What exists.** Export to a file and restore from a file, both through the
system file picker (Appendix A.10). The phone menu on the sessions screen has
"Sicherung exportieren" and "Sicherung einlesen". No cloud, no network, no
storage permission: NFR-7 is untouched, and moving the file to Drive or anywhere
else is something the user does outside the app.

Why it exists: the phone's database is the only complete copy of anything. The
watch deletes each session on acknowledgement, Health Connect is write-only and
lossy, and a TCX re-import cannot bring back route names or priorities.

**What is verified.** `ArchiveCodec`, the ZIP and JSON layer, is covered by JVM
tests: a full session round trip, nullable sample fields, many sessions addressed
by id, a large archive whose last entry must still be complete, a future format
version, a ZIP that is not a Hecate archive, unreadable JSON, and unknown fields
from a future version. It and its tests now live in `:core` (A.15), so
`./gradlew :core:test` runs them.

`ArchiveRepository` is verified too, under Robolectric (§3.2): a database with
three sessions and two named routes is exported and restored into an empty one,
and the assertions are the ones this feature exists for — the names and the
priorities come back, the samples come back, and `committedAtM` is carried rather
than recomputed. Restoring over a populated database is checked to change nothing
already there.

**What is not.** Everything that needs a device:

- The Storage Access Framework halves — that a picked `content://` Uri really
  opens for writing, that the suggested filename survives, and that a large
  archive does not hit a timeout on a real phone.
- The Storage Access Framework, which the tests substitute a `file://` Uri for.
  Everything on this side of that Uri is now covered.
- The one thing worth doing first: **export, wipe the app's data, restore, and
  check the route names and priorities came back.** A test does that against an
  in-memory database; nobody has done it to a real one on a phone.

**Decided while building it,** both recorded in A.10: restore merges rather than
replaces, so it can never destroy what is already there; and it writes rows back
directly rather than through `SessionRepository.ingest`, so `hcWriteState` and
`committedAtM` survive. A.15 narrows the first of those: a session whose source
is an import can also be skipped for looking like a workout already in the
database, because a takeout contains the watch's own log of workouts Hecate
recorded and no id relates the two copies.

**The first real restore will be a takeout import**, which makes it a better
test than the wipe-and-restore above and a worse one: 60 sessions and no routes,
names or priorities in the archive at all, so it exercises the session path and
the clustering that follows it while saying nothing about the part of the format
that exists for names and priorities. Do both.

---

## 2. Needs a decision

### 2.1 Tuning constants have only ever met synthetic GPS

Every constant in `de.hecate.core.Tuning` is reasoned from the geometry of the
problem and validated against synthetic tracks. Synthetic tracks are generated
with a specific noise model — an Ornstein-Uhlenbeck process with a 100 s
correlation time and a 4 m standard deviation — and real GNSS is not that.

Treat the following as first guesses, to be revisited against recorded fixtures
via the replay harness:

| Constant | Value | What to watch for |
|---|---|---|
| `GPS_SIGMA_M` | 15 m | Too low and the matcher is overconfident between buildings |
| `WEIGHT_DECAY` | 0.97 | Too high and an early mistake sticks; too low and nothing ever commits |
| `COMMIT_SEPARATION_M` | 50 m | Too high and parallel streets never separate |
| `COMMIT_MIN_M` | 400 m | Followed along the route itself (A.31); too low and a route's last stretch is named at the front door |
| `FOCUS_RELEASE_M` | 100 m | How long a name survives not fitting; too low and it flickers where a medoid is a street off |
| `NULL_DISTANCE_M` | 120 m | Must stay above `DEVIATION_M`; see Appendix A.3 |
| `CLUSTER_CUT` | 0.25 | Too low and every variation is a new route; too high and shortcuts merge |
| `DEVIATION_HOLD_SAMPLES` | 15 | 15 s of alert latency; shorter risks false alarms at junctions |

The replay harness exists for exactly this (US-10):

```bash
./gradlew :core:run --args="bundle --sessions ./fixtures --out bundle.json"
./gradlew :core:run --args="replay --bundle bundle.json --session fixtures/x.jsonl"
./gradlew :core:run --args="onwrist --bundle bundle.json --session fixtures/x.jsonl"
./gradlew :core:run --args="timeline --archive backup/hecate-sicherung-<stamp>.zip"
```

`onwrist` compares the decisions the watch actually made, stored inside the
session file, against a fresh replay. Any difference is a bug by definition —
for recordings made by the same build, with the watch on one lane (A.31):
before that, deliveries could reach the matcher out of order, and a backup
stores the samples sorted. `timeline` replays a whole backup against its own
routes (any `--bundle` may be a backup ZIP) and prints, per recording, what
the watch would name along it and how the ahead/behind behaved; it is how
A.31 was measured, and the quickest way to see what a tuning change does to
every workout there is.

### 2.2 The fixture set does not exist yet

§6.5 says to build it first: work route, two shortcuts of it, the reverse
direction, one novel path, one with a 90 s GPS dropout, one with a long pause.
All seven exist as *synthetic* geometries in `core/src/test/.../Synthetic.kt`
and the unit tests exercise them. Real recordings have now started — the first
session crossed to the phone and reached Health Connect — but none of the seven
shapes exists as a real track yet, so the golden-file regression testing §6.5
calls for still has nothing to guard.

Once you have ten or so real sessions, copy them off the watch into a
`fixtures/` directory and commit the ones that are interesting. Note that
`/data/data/de.hecate/files/sessions/` is private storage: a plain
`adb pull` reaches it only on a rooted watch, and `adb shell run-as` only on a
debuggable build — which is a signature change, and therefore an uninstall.
README's "Testing on the wrist" has the workable spellings. The phone is no help
either; it ingests into Room and keeps no raw JSONL.

### 2.3 Route priority in use

Priority (Appendix A.4b, A.31) breaks ties between routes that share the same
ground. One integer per route, −2..2 since schema v7 (A.21), compared only
against other *contenders* joined at about the same point — a route followed
since the start always beats one the ride left and came back to — and not
context-aware — see §4 for why that is settled rather than pending.

Two things to watch once you are using it:

- Setting a priority rebuilds and pushes the bundle immediately, so it takes
  effect on the next workout rather than after the next ingest. If the watch is
  out of range when you change it, it takes effect whenever the watch next syncs.
- Preferring a route costs a wrong label on the shared part of the days you
  take the other one, until the paths part and then `FOCUS_RELEASE_M` (100 m)
  further. Ahead/behind is unaffected — it is carried across when the name
  changes (A.31) — and the label is drawn as an assumption until then. That is
  the trade you are making, and it is the right way round only if you take
  the preferred route most of the time.
- With equal priorities a route is still named on shared ground (A.31): the
  one already named, else the one with the most weight. Rank the variants if
  the name matters to you.

### 2.4 Ahead/behind sign convention

`deltaVsMedianS` is `elapsed − median`, so **positive means slower than usual**.
The watch shows `+45 s` in red and `−12 s` in green, under the label `Ø`, with
nothing spelling out which way round it is — the captions that used to say so
went when the live page was made dense. Runners are used to the opposite sign
from race splits. Change `Format.delta` and the colour test in `LivePage`
together if you want it the other way round.

### 2.5 A parallel street 80 m away will match a learned route

With `NULL_DISTANCE_M` at 120 m (Appendix A.3), a path running 80 m from a known
route still scores better than "unknown", so it will be reported as that route —
though the deviation alert will fire, since 80 m is above `DEVIATION_M`. The
combination is arguably the right behaviour ("you're doing the work route, but
you're off it"), but it has not been tried in a real street grid, where routes
80 m apart are common. Watch for it.

### 2.6 Clustering is O(n²) and recomputed on every ingest

§5.7 says this is fine for a few hundred sessions and defers incremental
clustering (§8). At one workout a day that is fine for a year. The coverage
metric is the expensive part and is accelerated by a bucket index, but
`ClusterWorker` also reloads every sample of every session from Room on each
run. Measure it once you have two hundred sessions; if it hurts, cache the
resampled tracks rather than reaching for incremental clustering.

### 2.7 targetSdk trails compileSdk deliberately

`compileSdk` is 37, which Compose BOM 2026.09 requires; `targetSdk` stays at 36.
compileSdk only decides which APIs compile, while targetSdk opts the app into
new *runtime* behaviour — new permission semantics, new background limits — and
that is a change to be made on a day someone can wear the result and watch for
it. Lint's `OldTargetApi` is disabled in `phone/build.gradle.kts` with the same
reasoning written at the suppression, so the warning does not simply accumulate
unread.

### 2.8 Bundle size on the watch

A route bundle is gzipped JSON containing every route's geometry at 10 m spacing
plus its profile. Fifty 10 km routes is roughly 50 000 points — a few hundred
kilobytes gzipped. Comfortable, but the whole bundle is deserialised into memory
on the watch at session start. If the route list ever grows past what §8.3
contemplates, this is the thing that will complain first.

---

### 2.9 The two emulators are not paired, and nothing decides whether they should be

`tools/emu` boots a watch and a phone that cannot talk to each other. The
Wearable Data Layer is not a socket the app opens: it is Play services on both
nodes brokered by the Wear OS companion app, and without a pairing there is no
node, so `DataClient` publishes into nothing. That is why Appendix A.19's pass
over every screen could cover every screen and no transfer, and why
`tools/emu bundle` exists — on a wrist the phone sends the bundle, and here it
has to be put there by hand.

What pairing would take, as far as anyone here knows and with the third point
unverified:

- A **Google Play** phone image, because the companion app comes from the Play
  Store and is not legitimately sideloadable — which also means a Google account
  signed in on the emulator, against the grain of the rest of this setup.
- A watch image with Play services, which the standard `android-wear` images have.
- A **TCP bridge on port 5601** between the two. The documented spelling covers a
  Wear emulator and a *physical* phone (`adb -d forward tcp:5601 tcp:5601`); for
  two emulators on one host it is a forward on the watch plus a reverse on the
  phone, and nobody here has run it. Android Studio's Device Manager has a
  pairing assistant that does this and drives the companion app, with no
  documented command-line equivalent — so the honest options are to install
  Studio once or to get the bridge right by hand. Either way it dies with the
  emulator and `tools/emu up` would have to re-establish it.
- Both APKs sharing an `applicationId` and a signature, which they already do
  (§1.2), and which `tools/emu install` satisfies with one debug keystore.

**What it would buy**: the transfer path, which nothing can currently exercise —
session upload, the acknowledgement, `markAcked` deleting the file, the bundle
coming back, and both gaps in §1.2 that are dead code until something delivers a
`DataItem`. Possibly Health Connect write-back on the phone emulator as well,
though a Play image is likely necessary there too and that has not been checked.

**What it would not buy**: matching. The watch's sensor data would still come from
the synthetic provider and its one default route. That gap is answered instead by
`tools/emu replay` (Appendix A.28), which needs no pairing at all — which is
exactly why this is a decision rather than a blocker.

## 3. Known gaps against the specification

### 3.1 The auto-pause broadcast test has not been run

§6.5 gives an `adb shell am broadcast` incantation for injecting
`whs.AUTO_PAUSE_DETECTED`. The code handles `ExerciseState.AUTO_PAUSED` and
records a `PAUSE_AUTO` event, but this has never been exercised.

Since schema v3 (Appendix A.12) that `PAUSE_AUTO` is kept rather than dropped at
ingest, so this no longer needs the broadcast or a cable to answer: ride, then
read "Verlauf der Aufzeichnung" on the session detail screen. A `PAUSE_AUTO`
with a matching `RESUME_AUTO` at a plausible offset settles it in the
affirmative; a ride with a genuine standstill and no such pair is the real
finding.

### 3.2 No instrumented tests

`:core` carries the algorithms, the archive format and the takeout import.
`:phone` now runs its Android-side code under Robolectric —
`./gradlew :phone:testDebugUnitTest`, about twenty seconds, no device — covering
Room and every migration, ingest, the backup round trip, and the TCX import.
`:wear` has no tests, and nothing anywhere is instrumented.

Robolectric moved the line but did not remove it. Still unreachable from a
laptop: Compose UI, the Wearable Data Layer, real permissions, Health Connect,
and a genuine `content://` Uri from the system file picker — the archive tests
substitute a `file://` one, which exercises every line of the app's code and
says nothing about whether the picker's Uri opens for writing on a phone.

§6.5 does not ask for instrumented tests — it asks for emulator and on-wrist
verification, both of which are manual. This is a reasonable position for a
single-user sideloaded app, but it does mean every regression in the list above
will still be found by wearing it. One of the two is at least repeatable now:
`tools/emu replay` plays a stored activity into the recorder, so Compose, the
match display and the deviation haptic can be watched against a real track
without leaving the desk (Appendix A.28). It is still a human looking at a
screen, not a test.

The first session on real hardware produced three defects, and the suite was
green through all of them. That is not a failure of the tests; it is the exact
shape of what they cannot see:

- **The Data Layer never delivered anything** (§1.3). No JVM test can observe
  `applicationId` scoping between two devices.
- **Every Health Connect write was rejected** with `route can not be out of
  parent time range`. The session's end time was taken from its last sample, and
  Health Connect requires route points *strictly* before the end —
  `location.time.isBefore(endTime)`, an exclusive bound. Ingest had been
  discarding the footer's real `endedAtUtcMs`, so the column now exists
  (schema v2) and the writer uses it.
- **Every TCX import failed** before parsing a byte. `DocumentBuilderFactory
  .setXIncludeAware` throws `UnsupportedOperationException` on Android and works
  on the desktop JVM, so `TcxParserTest` passed while the phone refused every
  file. The XML hardening is now best-effort, with an `EntityResolver` doing the
  actual work, and a test pins the security property rather than the mechanism.

All three lived in platform behaviour: cross-device delivery, a vendor library's
validation, a JDK-versus-Android API difference. The replay harness is excellent
at the algorithms and structurally blind to every one of them. Anything that
talks to `com.google.android.gms`, `androidx.health`, or `javax.xml` should be
assumed unverified until it has run on the device.

A fourth and fifth, from the first long run on a wrist, both written up in A.17:

- **The heart-rate permission had never been granted, and could not be.** The app
  asked for `BODY_SENSORS`, which Wear OS 6 no longer tracks as a runtime
  permission for an app targeting SDK 36; the grant it wants is
  `health.READ_HEART_RATE`. Health Services rejects an exercise configuration as
  a whole when it names a data type the caller may not collect, so a missing
  heart-rate grant cost the whole exercise — GNSS was never switched on for the
  entire 44 minutes, which `dumpsys location` shows plainly. Nothing on the wrist
  said so: the service ran, the notification said "recording", and `PREPARING`
  had no exit but a fix that could not come.
- **Three empty sessions had stranded themselves.** A session with no samples is
  refused by ingest, a refusal was never acknowledged, so the watch kept the file
  and re-offered it after every reboot while the data item sat on the phone.

Both were found with `dumpsys`, not with logcat, because the watch's main log
ring is 64 KiB — perhaps a minute of Wear OS chatter. **Raise it before any run
you might want to diagnose**, on both devices; it resets on reboot and the
persistent property needs root:

```bash
adb -s <device> shell logcat -G 8M
```

A sixth defect, of a different kind again: **`MIGRATION_4_5` named a column
that does not exist.** The property is `tzOffsetMinutes`, the column is
`tz_offset`, and the migration's SQL said `tz_offset_minutes`. Room runs pending
migrations the first time the database is opened, so every start of the new build
crashed. Nothing in the build could have caught it — raw SQL in a migration is a
string until something executes it — and this is what finally bought Robolectric:
`MigrationTest` builds each exported schema version, runs the real migration list
against it, and fails in a second on exactly that typo. Unlike the three above,
this one was reachable from a laptop all along and simply had nothing looking.

**Trap found while writing those tests, not yet acted on.** `SessionDao.insert`
is `OnConflictStrategy.REPLACE`, SQLite implements REPLACE as DELETE then INSERT,
and `sample` has `ON DELETE CASCADE` — so re-inserting a session row to change
one column silently deletes every sample it had. Both callers guard with
`countById` first, so nothing does this today, and a test now pins the behaviour
so the next person meets it in a test rather than in a history. The question left
open is whether `insert` should be `ABORT` instead, which would turn a future
mistake into a crash rather than into quiet data loss.

### 3.2b The takeout import has been run, but its output has never been restored

`./gradlew :core:run --args="takeout"` has been run against one real 273 MB
export: 429 exercises in, 60 sessions out, which cluster into 4 routes on a
laptop through the same `bundle` command the replay harness uses. The unit tests
cover the CSV reader, the offset handling, heart-rate matching, all three
duplicate mechanisms and a split export, against fixtures shaped like the real
files.

What has not happened: **the archive it produces has never been read by the
phone.** The format is the same one `ArchiveCodec` round-trips in tests, and it
is written by that same class, so the risk is not in the bytes — it is in the
restore path on a device, which is the whole of §1.3. Also unverified, and only
visible after a real restore:

- Whether 60 sessions of ~3000 samples each clusters in a tolerable time on the
  phone rather than on a laptop. `ClusterWorker` is O(n²) over sessions (§2.6)
  and has never had more than a handful.
- Whether the routes the phone derives are the four the laptop derived. They
  should be — same code — but "should be" is what §1.3 exists to distrust.
- Whether the auto-detected activities that were let in (three rides here) are
  real rides or artefacts worth excluding with `--no-auto`.

### 3.3 Battery has not been measured

NFR-3 asks for battery cost comparable to Google's Exercise app. The matcher's
CPU budget is tested (a unit test fails if it exceeds 5 ms per sample with a
realistic route set); the GNSS and heart-rate cost is whatever Health Services
does. Unmeasured.

### 3.4 Ambient batching has not been observed

§1.3 says exercise data arrives at ~1 Hz with the screen on and batched while
ambient. The matcher is proven to produce identical results either way — that is
a unit test, `batched processing equals streamed processing`. What has not been
seen is how large the batches actually are on a Pixel Watch 4, which determines
how stale the Route page looks mid-run. §7 says to leave batching at its default
until measured; that is still the position.

---

### 3.5 The recorder logs an unexpected state on resume

Pausing a recording by hand and resuming it produced, once, on a Pixel Watch 4:

```
W RecorderService: Unerwarteter Zustand: USER_RESUMING
```

The session itself was unharmed — it recorded, stopped cleanly, transferred, and
reached Health Connect with its pauses intact. But `USER_RESUMING` is a state
Health Services does emit and `RecorderService` does not expect, on a path every
real run will take. Reproduce it deliberately, then decide whether it is a
missing branch or a state that is genuinely safe to ignore. Until then, treat
auto-pause as unverified: §3.1 already says its broadcast test has not been run,
and this is the same gap seen from the other side.

The warning itself is still logcat-only, and logcat will not survive a long ride.
What is now durable is the context around it: schema v3 keeps the session's
`PAUSE_MANUAL`/`RESUME_MANUAL` pair (Appendix A.12), so after the fact you can
tell whether the resume that produced this warning actually took effect — a
`RESUME_MANUAL` at the expected offset, and samples that stop being `paused`
after it — without having caught the line live.

### 3.6 The dense live page has been measured, not seen

The Material 3 rework replaced the Live and Route pages with one page carrying
eight values (Appendix A.11). Whether all eight are actually *readable* at speed
is the only question that matters about it, and it cannot be answered here.

What has been checked: both modules compile and lint clean, and the layout was
sized against the type scale rather than guessed. Wear Material 3 sets
`numeralSmall` at 30 sp, `labelMedium` at 18 sp and `bodyExtraSmall` at 12 sp;
the six rows plus spacers come to roughly 175 dp, against 204 dp on a 41 mm face
less about 26 dp for `TimeText`. It fits, with more room on a 45 mm face.

What has not: how it looks on glass, in daylight, on a moving bike. Three things
to look for specifically.

- **Whether the secondary pair is too small.** Heart rate and delta are set at
  18 sp against 30 sp for distance and speed. The delta is the app's whole
  point, and it was given colour instead of size on the theory that red-versus-
  green reads faster than another large numeral. If that is wrong, the fix is to
  raise both to `numeralSmall` and drop the ETA line, which is the least urgent
  row on the page.
- **Whether the round face crops the first and last rows.** The route name and
  the status line sit in the narrow bands at top and bottom. Both are single
  lines with horizontal padding, but padding chosen by arithmetic is not padding
  chosen by looking.
- **Whether it ever scrolls.** It should not. If it does on the watch in hand,
  the budget above is wrong and a row has to go, rather than the page being left
  to scroll — the point of the change was to stop hands leaving the bars.

Also unverified, and cheap to check at the same time: `TimeText` now draws a
clock the app never had, and the `EdgeButton` on the control page is positioned
by the scaffold rather than by this code.

### 3.7 The standout threshold has been raised once, on one history

The recordings list marks a recording whose speed or average heart rate is at or
above the 85th percentile for its route (Appendix A.13). The arithmetic is
tested — twelve cases in `StandoutsTest`, including that each route ranks against
itself and that missing heart rates do not drag the threshold down — but tests
use invented numbers, and the only question that matters here is whether the
marks are *useful*.

The threshold was 0.75 and was raised to 0.85 by A.21, which is one judgement
against one history: a quarter of a sixty-ride commute carried a mark and the
emphasis had stopped reading as emphasis. Two things to watch.

- **Whether one in seven is now too few.** 0.85 marks roughly the best in seven,
  and on a route with a dozen recordings that is two rows. If the mark now feels
  absent rather than rare, the constant is `Standouts.PERCENTILE` and nothing
  else has to move.
- **Whether four members is too few.** At exactly four, only the best recording
  clears the threshold, and it changes every time a faster one arrives. That is
  arguably correct — it is a personal best against the route — but it means a
  brand-new route immediately marks one of its own recordings, which may look
  like a claim rather than a fact.

Also unverified, and only visible on a device: whether the two marks can be told
apart at a glance. They are distinguished by colour — primary for speed,
tertiary for heart rate — plus bold weight and an upward arrow, and both colours
come from the wallpaper under dynamic theming. The arrow is the same on both, so
a wallpaper whose primary and tertiary land close together would leave weight
and the unit suffix doing all the work of telling them apart.

### 3.8 The heatmap has been seen on an emulator, not on a phone

The picture above the routes list (Appendix A.14, rebuilt in A.21, corrected in
A.22) was first checked by rasterising it off-device — `Heatmap.build` and
`Heatmap.blur` are pure JVM — against synthetic data; the tests pin the extent,
the radius filter, the cell size, the line drawing, the blur and which way up the
raster is. It has since been looked at on a phone emulator with 66 real
recordings and four real routes behind it, in both themes, which is what found
the two faults A.22 fixes: an extent that cut a fifth of the sample points off at
an invisible line, and a bottom step of the colour ramp that could not be seen.

What that leaves.

- **Whether the log scale separates the right things** now that the bottom of
  the ramp is legible. On the history above, a lane crossed once and a commute
  ridden forty times are both visible and plainly different, which is the whole
  requirement; on a history with two orders of magnitude between them it may
  want the other direction. It is one line in `rasterOf`.
- **Whether 15 m cells and σ = 1.5 are the right pair.** They are meant to make
  a track as wide as the uncertainty about where it was, roughly 22 m of ground.
  Too wide and two streets a block apart merge; too narrow and the picture goes
  back to being a line drawing. On the emulator at 20 × 20 km of extent they hold
  up; a denser town is the case that would break them. They are two constants in
  `Tuning` and a redraw, not a re-clustering.
- **Whether the reach is the right bound.** `HEATMAP_EXTENT_REACH` is one
  route-span, which on the history above puts a ride 17 km from home outside the
  picture and says so in the caption. Whether that is the right place to stop is
  a judgement only more histories can settle: the failure in one direction is a
  picture that squashes what you do daily to fit in something you did once, and
  in the other a caption that keeps mentioning recordings nobody can find.
- **What the cap costs in practice.** Past about 35 × 20 km of extent the cell
  doubles, and nothing here has been looked at in that state. A history with two
  homes in it is the case: it is also the case where the picture is of two towns
  rather than of a street, so coarser cells may be exactly right.
- **Whether the full-screen view is usable one-handed, and whether the pinch
  feels right.** Pinch to 8×, drag to pan, tap to close. The gesture now holds
  the ground still under the fingers (A.23) and `ViewportTest` proves the
  arithmetic, but *proves* is the strongest word available here: a pinch cannot
  be injected into these emulators at all. `adb shell input` has no second
  finger, and SELinux refuses `sendevent` on `/dev/input/*` on a production
  system image, which every Play-enabled AVD is. So the clamp, the anchoring and
  the feel of the whole thing have been reasoned about and unit-tested, and not
  once felt. It is the first thing to try on the phone.
- **Whether the two extents are the right two.** The card is the routes' box and
  the full-screen view is everything inside the radius (A.23). On the history
  this was built against that reads well; on one with a second home 60 km away
  the full picture becomes two towns and a great deal of nothing, at a cell size
  the cap will have doubled. Nobody has seen that.

Also worth timing on a device: the bitmap is built on `Dispatchers.Default` and
the blur alone is about 300 ms on a laptop at the cap, so the card may show its
empty extent for a visible moment on a large history. If that reads as a bug
rather than as loading, the answer is probably a placeholder rather than a
faster blur.

Not yet decided, and only answerable with a real history: whether the extent
should follow the routes of the *selected activity type* rather than all of
them. A cycling route of 60 km and a running loop of 5 km in one picture means
the running loop is a smudge. The recordings list has an activity filter now
(A.21); the routes list has none, and this is the reason it might want one.

### 3.9 The intensity colours have been seen on an emulator, not on a wrist-worn history

(A.26 found what §3.9 could not: the colours were fine and the data was not —
the recorder kept one heart rate per delivery and threw the rest away.)

Both drawings on both detail screens are coloured by effort (A.21), and every
part of that is arithmetic that produces a picture rather than an error: a wrong
boundary still colours something. `IntensityTest` pins the boundaries, the
saturation above the peak and the difference between "no reading" and "an easy
one"; what it cannot say is whether the result is legible. A phone emulator
carrying 66 real recordings answered one of the three questions below and left
the other two open.

- **Whether the 15 s median is enough at a boundary.** An effort sitting a few
  beats under 80 % settles to one colour after smoothing; one balanced exactly on
  the line still alternates, because it genuinely has no band. If that reads as
  noise on a real ride, the answer is hysteresis — remember the previous band and
  require a margin to leave it — which is a change to `Intensity` and its tests
  and to nothing else.
- ~~Whether the full-height backdrop is too much.~~ Answered on the emulator:
  yes, and worse than stripes — it coloured the sky above the line, where nobody
  was. A.22 removed it, and the effort is now the line and the fill under it.
- **Whether 180 bpm is a reasonable place to start.** It is a plausible figure
  for a fit adult in their thirties and nothing more; the setting is under
  Einstellungen → Belastung, and the bands are listed there in bpm precisely so
  that a wrong default is obvious rather than invisible.

Worth knowing: a route's colours come from `route_profile.median_hr`, which has
been filled since the first schema but is null for any bin whose members carried
no heart rate. A route whose recordings all predate a strap draws plain, like
such a recording does.

### 3.10 The speed chart is an inference, not a measurement

(A.24 found the first thing this predicted: the chart was fine on imports and
nonsense on watch recordings, because the clock underneath it was. What follows
is what is left after that.)

A.23 added speed and heart rate against time to both detail screens. The heart
rate is a column being drawn. The speed is not: it is a derivative of the
positions, and every decision behind it is visible only as a shape.

- **Whether smoothing to the drawn scale hides something worth seeing.** A long
  recording is now median-filtered at the width of one drawn point before it is
  thinned (A.25), so a seven-hour walk is a line rather than a solid band. A
  sustained effort survives a median and a one-second excursion does not, which
  is the intent — but the width follows the *duration*, so the same sprint is
  drawn on a 40-minute ride and smoothed away on a six-hour one. Whether that
  ever matters is a question for a long ride with something real in it.
- **Whether 15 s of rolling median is the right width for a recording.**
  Narrower and a single wandering fix becomes a sprint; wider and a real sprint
  becomes the average of what surrounds it. `Tuning.SPEED_MEDIAN_WINDOW`, and a
  redraw rather than anything stored.
- **Whether 150 m is the right baseline for a route.** The per-bin median times
  are monotone only on average, and differencing neighbours produced 4418 km/h
  on the first real route it met — `ROUTE_SPEED_WINDOW_BINS` is the fix and 40.8
  km/h was the result, which is a bicycle going downhill but has not been
  checked against what that descent actually is. A route with a genuinely fast
  short stretch is the case that would say whether the window is too wide.
- **Whether a chart of a route's *typical* speed is a thing anybody wants.** It
  is the median of every ride there, so it is nobody's ride. It may turn out to
  be the most useful chart on the screen — it is the one that says where this
  route is slow — or it may turn out to be a shape with no reader.

### 3.11 A watch recording has no altitude

Both rides in the first real backup carry no altitude on any sample, while a
takeout import of the same commute carries one on every sample. So the elevation
profile, the ascent and descent figures and the height band — everything A.20
built — are simply absent from a recording made on the wrist, and present on the
same ground imported from Google Health.

The recorder rejects an altitude that is not finite or that falls outside
`MIN_ALTITUDE_M..MAX_ALTITUDE_M`, which was written for the emulator's synthetic
provider sending `Double.MAX_VALUE` (A.18). Whether the watch is sending such a
sentinel, sending nothing at all, or needs `DataType.ELEVATION_GAIN` requested
alongside `LOCATION`, is not answerable from the data: a rejected value and an
absent one look identical once written. The next thing to do is to log what
`LocationData.altitude` actually contains on a real recording — one line, one
ride — and then decide.

Worth knowing before anyone spends time on it: a barometric altitude is what
makes a profile worth having, and whether this watch exposes one through Health
Services at all is the first question, not the last.

### 3.12 Backfilling a heart rate the recorder never wrote

The two rides recorded before A.26 carry 161 and 787 readings where the sensor
measured thousands. Those readings are not in the files and no amount of
processing will bring them back. Two other copies of the same rides plausibly
exist, and reading either one into an existing recording is a feature that does
not exist yet.

- **Health Connect.** The phone already writes sessions there and holds the
  write permission; what it has never asked for is `ReadHeartRateRecord`. If
  Google Health or Fitbit syncs a continuous rate for the workout's window,
  filling the holes is a query and a merge — the merge already exists
  (`HeartRates.merge`), and the only new decisions are which source to trust
  when both have a reading and how to mark a recording whose colours came from
  somewhere else.
- **A Google takeout.** The same workout appears in the export with its own
  series, and the importer already parses exactly that. What it does today is
  skip the whole workout as a duplicate of the recording it already has
  (`isDuplicateImport`); what it would have to learn is to merge one field of a
  duplicate rather than dropping it.

Neither is hard. Both change a recording after the fact, which this app has so
far refused to do — the file is what the watch said — so the question is not
whether it can be done but whether a recording that borrows its heart rate from
another app is still the recording. A visible mark ("Puls aus Health Connect")
is probably the price of doing it at all.

---

### 3.13 The basemap has been seen on an emulator, not on a phone in daylight

What the OpenStreetMap ground under the track drawings (A.29) has actually been
put through, so that the gap is the right size:

- `karteinfo --svg` renders the store from its decoded tiles with the user's own
  recordings in red over it, which answers coverage and alignment. It answered
  them correctly on a real fetch of the Regnitz valley north of Erlangen.
- The codec, the viewport query, the label placer, the coverage clusterer, the
  fetch planner and the level-of-detail table are unit-tested; the import, the
  delete, a corrupt import and an empty store are tested under Robolectric.
- **On the Pixel 8 emulator, against the real history**: a route drawing, the
  heatmap card and the full-screen heatmap all draw the map, in light and dark,
  with the track aligned on it and the level of detail visibly different between
  the coarse view and the close one. The on-demand fetch filled twenty-five
  tiles by itself, silently, while the heatmap was open — which is the only part
  of the network path that a test cannot reach.

That run found two defects that no test would have: the place labels were
unreadable, because the halo was measured with the stroke baked into its
`TextStyle`, which fattens the glyphs rather than outlining them; and the card
said "1 Gebiete".

Three things are still only judgeable on a real screen, and each has a specific
way of being wrong:

- **Whether "dim" is dim enough in daylight.** The palette is one neutral ink
  from 0.40 down to 0.10 alpha, chosen against a track drawn at full opacity. It
  reads correctly on an emulator in both themes; an emulator is a monitor. On a
  phone at midday with the brightness up the whole map may simply vanish, and in
  a dark room a street grid at 0.23 may compete with the track. The alphas are
  all in `BasemapColors` in `Theme.kt` and are the one thing here that should be
  expected to change after the first walk outside — as are the 11 sp place
  names, which are legible on a desk and have never been read at arm's length in
  the sun.
- **Whether the pinch holds 60 fps across a zoom-key crossing.** The scene is
  rebuilt six times over the 1…8 range, off the main thread, and each rebuild is
  a few thousand `lineTo` calls. The risk is a visible hitch at the moment a key
  changes. If it stalls, the fix is local: split each class's `Path` by tile
  inside the scene builder.
- **Whether the map lines up with a recording made on the ground it draws.** The
  SVG says the map is where OSM says it is and the track is where the watch said
  it was, at the scale of a region. A systematic offset of ten metres would be
  invisible there and obvious on a route drawing of one street.

---

### 3.14 Route lines still fatten under the pinch; the basemap does not

In the full-screen heatmap, the basemap is built for the pinched viewport and
its strokes are divided by the zoom, so a 1 dp road stays 1 dp at eight times
magnification. The route lines drawn over the raster are not: they ride inside
the `graphicsLayer` and are eight times thicker at full zoom, as they always
have been.

That is now an inconsistency inside one picture. It is left alone deliberately —
A.29 changed enough at once without also changing how the existing lines behave,
and the fat lines have never been complained about. Fixing it means moving the
route drawing out of the scaled layer the same way, which is a change to a
picture that currently works and should be made on its own.

---

### 3.15 Overpass is a volunteer service, and a full prefetch leans on it

„Karte vorladen" over the whole history is about forty requests. The client is
polite by construction — one request at a time, two seconds between them,
`Retry-After` honoured, backoff on 429 and 504, and three instances tried in
turn — and the job is resumable, because every completed tile is on disk before
the next request goes out. None of that helps if all three instances are down,
which is the price of this data source and not a bug to be fixed.

Two smaller things sit under this heading:

- **Nothing warns that a two-year-old tile is two years old.** Deliberate: a nag
  about scenery would be worse than stale scenery. The per-tile timestamp is
  stored, so a refresh is possible when it is wanted; `karte --refresh` is the
  laptop-side way to take it.
- **Water relations are not resolved.** Lake Garda is an OSM multipolygon and
  this treats member ways as independent rings, so a large lake may show a seam
  and an island renders as water. Acceptable on a dim backdrop; noted here so it
  arrives as a known simplification rather than a bug report.

### 3.16 The auto-stop has never met a forgotten activity

The three rules that end a recording by themselves (Appendix A.30) do the two
most irreversible things in the app: they stop a workout, and they throw away
the end of it. Their thresholds — three quarters of an hour inside a 30 m
circle, a quarter of an hour without a usable fix, ninety seconds above the
sport's ceiling — come from the geometry of the problem, from what the two
mistakes cost, and now from one run over the real history. §2.1 already says this about every other constant here;
these are the ones where being wrong is visible to the user and cannot be undone
from the wrist.

Two questions, and a command that answers the first one on a laptop:

```bash
./gradlew :core:run --args="autostop --archive takeout/hecate-sicherung-*.zip"
```

**How often would it fire on workouts that were not forgotten?** It prints a
line per session and a count at the end, and the answer has to be zero. A hike
with a long lunch, a bike ride with a café stop, a ski day full of lifts and a
run through a city with poor reception are the four that would say otherwise,
and the real history is the only place they exist.

Run over the 73 sessions of the September 2026 backup, the first guess of
fifteen minutes stationary fired on ten — six of them walks and hikes ended at a
break with hours still to come (a half-hour summit rest, a 25-minute stop four
hours into a seven-hour walk). At forty-five it fires on four: three drives
home, caught by the speed rule, and one recording (gh-31923) whose 75-minute gap
without samples mid-walk is ended as Stillstand with 0.75 km of walking after
it. That one is the remaining open case: a gap that long in a Google recording
is most likely a pause that the export does not mark, which a manual pause on
the watch would shield.

**Does it fire when it should, on a wrist?** Nothing yet has left a watch
recording on a kitchen table for twenty minutes, or driven home with a ride
still running, and looked at what came back: the `AUTO_STOP` event and its
reason in session detail, the distance and duration cut back to where the
activity ended, and the positions after it absent from the database. The replay
harness reaches the same verdicts on the same samples — the rules read sample
offsets and never a clock — so the emulator can rehearse it (`tools/emu replay`
of a session that ends in a car will now end the replay), but the ninety-second
watchdog and the haptic have only been read, not felt.

Until the second is done, treat a stop that surprises you as a threshold to
change in `Tuning` rather than as a fault. Note what a mis-tuned stop costs:
the cut is applied at ingest, so the part before it is safe, but the watch has
*stopped recording* — whatever the user does after a false stop is not in the
file at all unless they start a new one.

---

## 4. Things deliberately not done

Everything in §8 of the specification, as specified: route variant hierarchies,
automatic session start, the route graph, Google Health API integration, maps,
charts, localization, and the rest of §8.5.

Three more worth naming explicitly, because they look like oversights:

- **Route priority does not vary by weekday or time of day.** The obvious
  extension, since the start-of-session prior already buckets by exactly that,
  would be a per-route map of context key to priority, resolved once at session
  start and falling back specific-to-general (this weekday-and-time bucket → all
  weekdays → the base value). It was designed and costed and then declined: one
  number per route is what gets used, twelve cells per route is what gets left at
  the default, and the automatic variant — ranking contenders by how often each
  was taken in this context, which the bundle already carries — follows habit
  rather than intent and is confidently wrong on exactly the days you do
  something unusual. The simple version is the decision, not a placeholder for a
  better one.
- **No phone-side recording.** §8.5 defers it. The phone app has no record
  button and the manifest asks for no location permission.
- **~~No network permission anywhere.~~** This one was taken, consciously, and
  is written up as Appendix A.29. The **watch manifest is untouched** and stays
  offline. The phone now declares `INTERNET` and uses it for exactly one thing:
  fetching OpenStreetMap geometry for ground already recorded, in tile-grid-
  aligned rectangles, on request. No recording, position, time or identifier
  leaves either device, and no dependency was added — `HttpURLConnection` is in
  the platform. What it does cost is in §3.13 below.
