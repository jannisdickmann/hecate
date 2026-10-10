# ADR: Robolectric, bought by a migration that named the wrong column

**Date:** 2026-09-12
**Status:** Accepted
**ID:** A.16 (formerly SPECIFICATION.md Appendix A)

## Context

§6.5 asks for JVM tests of the algorithms, an emulator for plumbing and a wrist
for the rest, and says nothing about the middle. The middle is where `:phone`
lives: Room, WorkManager, a `ContentResolver`. None of it is algorithmic, all of
it is ordinary, and until then none of it had a test — the position being that
Android-side code needs a device, so it gets lint and the compiler and a careful
reading.

That held until `MIGRATION_4_5` was written against `tz_offset_minutes`. The
column is `tz_offset`; `tzOffsetMinutes` is the Kotlin property. Room runs
pending migrations the first time the database is opened, so the consequence was
not a wrong number somewhere — it was an app that would not start, on a database
that already held the user's history. Nothing in the build had any opinion,
because raw SQL in a `Migration` is a string until something runs it, and the
only thing that runs it is a database at the old version.

A migration is the worst possible place for "it gets a careful reading" to be
the whole safety net, and it is also entirely reachable from a laptop: the old
version is fully described by the JSON Room already exports into
`phone/schemas/`.

## Decision

`:phone` has Robolectric, and test classes for the silent promises:

| | |
|---|---|
| `MigrationTest` | each exported version built from its schema, migrated through the real list, rows read back |
| `SessionRepositoryTest` | ingest's two silent promises — idempotent on the id (§5.4), start-time duplicates refused (§7) |
| `ArchiveRepositoryTest` | A.10's round trip: export, restore into an empty database, names and priorities intact |
| `TcxImporterTest` | US-9 end to end, including the time zones A.15 fixed |

(`StandoutsTest`, A.13's arithmetic, runs in the same task; the README lists
the current set.)

**The migration tests do not use `MigrationTestHelper`.** Room's own helper reads
the exported schemas through the `AssetManager`, and Android unit tests do not
merge the test source set's assets — so the only way to satisfy it is to put
`phone/schemas/` on the *main* source set, which ships 60 KB of schema JSON
inside the APK to serve a test. `SchemaFixture` reads the same files off disk
instead. What is given up is per-migration schema validation; what is kept is
better, because the tests go through `Room.databaseBuilder` with the app's own
migration list, so Room validates the final schema itself *and* a migration that
was written but never registered fails.

**The archive tests use a `file://` Uri.** Everything on the app's side of that
Uri is exercised — the codec, the Room mapping both ways, the restore's
transactions and foreign-key ordering. Nothing about the Storage Access Framework
is, and §1.3 still says so.

**The workers are stubbed, not run.** Both repositories enqueue clustering as
their last act. `ClusterWorker` is a thin wrapper over `:core`'s clusterer, which
has its own tests, and `HealthConnectWorker` needs a Health Connect the JVM does
not have; a WorkManager that accepts an enqueue and runs nothing keeps the tests
about the thing they are about.

## Consequences

What this does not change: `:wear` has no tests, nothing is instrumented, and the
three defects open point §3.2 lists — cross-device delivery, a vendor library's
validation, a JDK-versus-Android API difference — would all still have reached
the device. Robolectric moved the line between "found on a laptop" and "found on
a wrist". It did not remove it.

A.20 found the next hole: `MigrationTest` kept its own copy of the migration
list. Both now use `HecateDatabase.MIGRATIONS`. A.21's v7 rebuild added rows in
every child table and a cascade check, because each step of a table rebuild
fails silently.
