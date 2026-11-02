# ADR: Released from GitHub, and an update notification on the phone

**Date:** 2026-11-01
**Status:** Accepted
**ID:** A.34

## Context

Both APKs were built on a laptop and installed with `adb`. The user asked for
the update mechanism the Nexus Android apps have (`numynox/nexus`,
`android/core/data/.../UpdateChecker.kt`): CI publishes each signed APK as a
GitHub release, and the installed app notices a newer one and points at it.
Here it must cover two APKs on two devices, phone and watch, and only the
phone may notify — the watch has no network (NFR-7) and no browser to open a
release page in. Builds of the two should be independent and run only when
needed; nothing may be published without the signing secrets.

## Decision

**CI, one workflow per APK.** `.github/workflows/android-phone.yml` and
`android-wear.yml` each trigger on their own module, `core/`, and the shared
Gradle files, and call `android-app.yml`, which tests, lints, builds and — on a
push to `main` with `ANDROID_KEYSTORE_BASE64` set — publishes. The secrets are
named as in Nexus. Without the keystore secret the APK is built unsigned, kept
as a 14-day artifact, and no release is made; with it, same-repository pull
requests are release-signed too, so their artifact installs over the app.

**Versions from the run number.** Each workflow's run number becomes its
APK's `versionCode` and the last part of `versionName` = `1.0.<n>`
(`HECATE_VERSION_CODE`, read in both build scripts; local builds are 1).
Releases are tagged `phone-v1.0.<n>` and `wear-v1.0.<n>`, with
`--latest=false` so neither becomes the repository's "latest" over the other.
Phone and watch therefore count separately; they share an `applicationId`
but live on different devices, so the two numbers never meet.

**The check is the phone's, for both.** `de.hecate.core.update.Releases` reads
GitHub's release list (`/repos/jannisdickmann/hecate/releases`, unauthenticated)
and picks the newest non-draft, non-prerelease tag per prefix by version code —
the Nexus logic, per target. The watch publishes its installed version as a
Data Layer item (`/hecate/watch-version`) on every process start; the phone
reads it when it checks. `UpdateWorker` runs daily on any network, once on
every app start, and once whenever the watch's item changes.

**A notification per release, not per check.** One notification per target,
opening the release page; the version code last announced is kept in `meta`
(`update_notified_phone` / `_watch`), so a swiped notification does not come
back tomorrow. Without the notification permission nothing is recorded, and
the next check tries again. The Settings card **Version** shows installed and
available versions for as long as they differ, with the release page one tap
away — the equivalent of Nexus's banner.

**No switch.** Unlike the weather (A.33), the request carries nothing about
the user — no position, time or identifier, only the IP address any request
has and a generic User-Agent — which is A.29's reasoning for the basemap.

**`:wear`'s session header** now records the installed `versionName` from the
package rather than a hand-maintained constant (`RecorderService.BuildInfo`,
gone).

## Consequences

- **The GitHub secret must be the existing Hecate keystore** (OP-1.1), not
  Nexus's. A release signed with any other key cannot update the installed
  app; installing it means an uninstall, which costs every session the watch
  has not uploaded.
- **A local build cannot replace a released one.** It is `versionCode` 1, and
  Android refuses a downgrade without an uninstall. Once CI builds are
  installed, either install CI builds only, or build locally with
  `HECATE_VERSION_CODE` above the installed one.
- **Do not rename `android-phone.yml` or `android-wear.yml`**: a new file name
  starts its run number at one.
- A watch running a build older than this change never reports its version;
  the phone then does not notify about the watch, and the Settings card shows
  the newest watch release with "noch nicht gemeldet" instead.
- (Superseded in part by [A.35](2026-11-02-watch-updates-sent-from-the-phone.md):
  the phone now sends the APK to the watch.) Installing on the watch is still `adb` over wireless debugging; the release
  page is where the APK comes from, not a way to install it.
- NFR-7 gains a third use of the phone's network.
