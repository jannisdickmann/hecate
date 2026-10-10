# ADR: Material 3 on both ends, and one watch page instead of two

**Date:** 2026-09-12
**Status:** Accepted
**ID:** A.11 (formerly SPECIFICATION.md Appendix A)

## Context

§4 selected "Compose for Wear OS, `HorizontalPager`, 3 pages" for the watch and
"Compose Material 3, 3 screens" for the phone. Both rows were true in outline;
what they left unsaid is what needed fixing.

**The phone was never themed.** `HecateApp` called `MaterialTheme {}` with no
arguments, which yields the baseline purple placeholder scheme, and then every
composable under it overrode that scheme by hand: `Color.Gray` for secondary
text, `Color(0xFF00796B)` for a matched route, `Color(0x14000000)` for an
unselected chip, `Color(0xFFC62828)` for delete, and font sizes as bare `sp`
literals. The app was on Material 3 in its dependency list and nowhere else, and
the consequence was not only that it looked a decade old: hardcoded colours have
no dark variant, so there was no dark mode at all.

**The watch was on Material 2.5.** `androidx.wear.compose:compose-material` is
the previous generation, and its `Chip` — which was the start screen and half
the control page — is the single most dated thing the app drew.

**Three watch pages hid the point.** The Live/Route split meant that "how am I
doing against the usual" — US-5, the one question this app exists to answer —
lived on a page you had to swipe to. On a bike that is a hand off the bars.

## Decision

**Phone:** a `HecateTheme` built on `dynamicDarkColorScheme` /
`dynamicLightColorScheme`. `minSdk 34` is well past the API 31 floor for dynamic
colour, so there is no version fence and no hand-written fallback palette to
drift out of sync. Every colour in the phone UI is a scheme role and every size
a typography token. Dark mode follows the system for free.

Two colours deliberately did **not** become roles. The start and end markers on
the route drawing say which end of a track is which, which is a fact about the
track; a wallpaper must not be able to recolour one into the other. They live in
`HecateMapColors` and are fixed. The same rule governs the watch's status
colours in `HecateStatus`: "faster than usual" is green on every watch face.

**Watch:** `androidx.wear.compose:compose-material3`, which needed no new
version line in the catalog: Material 3 ships at the same `wearCompose` version
as the rest of the group. It brings `AppScaffold`/`ScreenScaffold`, and with
them `TimeText`; the app previously showed no clock at all, which on a watch is
conspicuous.

**Three pages became two, and this is a behaviour change, not a restyle.** Live
and Route are one dense page: route name, duration, distance, speed, heart rate,
delta, remaining and ETA, all visible at once. Control keeps its own page, and
keeping it there is the point — pause and stop are the two actions a sleeve must
not trigger, so the swipe that used to be a cost is now the guard.

The dense page is laid out against a height budget set by the smaller watch: a
41 mm face is 204 dp tall, `TimeText` takes the first 26, and the six rows have
to live in the remainder. That is what decides the two-column grid, the inline
12 sp units instead of a label line under each number, and the secondary pair
(heart rate, delta) being set smaller than the primary one (distance, speed).
The delta earns its rank by colour rather than by size. The column can still
scroll, but only so that a face smaller than either of these clips nothing —
scrolling is not part of the design.

`HorizontalPagerScaffold` replaced the hand-rolled `PagerDots` with the curved
indicator that fades out on its own, and `EdgeButton` gives Beenden the
bottom-bezel shape, both of which were pixels the numbers wanted back.

## Consequences

**What was declined.** Material 3 Expressive — `MaterialExpressiveTheme`,
`MotionScheme`, `ButtonGroup`, `LoadingIndicator`, the Flexible app bars — is
compiled into material3 1.4.0, which is what Compose BOM 2026.09.00 pins, but
every piece of it is `internal` at that version and only becomes public in the
1.5.0 alphas. Taking it means overriding the BOM with an alpha across the whole
module, which §7 pins against, for what is here a matter of styling. The stable
API gets the same dynamic colour and the same type scale. Also declined:
`material-icons-extended`, because release builds run with
`isMinifyEnabled = false` (§6.3) and would ship every unused icon — the ~40 of
`material-icons-core` cover every icon this app needs; and
`material3-adaptive-navigation-suite`, which earns nothing on one phone.

NFR-8 is untouched: every artifact added here is AndroidX. The only new
transitive artifact anywhere is `androidx.graphics:graphics-shapes`, which Wear
Material 3 depends on. §4's "no navigation library" also still holds — Wear
Material 3 does not drag one in, and the phone still switches screens on a state
enum.

"A wallpaper must not be able to recolour a fact" became a standing rule: the
effort bands (A.21) and the basemap palettes (A.29) are fixed literals for the
same reason. The live page later gained two edge bars and a health line without
breaking this height budget (A.27); whether it is legible on a wrist is open
point §3.6.
