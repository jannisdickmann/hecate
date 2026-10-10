# The two emulators are not paired, and nothing decides whether they should be

**Status:** Open
**Kind:** Needs a decision
**Discovered:** 2026-09-13
**ID:** OP-2.9 (formerly OPEN-POINTS.md §2.9)

## Problem

`tools/emu` boots a watch and a phone that cannot talk to each other. The
Wearable Data Layer is not a socket the app opens: it is Play services on both
nodes, brokered by the Wear OS companion app, and without a pairing there is no
node, so `DataClient` publishes into nothing. That is why spec A.19's pass could
cover every screen and no transfer, and why `tools/emu bundle` exists — on a
wrist the phone sends the bundle; here it is put there by hand.

What pairing would take, as far as anyone here knows (the third point unverified):

- A **Google Play** phone image, because the companion app comes from the Play
  Store and is not legitimately sideloadable — which means a Google account
  signed in on the emulator, against the grain of the rest of this setup.
- A watch image with Play services, which the standard `android-wear` images have.
- A **TCP bridge on port 5601**. The documented spelling covers a Wear emulator
  and a *physical* phone (`adb -d forward tcp:5601 tcp:5601`); for two emulators
  on one host it is a forward on the watch plus a reverse on the phone, and nobody
  here has run it. Android Studio's Device Manager has a pairing assistant that
  does this and drives the companion app, with no documented command-line
  equivalent — so either install Studio once or get the bridge right by hand.
  Either way it dies with the emulator, and `tools/emu up` would have to
  re-establish it.
- Both APKs sharing an `applicationId` and a signature — already true
  ([watch-phone-link-has-no-retry-but-the-next-workout.md](watch-phone-link-has-no-retry-but-the-next-workout.md)),
  and `tools/emu install` satisfies it with one debug keystore.

## Impact

**What pairing would buy:** the transfer path, which nothing can currently
exercise — session upload, the acknowledgement, `markAcked` deleting the file, the
bundle coming back, and both gaps in
[watch-phone-link-has-no-retry-but-the-next-workout.md](watch-phone-link-has-no-retry-but-the-next-workout.md).
Possibly Health Connect write-back on the phone emulator too, though a Play image
is likely needed there as well — unchecked.

**What it would not buy:** matching. The watch's sensor data would still come
from the synthetic provider and its one default route.

## Workaround

Matching is covered instead by `tools/emu replay` (spec A.28), which needs no
pairing — which is exactly why this is a decision rather than a blocker. The
transfer path is verified only on the real devices.
