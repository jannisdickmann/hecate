# Glossary

What things are called in code, in the German UI, and in this brain. The UI is
German and not localised ([§4](architecture.md#technology-choices-4)); the code
and the wire formats are English. When the two differ, the code name is not
renamed to match the screen — the boundary is stated below.

## Recordings and their files

| Code / brain | UI (German) | Meaning |
|---|---|---|
| session | **Aufzeichnung** | One recorded workout. `session` stays the wire format, the table, the archive directory and the watch's filename; only user-facing strings say *Aufzeichnung*, and `Screen.Recordings` is the boundary ([A.13](decisions/2026-09-12-settings-panel-back-stack-and-standout-marks.md)). Log lines still say *Sitzung*. |
| sample | *Messpunkt* | One position record in a session file (one per GNSS position, at ~1 Hz). |
| heart-rate reading | *Pulswert* | An `"r"` record — its own series since [A.26](decisions/2026-09-16-heart-rate-is-its-own-series.md), joined to samples at ingest. |
| event | *Verlauf der Aufzeichnung* | A lifecycle record (`SessionEventType`: pauses, GPS lost/acquired, service restart, matcher disabled, auto-stop), shown on recording detail ([A.12](decisions/2026-09-12-session-lifecycle-events-are-kept.md)). |
| match log | — | The matcher's per-sample weights and commit/demote events, stored with every session (US-10). |
| footer, cut | — | The session's last record; `trimmedAfterTOffsetMs` is the auto-stop cut, recorded rather than applied ([A.30](decisions/2026-09-21-a-recording-ends-itself.md)). |
| auto-stop | *Automatisch beendet — Stillstand / Kein GPS / Zu schnell* | The recording ending itself; the three reasons are distinct because they send the user to look at different things. |
| imported | — | `source` of a session that came from TCX or a takeout; never written to Health Connect, and subject to start-time duplicate checks. |

## Routes and matching

| Code / brain | UI (German) | Meaning |
|---|---|---|
| route | *Route*, *Strecke* | A cluster of ≥ 3 similar recordings, direction-sensitive and per exercise type ([clustering.md](clustering.md)). Auto-named like *Route 4 (Rad abends, 8,2 km)*. |
| medoid | — | The member whose track is the route's geometry. |
| profile | — | Per-10 m-bin medians (cumulative time, p25/p75, heart rate, altitude) over a route's members. |
| bundle | *Routen* | `route-bundle.json.gz`: the read-only routes the phone pushes to the watch, keyed by a version counter that the UI never shows (*4 Routen · vor 3 Tagen*). |
| null hypothesis | *Neue Strecke* | "None of the known routes" — a finding, not a failure ([A.27](decisions/2026-09-19-the-watch-says-what-it-knows.md)). Older code and text say `Unbekannt`. |
| — | *Noch am Vergleichen* | Nothing decided yet. Formerly `Suche…`. |
| — | *Noch keine Routen* | The bundle has no route of this exercise type: a fact about the phone, not about today. |
| contender | *Arbeit · Waldrunde* | A followed route within `COMMIT_SEPARATION_M` of the nearest one; several are shown together while indistinguishable. |
| provisional | *≈ Weg zur Arbeit* | A leader named as a guess. |
| committed / named | *Weg zur Arbeit* | The one route the numbers are read off ([A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md)). Green on the watch; blue when named by priority (`committedByPriority`). |
| joined, set off on | — | Where a route was first found under the user; *set off on* if that was at the start of the session ([A.31](decisions/2026-10-10-one-named-route-and-everything-read-off-it.md)). |
| priority | −2 … +2 | Tie-breaker among contenders: *ganz hinten, eher nicht, keine Meinung, eher schon, ganz vorn*; the app shows only the numbers ([A.4b](decisions/2026-09-11-manual-route-priority.md)). |
| deviation | *Abweichung* | The haptic alert for leaving the route network ([A.4c](decisions/2026-09-11-deviation-is-measured-against-the-route-network.md)). |
| ahead/behind, delta | — | Signed seconds against the route's median at the current position. |

## Exercise types

Each `ExerciseType` carries three German words for three jobs
([A.18](decisions/2026-09-13-hiking-and-skiing-and-exercise-types-as-enums.md)):
`label` (what the user taps — *Laufen, Spaziergang, Wandern, Fahrradfahren,
Skifahren*), `sessionTitle` (the noun Health Connect lists — e.g. *Radfahrt*),
and `routeWord` (inside an auto-generated route name — e.g. *Rad*).

## Phone screens and settings

| UI (German) | Meaning |
|---|---|
| *Einstellungen* | The settings panel: import, backup, Health Connect switch, re-clustering, map, peak heart rate ([A.13](decisions/2026-09-12-settings-panel-back-stack-and-standout-marks.md)). |
| *Sicherung exportieren / einlesen* | Write / restore a backup archive, `hecate-sicherung-<stamp>.zip` ([A.10](decisions/2026-09-11-backup-is-a-file-not-a-cloud.md)). Kept in `backup/` on the laptop. |
| takeout | A Google Takeout export, kept in `takeout/`; the importer's output goes to `takeout/parsed/` ([A.15](decisions/2026-09-12-backfill-from-a-takeout.md)). |
| *Karte* | The OpenStreetMap basemap; `grob` and `fein` are its two detail levels; *Karte vorladen* prefetches, *Karte einlesen* imports a laptop-built store ([A.29](decisions/2026-09-20-osm-basemap-under-every-track.md)). |
| *Belastung* | The peak-heart-rate setting (default 180 bpm) behind the four effort bands *Leicht, Mittel, Intensiv, Höchstleistung* at 70/80/90 % ([A.21](decisions/2026-09-13-filter-louder-mark-real-heatmap-no-splits.md)). |
| *Wiedergabe ×10* | Shown on the watch while an emulator replay runs ([A.28](decisions/2026-09-13-replaying-an-activity-into-the-recorder.md)). |
| *Beenden* | Stop, on the watch's Control page. |

A replay workspace — a `bundle.json` and a `sessions/` directory — lives in
`replay/<date>/`.
