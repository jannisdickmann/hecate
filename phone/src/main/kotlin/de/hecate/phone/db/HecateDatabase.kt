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
        MetaEntity::class,
    ],
    version = 2,
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
                .addMigrations(MIGRATION_1_2)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                .also { instance = it }
        }
    }
}
