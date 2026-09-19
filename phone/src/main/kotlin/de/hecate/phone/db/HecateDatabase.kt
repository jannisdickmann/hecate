package de.hecate.phone.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.time.Instant
import java.time.ZoneId

/**
 * §4: Room + SQLite on the phone, because history and per-route queries need a
 * real database. The watch keeps its append-only files; nothing is shared.
 */
@Database(
    entities = [
        SessionEntity::class,
        SampleEntity::class,
        RouteEntity::class,
        RoutePointEntity::class,
        RouteProfileEntity::class,
        RouteMemberEntity::class,
        MatchLogEntity::class,
        SessionEventsEntity::class,
        HeatmapEntity::class,
        MetaEntity::class,
    ],
    version = 7,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class HecateDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun routes(): RouteDao
    abstract fun heatmap(): HeatmapDao
    abstract fun meta(): MetaDao

    companion object {
        /** v2 records the session's stop time. Ingest previously kept only the
         *  start and the active duration, which cannot be added back: a paused
         *  session ends later than start + active. Existing rows keep NULL. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE session ADD COLUMN ended_at_utc INTEGER")
            }
        }

        /** v3 keeps the session's lifecycle events. The watch has always written
         *  them into the session file, and ingest has always parsed them; they
         *  simply had nowhere to go and were dropped, which meant they died with
         *  the watch's copy of the file. Sessions ingested before v3 have no row
         *  and never will — the events are gone with the file. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS session_events (
                        session_id TEXT NOT NULL,
                        payload BLOB NOT NULL,
                        PRIMARY KEY(session_id),
                        FOREIGN KEY(session_id) REFERENCES session(id)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent(),
                )
            }
        }

        /** v4 stores the precomputed heatmap raster. Nothing is lost by starting
         *  empty: it is derived data, and the next clustering rebuilds it. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS heatmap (
                        id INTEGER NOT NULL,
                        payload BLOB NOT NULL,
                        computed_at_utc INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }

        /**
         * v5 repairs the time zone of everything imported before the TCX reader
         * knew about time zones.
         *
         * The reader used to stamp every imported session with offset 0, because
         * a TCX from Google Health is written in UTC and names no zone. The
         * instants were always right; what was wrong was the offset used to turn
         * them back into a local time, so an import made in summer displayed two
         * hours early — and, less visibly, landed in the wrong time-of-day bucket
         * for the matcher's context prior.
         *
         * Only rows that are both imported and sitting at exactly 0 are touched,
         * and the offset is resolved at the instant of each session so that a
         * January walk and a July ride come out an hour apart. A genuine offset
         * of 0 — an import of a workout recorded in Britain in winter — is
         * rewritten too, which is the price of not being able to tell the two
         * apart after the fact. On a phone in Berlin that case does not arise.
         *
         * Data only; the schema is untouched.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val zone = ZoneId.systemDefault()
                val rows = ArrayList<Pair<String, Long>>()
                db.query(
                    """
                    SELECT id, started_at_utc FROM session
                    WHERE tz_offset = 0 AND source LIKE 'IMPORT%'
                    """.trimIndent(),
                ).use { cursor ->
                    while (cursor.moveToNext()) rows.add(cursor.getString(0) to cursor.getLong(1))
                }
                for ((id, startedAt) in rows) {
                    val offset = zone.rules.getOffset(Instant.ofEpochMilli(startedAt)).totalSeconds / 60
                    if (offset == 0) continue
                    db.execSQL(
                        "UPDATE session SET tz_offset = ? WHERE id = ?",
                        arrayOf<Any>(offset, id),
                    )
                }
            }
        }

        /**
         * v6 stores the route's elevation profile: one median altitude per bin,
         * beside the median time that was already there.
         *
         * Existing rows get NULL and stay that way until the next clustering
         * pass, which is derived data and rebuilds from the sessions — the
         * altitude was in the samples all along, it simply had nowhere to be
         * aggregated to. A route detail opened before that pass shows no
         * elevation chart rather than a wrong one.
         */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE route_profile ADD COLUMN median_alt_m REAL")
            }
        }

        /**
         * v7 rescales the manual route priority from 1..10 to −2..2.
         *
         * Every route is reset to 0 rather than mapped onto the new scale, and
         * that is deliberate: the old default was 5, so all but a handful of
         * routes are sitting on a number the user never chose, and there is no
         * way to tell those apart from a deliberate 5. Mapping would turn a
         * value nobody picked into a value nobody picked on a different scale.
         * Resetting asks the handful of users of this feature to say it again on
         * a scale where saying nothing is the honest zero.
         *
         * The table is rebuilt rather than updated in place, because the column
         * default itself changes and SQLite has no `ALTER COLUMN`. This is the
         * "12-step" rebuild from the SQLite manual, minus the steps that only
         * apply to tables with indices or triggers on them, of which `route` has
         * neither. Foreign keys are off while Room runs a migration, so dropping
         * the parent table does not cascade into `route_point`, `route_profile`
         * and `route_member`, and the rename does not rewrite their `REFERENCES`
         * clauses — which is what makes them still point at the right table
         * afterwards. `MigrationTest` replays exactly this and then reads the
         * children back, because every clause of that sentence is a thing that
         * would fail silently.
         */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS route_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        name_is_custom INTEGER NOT NULL,
                        exercise_type TEXT NOT NULL,
                        length_m REAL NOT NULL,
                        member_count INTEGER NOT NULL,
                        medoid_session_id TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        active INTEGER NOT NULL,
                        priority INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO route_new (
                        id, name, name_is_custom, exercise_type, length_m,
                        member_count, medoid_session_id, created_at, active, priority
                    )
                    SELECT id, name, name_is_custom, exercise_type, length_m,
                           member_count, medoid_session_id, created_at, active, 0
                      FROM route
                    """.trimIndent(),
                )
                db.execSQL("DROP TABLE route")
                db.execSQL("ALTER TABLE route_new RENAME TO route")
            }
        }

        /**
         * Every migration, in one place.
         *
         * The list used to be spelled out twice — here and again in
         * `MigrationTest` — and the copies drifted the moment a migration was
         * added: the test opened the database without the new one and failed
         * with "a migration from 4 to 6 was required but not found", which is a
         * true statement about the test and says nothing about the app. One list
         * makes that impossible, and A.16 is the reminder of what a migration
         * nobody replays costs.
         */
        val MIGRATIONS: Array<Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6,
            MIGRATION_6_7,
        )

        @Volatile
        private var instance: HecateDatabase? = null

        fun get(context: Context): HecateDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                HecateDatabase::class.java,
                "hecate.db",
            )
                // Foreign keys do the cascading deletes; without this SQLite
                // ignores them and orphan samples accumulate.
                .addMigrations(*MIGRATIONS)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                .also { instance = it }
        }
    }
}
