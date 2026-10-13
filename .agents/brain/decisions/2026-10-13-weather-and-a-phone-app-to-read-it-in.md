# ADR: Weather for every recording, and a phone app to read it in

**Date:** 2026-10-13
**Status:** Accepted
**ID:** A.33

## Context

The user asked for the weather — symbol, precipitation, temperature — every
ten minutes of every recording, from an open-source API, shown with the
activity on the recording screen. In the same request: make that screen a
pleasure to analyse with, then do the same for the rest of the phone app.

Weather needs a position and a time to be asked about. NFR-7 said that no
recording, position or time ever leaves either device, and the watch has no
network at all. The user was asked and chose: **the phone fetches it
afterwards**, for all history, from Open-Meteo.

## Decision

**Open-Meteo, after the fact, from the phone.** `de.hecate.core.weather`:

- `WeatherPlan.probes` — a probe every 10 minutes of wall clock (the weather
  does not pause with the recording), plus the finish when it is at least five
  minutes past the last tick, at the position nearest that moment.
- Positions are rounded to `GRID_DEG` = 0.02° (≈2.2 km N–S) **before** they
  leave the phone, and deduplicated. That is the grid of ICON-D2, the finest
  model Open-Meteo serves here, so nothing is lost by it — and it collapses a
  10 km run into a handful of squares.
- **One request per recording**, carrying all its squares and **dates only**
  (`start_date`/`end_date`), never a time of day. The time is applied on the
  phone, to the answer.
- Since 2022: the Historical Forecast API, `minutely_15` (archived
  high-resolution runs). Before: the Archive API, `hourly` (ERA5, back to 1940)
  — a takeout reaches back years.
- Sums are over the step *ending* at its stamp, so precipitation and the code
  come from the first step at or after a moment; temperature is interpolated
  between the stamps around it. Precipitation is stored as mm/h.
- `WeatherClient` is a one-method interface, like `OverpassClient`: no test
  opens a socket. Fixtures are two real responses.

**Phone.** Schema v10 adds `session_weather` (payload JSON plus a summary in
columns, so the list can show a symbol per row without decoding). A row with no
readings is an answer ("nothing to ask about"); a failure writes no row, so the
recording is asked about again. `WeatherWorker` backfills every recording
without a row, newest first, 500 ms apart, on any network; after ingest and on
every resume; a failure or a 429 retries with backoff. **Not in the backup**:
it is derived and is fetched again after a restore.

**A switch, unlike the map.** A.29 has no on/off for the basemap because
deleting the data is the off switch and what it sends is a tile square. This
sends where you were on which day, at 2 km — so it gets a switch in Settings
(`meta.weather_enabled`, absent = on) and a sentence saying exactly what is
sent. Attribution (CC BY 4.0) is under every weather strip and in Settings.

**The recording screen** became an analysis screen:

- one **cursor** for the whole screen: press or swipe any chart (altitude,
  speed, heart rate, weather) and every chart marks the moment, the weather
  strip lights its slot, the map shows a dot, and a panel slides in with a
  mini-map and every figure *then*. `Analysis.Moment` translates a moment
  between the time axis and the distance axis;
- a hero (title from the time of day — "Morgenlauf"), a figure grid, a route
  card that answers "how did today compare" (place, delta to median, every
  member as a dot on one line);
- **time in effort bands** and **splits of this recording** (1 km, 5 km above
  25 km), from `Analysis` in `:core`. These are not the route-median splits A.21
  removed: they come from one recording's own samples, cost nothing, and cannot
  contradict another column.

The rest of the app was redone in the same language (`Design.kt`: one card,
one section header, one figure cell; `Glyphs.kt`: drawn weather and figure
icons — no icon library, A.11): a week card with twelve weeks of bars and
month headers with totals on the list, trend sparklines per route, a "how is
it going" chart on the route screen, settings as titled cards. Screens are
split one per file and take state, not the view model, so that
`ScreenshotTest` renders every one of them on the JVM from synthetic data.

## Consequences

- NFR-7 is narrowed a second time: the phone now also sends dates and
  2 km squares to Open-Meteo, switchable. The watch is untouched.
- A history of a few hundred recordings is a few hundred requests, well inside
  the free tier per day; a 429 just waits for tomorrow.
- **Unverified on a device**: the whole UI has been seen only in Robolectric
  renders (no basemap tiles, no heatmap raster there), and no request has been
  made from a phone. The renders are in `phone/build/screenshots` after
  `./gradlew :phone:testDebugUnitTest --tests '*ScreenshotTest*'`.
