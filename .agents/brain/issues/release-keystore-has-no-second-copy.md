# The release keystore has no second copy

**Status:** Open
**Kind:** Blocking
**Discovered:** 2026-09-11
**ID:** OP-1.1 (formerly OPEN-POINTS.md §1.1)

## Problem

A release keystore exists and both modules install signed with it. It is the one
artefact in the project that cannot be regenerated: lose it and every future
build is a different app, which means an uninstall, which means losing whatever
the watch has not yet uploaded (spec §6.3). Nobody has yet made a second copy
and proved it restores.

Its coordinates live in `keystore.properties` at the repository root:

```properties
storeFile=/absolute/path/to/hecate-release.jks
storePassword=…
keyAlias=hecate
keyPassword=…
```

Both modules pick it up when the file exists and fall back to an unsigned
release build when it does not. `keystore.properties` and `*.jks` are
git-ignored.

Two traps learned creating it:

- **An incomplete `keystore.properties` blames the wrong field.** It fails as
  `Keystore file not set for signing config release` even when `storeFile` is
  set — the missing key was `storePassword`.
- **A missing file does not fail at all.** `assembleRelease` succeeds and quietly
  produces `phone-release-unsigned.apk`, which only refuses at install time.

## Impact

Loss of the keystore forces an uninstall on both devices, and with it the loss of
every session the watch holds that the phone has not acknowledged. **Upload
before reinstalling, every time.**

## Workaround

None yet beyond the one copy on disk.

## What closes it

A backup that has been restored once:

1. A `.jks` is binary, so it is not an "SSH key" entry in a password manager — it
   is an attachment, or `base64 -w0` into a secure note.
2. Store the alias and store type beside it, and the certificate fingerprint,
   which is what Android actually compares on upgrade:

   ```bash
   keytool -list -keystore <path>.jks -alias hecate       # SHA-256 fingerprint
   apksigner verify --print-certs <apk>                   # same value, from the APK
   ```

3. Restore it once, into a scratch path, and check the fingerprints match. The
   restore is the only part of a backup that can be wrong in a way you cannot see.
