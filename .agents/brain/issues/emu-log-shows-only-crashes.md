# `tools/emu log` shows only crashes

**Status:** Open
**Kind:** Defect (tooling)
**Discovered:** 2026-10-10, during a code cleanup
**ID:** OP-3.19

## Problem

`tools/emu log` runs `logcat -s Hecate:V AndroidRuntime:E`, but no code logs
under the tag `Hecate`. The watch's tags are the class names — `RecorderService`,
`ExerciseSource`, `MatcherHost`, `SessionWriter`, `Uploader`, `PhoneListener`,
`BootReceiver`, `BundleStore`, `ReplayFeed`, `ReplayReceiver` — so the command
shows nothing but `AndroidRuntime` errors. The hint `tools/emu replay` prints
uses the real tags.

## What closes it

List the real tags (or filter by the app's pid). Left as found because the
cleanup that found it promised no change in behaviour.
