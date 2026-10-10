# ADR: A takeout is a better backfill than a pile of TCX files

**Date:** 2026-09-12
**Status:** Accepted
**ID:** A.15 (formerly SPECIFICATION.md Appendix A)

## Context

§7 and US-9 assume the cold start is solved by TCX: export each workout from
Google Health, pick the files in the app, let the clusterer have them. That
works and it does not scale. Google hands out one TCX per workout behind a
per-activity URL, which for a year of commuting is several hundred downloads
that need an authenticated session; the export that requires no clicking is
Takeout, and Takeout contains no TCX at all. What it contains is a `tcxLink`
column pointing back at those same URLs.

## Decision

The backfill reads the takeout directly. `:core:run --args="takeout …"` turns
the ZIP — or the several ZIPs a large export is split into, which are separate
complete archives rather than one spanned one — into a backup archive in the
format of A.10, and the phone restores it through the path that already exists.
Takeouts are kept in `takeout/` and the converted archive is written to
`takeout/parsed/`; the flags are in the README, *Importing a Google Health
takeout*.

**The archive format moved to `:core`.** `Archive` and `ArchiveCodec` were in
`:phone` and neither had ever imported anything from `android.*`; only
`ArchiveRepository`, which maps Room rows to and from them, actually needs the
platform. A command-line tool cannot depend on an Android module, and defining
the backup format a second time inside the importer is how the two definitions
start to disagree. Moving the pair down also moved their tests to where nothing
about the Android toolchain can get between them and a laptop.

**The importer builds sessions, never routes.** The archive it writes has an
empty route table. Clustering happens on the phone at the end of the restore,
by the same `RouteClusterer` and the same thresholds as a route learned live, so
there is exactly one implementation of what counts as a route. A converter that
shipped its own clustering would be a second one to keep in step.

**What the export actually holds**, which took reading it to find out. The
authoritative list of workouts is `UserExercises_<date>.csv`: one row each, UTC
start and end, and — the thing worth the whole record — an explicit
`utc_offset` per exercise. The positions are elsewhere, in
`gps_location_<date>.csv`, at 1 Hz, in UTC, and not segmented by workout: the
import slices them by the exercise's window. The friendlier-looking
`exercise-0.json` beside them is a trap. It is paginated and capped — 237 rows
against the exercise table's 429 in the export this was built against — its
`hasGps` flag was false for fifteen days that do have position files, and its
`startTime` is `01/28/26 17:56:08` with no zone anywhere near it.

**Three duplicate mechanisms rather than one**, because there are three ways to
get the same workout twice:

| | caught by |
|---|---|
| the same takeout imported twice, or a later takeout that still contains it | session ids derived from Google's exercise id |
| a workout imported by hand as TCX and also present in the takeout | start-time proximity, `Track.likelyDuplicate` |
| a workout Hecate recorded, which the watch logged to Google Health on its own | the same |

The second and third cannot be caught by id — the two ends of the comparison
have unrelated ids and only the clock relates them — so the restore path applies
`Track.likelyDuplicate` to any session whose source is an import. This is a
deliberate narrowing of A.10's "merge by id and nothing else": a backup of the
app's own recordings is still restored exactly, and only an imported session can
be refused for looking like something already present. Being wrong in the
cautious direction costs one workout out of a backfill of hundreds. Being wrong
the other way gives the clusterer two members for one journey and quietly
inflates every route's member count, which is the number the UI reports and the
number `MIN_MEMBERS` tests.

**Not everything in the export can come in.** Workouts with no position file are
dropped — Google keeps the exercise summary long after it has dropped the day's
positions, so the oldest history is rows without tracks. Sports with no
`ExerciseType` are refused rather than filed under the nearest thing, because a
ski descent clustered as a run is a route nobody can ever run, and `--map`
exists for when the user wants the track anyway. (Skiing was one of those until
A.18 added it.) Auto-detected activities are taken only when they carry
positions and overlap nothing already taken. Every one of these is reported by
name and reason, which is the argument for this running on a laptop rather than
behind a button on the phone.

**Time zones, which were wrong before this and are now the one thing the export
is unambiguous about.** `TcxParser` stamped every session it read with
`tzOffsetMinutes = 0`. A TCX from Google Health is written in UTC and names no
zone, so the instants were right and the offset was a placeholder — and the
offset is what turns an instant back into the time of day it happened at. The
visible symptom was an imported summer workout displaying two hours early. The
invisible one was `RouteMatcher.contextKeyOf` bucketing it by the wrong hour, so
the context prior learned from a history shifted into the small hours.

The parser now believes an offset the file spells out, and otherwise resolves
one from a `ZoneId` — the platform default on a phone — *at the instant of the
workout*, so a January import and a July import differ by an hour as they
should. The takeout importer never needs the fallback: it uses the exercise's
own `utc_offset`, then the profile's zone, then Europe/Berlin.

## Consequences

Rows written by the old parser are repaired by migration v5, which is data-only
and touches only sessions that are both imported and sitting at exactly 0 —
and which shipped with the wrong column name, which is A.16.

That a converted takeout has been run but never restored on a real phone is
open point §3.2b.
