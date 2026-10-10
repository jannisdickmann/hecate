# ADR: A settings panel, a back stack, and what a fast run is

**Date:** 2026-09-12
**Status:** Accepted — the standout threshold moved from the 75th to the 85th
percentile in A.21
**ID:** A.13 (formerly SPECIFICATION.md Appendix A)

## Context

A set of changes to the phone UI (and one to the watch), all from using the
thing. Grouped because they share a cause: §5.4 specified which screens exist
and said nothing about how to get between them or what they should be called.

## Decision

**Settings is a panel, not two overflow menus.** TCX import, backup export and
backup restore lived behind a `⋮` on the recordings screen; re-clustering lived
behind a separate button on the routes screen. Neither was discoverable, and a
menu item has room for a label but not for an explanation — which is how
"Health Connect einrichten" came to be a button nobody could explain. The panel
costs one slot in the bottom bar and gives every action a sentence.

**Back walks the panels.** The old `BackHandler` was armed only on the detail
screens, so pressing back from a panel the user had reached one tap earlier fell
through to the launcher and closed the app. The view model keeps a bounded
history and `back()` reports whether it handled the press, leaving the system to
close the app only at the end of it. Bounded at 16 because this is a
convenience, not an undo log: someone who has tapped between two panels forty
times wants the next few back presses to work, not all forty. Deleting a
recording drops that recording's detail screen from the history as it goes —
otherwise back leads to a screen about something that no longer exists.

**Re-clustering shows that it is running.** Clustering is O(n²) over every
recording (open point §2.6), so on a real history the button appeared to do
nothing. WorkManager already knows the state of the unique job; the panel
watches it and swaps the button for a spinner, and the routes list grows a
banner, since that list is where the effect actually lands. The button is
replaced rather than disabled: the job is enqueued with `REPLACE`, so a second
press would restart it from the beginning rather than queue anything.

**"Sitzung" became "Aufzeichnung".** User-facing strings only. `session` stays
the wire format, the table name, the archive's directory and the watch's
filename — renaming it downward would change the backup format in order to
relabel a screen. `Screen.Recordings` is the boundary.

**Every screen keeps its scroll position.** Screens are swapped by a `when`, so
the one being left drops out of composition and everything it remembered goes
with it — which meant a back press out of a recording landed at the top of a
year of them, and the same on the way out of a route. A `SaveableStateHolder`,
keyed per screen, keeps each key's saved state alive while its composable is
gone; it is the mechanism Navigation-Compose uses for exactly this, and nothing
inside the screens had to change, because `LazyColumn`'s own state and
`rememberScrollState` were already saveable and were simply being saved into a
slot that no longer existed. Detail screens are keyed by id rather than by
kind: two recordings scrolled to different places are two different places.

**Delete asks first, and says what goes.** The dialog names the recording's
date, distance and duration, and then what is destroyed with it — samples, match
log, recording history — and what is not: the Health Connect entry stays.
Deleting the only complete copy of a workout on one tap was the last unguarded
irreversible action in the app.

**The watch says how many routes it has and how old they are**, not which
version of the bundle it is holding. The version is a counter the phone bumps on
every rebuild, so "v42" answers nothing a person wants to know before pressing
start; "4 Routen · vor 3 Tagen" answers all of it. The counter still exists and
is still what the Data Layer item and the session header are keyed on — it is
simply not a fact about anybody's day.

**Activity types have icons.** One vector drawable per type in `res/drawable`,
the official Material Symbols paths from `google/material-design-icons`, shown
on both lists and both detail screens. Not `material-icons-extended`, for the
reason A.11 gives: release builds run with `isMinifyEnabled = false`, so an
unused icon is an icon that ships. Symbols are drawn in a 960×960 box whose
origin is the baseline — the viewBox is `0 -960 960 960` — and Android's
viewport has no negative origin, so each drawable wraps its path in a
`<group android:translateY="960">` rather than having the path rewritten by
hand, which would make the next update a transcription exercise. The lookup is
over the enum rather than over its name, so a type without a drawable is a
compile error rather than a blank space at run time — which is what it used to
be, back when the icon was chosen by matching the string `"RUNNING"`.

**Fast and hard recordings are marked.** A recording is marked fast when its
average speed is at or above the 85th percentile of the recordings on the same
route — the 75th until A.21 raised it and gave the mark an arrow — and marked
for effort when its average heart rate is.

- **Per route, or not at all.** A recording with no matched route has nothing to
  be fast against and is never marked. Ranking is within the route, so the best
  climb of a slow hilly route is marked even though every recording of the flat
  route beat it.
- **Four members minimum.** A route exists at three (§5.7), and a percentile of
  three values is "the best one" wearing a statistician's hat. Four is the first
  count at which the threshold falls between two recordings rather than on one,
  and even then only the best of them clears it — this marks a personal best
  against the route and claims no more.
- **Speed and heart rate rank independently**, because a hard effort on a slow
  day is worth seeing precisely when the two come apart. Heart rate ranks only
  over the members that have one, and only if at least four do: a route whose
  older recordings predate a heart rate strap must not let those absences drag
  the threshold down to where everything qualifies.

The arithmetic lives in `ui/Standouts.kt` rather than in the view model, with no
Android in it, and has eleven tests. It is the only arithmetic in the phone UI
and the kind whose bugs hide well — a wrong threshold still highlights
*something*. The emphasis is colour **and** weight, never colour alone: the two
marks sit in the same row and have to be tellable apart by someone who cannot
separate the hues.

**Health Connect is a switch.** It cannot be made automatic, and that is not a
limitation of this app: Android health permissions are granted by the user
through a system-owned sheet, and `PermissionController`'s only entry point is
an `ActivityResultContract`. So the action stays, but it moves to settings, says
what it does, and gains the half it never had — `revokeAllPermissions()` turns
it off. A one-way "einrichten" button left the user to go and find Android's own
settings to undo it, which is most of why it read as inexplicable. Four states
rather than a boolean, because "off" has three different causes that need
different words: not installed, too old, or simply not granted yet. The state is
re-read on every `onResume` and whenever the panel appears, since both the grant
and its removal can happen outside this app.

## Consequences

- The back stack is the reason the full-screen heatmap is a `Dialog` rather than
  a fourth screen (A.21), and the restored scroll position is the reason the
  activity filter lives in the view model (A.21).
- A.19 found the watch's route count reading the wrong source; A.27 later
  separated "no routes" from "new route".
- The standout percentile is `Standouts.PERCENTILE`; how it fares on more than
  one history is open point §3.7.
