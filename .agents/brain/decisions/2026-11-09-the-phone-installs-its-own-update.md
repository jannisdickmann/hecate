# ADR: The phone installs its own update

**Date:** 2026-11-09
**Status:** Accepted
**ID:** A.36

## Context

A.34 made the phone notice a newer phone release; a tap then downloaded the
APK in the browser (as Nexus does), and the user opened it from the downloads
and installed it over the app. A.35 gave the watch an install path through
`PackageInstaller`, which worked on the first real transfer. The user asked
for the same on the phone: no browser, no downloads folder.

## Decision

- **Download in the app, install through a session.** The phone-update
  notification's tap and **Installieren** in Settings → Version start
  `SelfUpdateWorker`: a foreground worker (`dataSync`) that downloads the APK
  with a progress bar, checks it (`SelfUpdate.verify`: this package, newer than
  installed, same signing certificates) and commits a `PackageInstaller`
  session with `USER_ACTION_NOT_REQUIRED`.
- **The tap is a broadcast** (`SelfUpdateReceiver.ACTION_START`), like the
  watch's: since Android 12 a notification may not reach an activity through
  a receiver, and starting work needs none.
- **The installer answers to the same receiver.** When it needs the user
  (`STATUS_PENDING_USER_ACTION`) the receiver posts "Update installieren",
  whose tap opens the system confirmation, and also tries to open it directly,
  which Android allows while Hecate has a visible window — the usual case
  after a tap in Settings. Success and a cancelled confirmation dismiss the
  notifications; anything else is "Installation fehlgeschlagen".
- **The grant comes first.** Without "install unknown apps" for Hecate, the
  Settings button opens that screen; from the notification, the worker posts
  "Installation nicht erlaubt", whose tap opens it.
- A release without an APK still opens its page.

## Consequences

- The first self-update asks the user twice: the grant, and a confirmation —
  the browser or file manager that installed the current build is its
  installer of record. After that Hecate is its own, and Android may install
  later updates without asking. Not yet seen on a device; see also A.35's
  open question for the watch.
- Installing replaces the process, possibly before WorkManager records the
  worker as done. A rerun in the new build finds the APK no newer than itself
  (`Verdict.NOT_NEWER`) and ends without a notification.
- The phone declares `REQUEST_INSTALL_PACKAGES`. Hecate is not on Play, where
  that permission needs a declaration; nothing else changes.
- The check duplicates the watch's (`WatchUpdate.verify`): `core` is plain
  Kotlin without Android, so the two copies stay in their modules.
