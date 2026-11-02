# Hecate

A route-learning companion for a Wear OS watch and an Android phone. Start with
`README.md`; the reasoning behind the code is in `.agents/brain/` —
`architecture.md` for structure, `decisions/` for why, `issues/` for what is
known to be open.

## Rules

- **UI text says what the user needs, nothing more — at best nothing.** No
  sentence that explains how the app works inside: what is sent where, what a
  job does in the background, why a button exists, what happens after a tap.
  A label, a value, a short status ("Wird geprüft…", "Prüfung fehlgeschlagen")
  is enough. The reasoning belongs in the code and in `.agents/brain/`, never
  on a screen or in a notification. Legal attributions (OpenStreetMap,
  Open-Meteo) stay.
- **No real places** in code, tests, docs or commit messages: no city, region,
  route or street the user rides or lives near. Write "a city", "the home
  town", "a village"; use made-up route names in fixtures.
- Record decisions in `.agents/brain/decisions/` as they are made.
