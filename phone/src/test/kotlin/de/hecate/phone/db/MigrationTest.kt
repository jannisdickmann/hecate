package de.hecate.phone.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.SessionSource
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Every migration, replayed from the schema Room exported at the time.
 *
 * These exist because of a bug that reached a phone. `MIGRATION_4_5` was written
 * against a column called `tz_offset_minutes`, which is what the Kotlin property
 * is called; the column is `tz_offset`. Room runs pending migrations the first
 * time the database is opened, so the app crashed on every start — and nothing
 * in the build had an opinion about it, because raw SQL in a migration is a
 * string right up until a device runs it.
 *
 * The shape of the test is the point: build the database as the *old* version
 * really was, put rows in it, open it through Room with the real migration list,
 * and read the rows back. Room validates the whole schema on the way through, so
 * a table that a migration forgot fails here too.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = File(context.cacheDir, "migration-test.db")
    private var db: HecateDatabase? = null
    private var originalZone: TimeZone? = null

    /**
     * `MIGRATION_4_5` resolves the repaired offset from the platform default zone,
     * because that is the zone the phone doing the import was in. Pinning it here
     * is what lets the expected values be written down; without it the test reads
     * whatever the machine running it is set to and passes or fails by geography.
     */
    @Before
    fun pinTimeZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
    }

    @After
    fun tearDown() {
        db?.close()
        db = null
        file.delete()
        File("${file.path}-wal").delete()
        File("${file.path}-shm").delete()
        originalZone?.let { TimeZone.setDefault(it) }
    }

    @Test
    fun `a database from the first release opens at the current version`() {
        SchemaFixture.create(file, 1)
        // Opening is the assertion: Room compares every table, column, index and
        // foreign key against the compiled schema after the migrations run, and
        // throws if one differs.
        val sessions = runBlocking { open().sessions().all() }
        assertTrue(sessions.isEmpty())
    }

    @Test
    fun `every exported version migrates to the current one`() {
        val current = SchemaFixture.versions().max()
        for (from in SchemaFixture.versions()) {
            SchemaFixture.create(file, from)
            runBlocking { open().sessions().all() }
            db!!.close()
            db = null
            assertEquals(current, versionOf(file), "a database at v$from did not end up at v$current")
        }
    }

    /** The schema version SQLite itself is holding, read outside Room. */
    private fun versionOf(file: File): Int =
        android.database.sqlite.SQLiteDatabase.openDatabase(
            file.path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { it.version }

    @Test
    fun `v2 keeps the rows it found and leaves the new column null`() {
        SchemaFixture.create(file, 1)
        insertSessionV1(id = "s-1", startedAtUtc = 1_700_000_000_000, tzOffset = 60, source = "WATCH")

        val row = runBlocking { open().sessions().byId("s-1") }
        assertNotNull(row)
        assertEquals(60, row.tzOffsetMinutes)
        // ended_at_utc cannot be derived for a row written before v2: a paused
        // session ends later than start plus active duration.
        assertEquals(null, row.endedAtUtc)
    }

    // ------------------------------------------------------------ stored enums

    /**
     * `exercise_type` and `source` became enums without becoming a migration.
     *
     * The columns have always held the constant's name and still do; only the
     * Kotlin type either side of them changed. So every row an older build wrote
     * reads back unaltered — including the walks that were really hikes, which
     * stay walks, because nothing in the row says otherwise.
     */
    @Test
    fun `a row written before the enum existed reads back as the type it names`() {
        SchemaFixture.create(file, 1)
        insertSessionV1("old-walk", JULY, tzOffset = 60, source = "WATCH", type = "WALKING")

        val row = runBlocking { open().sessions().byId("old-walk") }
        assertNotNull(row)
        assertEquals(ExerciseType.WALKING, row.exerciseType)
        assertEquals(SessionSource.WATCH, row.source)
    }

    /** The other half of Appendix A.18: a name from the future is an error. */
    @Test
    fun `a type this build does not know is refused rather than read as a run`() {
        SchemaFixture.create(file, 1)
        insertSessionV1("from-the-future", JULY, tzOffset = 60, source = "WATCH", type = "PARAGLIDING")

        val dao = open().sessions()
        val e = assertFailsWith<IllegalArgumentException> { runBlocking { dao.byId("from-the-future") } }
        assertTrue(e.message!!.contains("PARAGLIDING"), e.message!!)
    }

    // ------------------------------------------------------------------ v4→v5

    @Test
    fun `v5 gives an imported session the offset its start deserves`() {
        SchemaFixture.create(file, 4)
        // Two imports either side of a daylight-saving change, both stamped 0 by
        // the TCX reader as it used to be.
        insertSessionV4(id = "winter", startedAtUtc = JANUARY, tzOffset = 0, source = "IMPORT_TCX")
        insertSessionV4(id = "summer", startedAtUtc = JULY, tzOffset = 0, source = "IMPORT_TCX")

        val dao = open().sessions()
        runBlocking {
            assertEquals(60, dao.byId("winter")!!.tzOffsetMinutes)
            assertEquals(120, dao.byId("summer")!!.tzOffsetMinutes)
        }
    }

    @Test
    fun `v5 does not touch what the watch recorded`() {
        SchemaFixture.create(file, 4)
        // A watch session at offset 0 is a workout recorded in a zone that really
        // is UTC. Nothing about it was ever a placeholder, so nothing is fixed.
        insertSessionV4(id = "watch", startedAtUtc = JULY, tzOffset = 0, source = "WATCH")

        val row = runBlocking { open().sessions().byId("watch") }
        assertEquals(0, row!!.tzOffsetMinutes)
    }

    @Test
    fun `v5 leaves an import that already had an offset alone`() {
        SchemaFixture.create(file, 4)
        insertSessionV4(id = "takeout", startedAtUtc = JULY, tzOffset = 345, source = "IMPORT_TAKEOUT")

        val row = runBlocking { open().sessions().byId("takeout") }
        assertEquals(345, row!!.tzOffsetMinutes)
    }

    @Test
    fun `v5 survives a database with nothing to repair`() {
        SchemaFixture.create(file, 4)
        val sessions = runBlocking { open().sessions().all() }
        assertTrue(sessions.isEmpty())
    }

    // --------------------------------------------------------------- plumbing

    /** The real thing: the same migration list the app registers. */
    private fun open(): HecateDatabase = Room
        .databaseBuilder(context, HecateDatabase::class.java, file.path)
        // The app's own list, not a copy of it: a migration added to the app and
        // not to the test is a migration this test cannot see.
        .addMigrations(*HecateDatabase.MIGRATIONS)
        .build()
        .also { db = it }

    private fun insertSessionV1(
        id: String,
        startedAtUtc: Long,
        tzOffset: Int,
        source: String,
        type: String = "RUNNING",
    ) = rawInsert(id, startedAtUtc, tzOffset, source, endedAtUtc = null, type = type)

    private fun insertSessionV4(id: String, startedAtUtc: Long, tzOffset: Int, source: String) =
        rawInsert(id, startedAtUtc, tzOffset, source, endedAtUtc = startedAtUtc + 1_800_000L)

    /**
     * Written with SQL rather than through the DAO on purpose: the row has to
     * look like one an *older* build wrote, and the DAO only knows how to write
     * the current one.
     */
    private fun rawInsert(
        id: String,
        startedAtUtc: Long,
        tzOffset: Int,
        source: String,
        endedAtUtc: Long?,
        type: String = "RUNNING",
    ) {
        android.database.sqlite.SQLiteDatabase.openDatabase(
            file.path,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        ).use { raw ->
            val columns = buildString {
                append("id, started_at_utc, tz_offset, exercise_type, active_duration_ms, ")
                append("total_distance_m, source, hc_write_state, ingested_at")
                if (endedAtUtc != null) append(", ended_at_utc")
            }
            val values = arrayListOf<Any>(
                id, startedAtUtc, tzOffset, type, 1_500_000L, 5000.0, source, 3, 0L,
            )
            endedAtUtc?.let { values.add(it) }
            raw.execSQL(
                "INSERT INTO session ($columns) VALUES (${values.joinToString(",") { "?" }})",
                values.toTypedArray(),
            )
        }
    }

    private companion object {
        /** 2026-01-15T10:00:00Z — CET, +01:00 in Europe/Berlin. */
        const val JANUARY = 1_768_471_200_000L

        /** 2026-07-15T10:00:00Z — CEST, +02:00. */
        const val JULY = 1_784_109_600_000L
    }
}
