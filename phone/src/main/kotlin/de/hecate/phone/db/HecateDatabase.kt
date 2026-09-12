package de.hecate.phone.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
        MetaEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class HecateDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun routes(): RouteDao
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                .also { instance = it }
        }
    }
}
