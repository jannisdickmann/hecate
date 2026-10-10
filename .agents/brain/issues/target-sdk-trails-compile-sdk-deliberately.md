# targetSdk trails compileSdk deliberately

**Status:** Open
**Kind:** Needs a decision
**Discovered:** 2026-09-12
**ID:** OP-2.7 (formerly OPEN-POINTS.md §2.7)

## Problem

`compileSdk` is 37, which Compose BOM 2026.09 requires; `targetSdk` stays at 36
in both modules. compileSdk only decides which APIs compile; targetSdk opts the
app into new *runtime* behaviour — permission semantics, background limits.

## Impact

Raising it is a behaviour change, and the last one of those (the heart-rate
permission, see [no-instrumented-tests.md](no-instrumented-tests.md)) cost a
whole recording before anyone noticed.

## Workaround

Lint's `OldTargetApi` is disabled in `phone/build.gradle.kts`, with this
reasoning written at the suppression so the warning does not accumulate unread.

## What closes it

Raise targetSdk on a day someone can wear the result and watch for what changed.
