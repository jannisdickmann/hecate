# ADR: Watch updates sent from the phone

**Date:** 2026-11-02
**Status:** Accepted
**ID:** A.35

## Context

A.34 made the phone notice a newer watch release, but installing it still
needed `adb` from a computer. The user wanted to update the watch from the
phone alone, without Google Play.

Google's supported path for a companion app is Play: since Wear OS 2.0 a
phone app no longer installs its watch app; Play updates each device, and the
phone may only open the Play listing on the watch (`RemoteActivityHelper`).
Outside Play there is no official mechanism. Third-party tools (WearLoad)
show the public APIs suffice: send the APK over the Data Layer, install it
with `PackageInstaller` on the watch.

## Decision

- **The phone downloads, the watch installs.** Tapping the watch-update
  notification, or "Auf Uhr" in Settings → Version, runs
  `WatchInstallWorker`: download the release's APK, open a `ChannelClient`
  channel on `/hecate/apk` to every connected node, stream the file. The
  watch stays offline (NFR-7 unchanged).
- **The watch keeps only a newer build of itself.** `WatchUpdate.verify`
  accepts the APK only if its package is `de.hecate`, its version code is
  higher than the installed one, and its signing certificates equal the
  installed app's. Anything else is deleted on arrival.
- **Installing is the user's tap, never during a recording.** A notification
  on the watch opens `UpdateActivity`, which refuses while the recorder is not
  idle (installing replaces the process), then commits a `PackageInstaller`
  session. The system's confirmation is an activity, so the session result
  comes back to `UpdateActivity` itself, which has the visible window needed to
  start it.
- **`USER_ACTION_NOT_REQUIRED`** is requested: once Hecate is its own
  installer of record, Android may skip the confirmation for later updates.
- The watch declares `REQUEST_INSTALL_PACKAGES`; the user allows "install
  unknown apps" for Hecate once.

## Consequences

- The first build carrying this must still reach the watch another way (adb,
  or a tool like WearLoad); every later one comes from the phone.
- If Wear OS offers no settings screen for "install unknown apps", the grant
  is one adb command, once:
  `adb -s <watch> shell appops set de.hecate REQUEST_INSTALL_PACKAGES allow`.
- Not yet seen on a watch: the installer's behaviour on Wear OS 6 —
  confirmation dialog, the unknown-sources screen, whether the self-update
  later skips the confirmation — is untested until the first real transfer.
- An APK is ~30 MB over Bluetooth if the watch is not on Wi-Fi; minutes, not
  seconds.
