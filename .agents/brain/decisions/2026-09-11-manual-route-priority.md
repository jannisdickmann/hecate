# ADR: Manual route priority breaks ties on shared ground

**Date:** 2026-09-11
**Status:** Accepted — range rescaled to −2..2 by A.21; its place in the naming
order (after seniority, and holding a name rather than re-deciding it every
sample) is superseded by
[2026-10-10-one-named-route-and-everything-read-off-it.md](2026-10-10-one-named-route-and-everything-read-off-it.md)
**ID:** A.4b (formerly SPECIFICATION.md Appendix A)

## Context

Not in the specification at all; added after §8.1's consequences were looked at
in practice.

§8.1 makes shortcuts independent routes that happen to share a prefix, and A.2
stops the matcher choosing between routes while they coincide. Together these
are correct and unhelpful: on a route family sharing 3 km of a 5 km run, the
watch shows `Suche…` — and no remaining distance and no ETA — for most of the
workout. The matcher is right that it cannot know. The user usually does.

## Decision

Every route carries an integer priority, settable on the route detail screen and
stored alongside the name (`route.priority`, preserved across re-clustering like
the name, A.5). Where two or more routes are *contenders* — live, being tracked,
and within `COMMIT_SEPARATION_M` of the nearest one — and exactly one of them
outranks the rest, the matcher commits to that one and reports remaining
distance and ETA from it. A tie at the top decides nothing and leaves the
display searching, so the default (every route at 0) reproduces the previous
behaviour exactly; the feature is opt-in by construction rather than by a flag.

The range was 1..10 until schema v7 rescaled it (A.21) to −2..2: only the
*order* of these numbers has ever mattered, so ten values offered a precision
the mechanism cannot use, and a neutral value of 5 out of 10 invited the user to
spend the other nine. The five positions read as *ganz hinten, eher nicht,
keine Meinung, eher schon, ganz vorn*; since A.22 the app shows only the
numbers, and the meaning is stated here and in the README.

## Consequences

Priority does not touch the probabilistic weights, and cannot make the matcher
believe the user is somewhere they demonstrably are not:

- It only ever selects among routes that already explain the current position
  equally well. On a novel path there are no contenders and nothing to select.
- It was re-decided every sample for as long as the tie lasted. The moment the
  paths part, the ordinary evidence rule takes over and will demote a
  priority-held commitment that the ground contradicts — so preferring the long
  route costs about twenty seconds of wrong label on the day you take the
  shortcut, and nothing else.
- `MatchState.committedByPriority` and the `COMMIT_BY_PRIORITY` log event record
  which of the two happened. The watch shows an assumed route in blue and a
  worked-out one in green, so a glance distinguishes a finding from a setting.

Making commitment possible inside a shared prefix exposed a false deviation
alarm on taking a learned shortcut, fixed by A.4c.

A.31 found that "held only while the tie lasts" was the weak point: every
commute committed to the ranked route at 0.4 km, let go at the first place the
medoids differed, and then committed by weight — GNSS noise. Priority now ranks
after seniority, and the named route is replaced only by a senior route ranked
higher. What priority means in use is open point §2.3.
