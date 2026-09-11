package de.hecate.phone.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
    exportSchema = true,
)
abstract class HecateDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun routes(): RouteDao
    abstract fun meta(): MetaDao

    companion object {
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
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                .also { instance = it }
        }
    }
}
