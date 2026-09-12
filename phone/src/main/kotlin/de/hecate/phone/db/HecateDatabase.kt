package de.hecate.phone.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 5,
    exportSchema = true,
)
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                .also { instance = it }
        }
    }
}
