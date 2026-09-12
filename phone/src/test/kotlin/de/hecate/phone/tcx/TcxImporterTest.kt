package de.hecate.phone.tcx

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.hecate.core.model.SessionSource
import de.hecate.phone.Fixtures
import de.hecate.phone.db.HcWriteState
import de.hecate.phone.db.HecateDatabase
import de.hecate.phone.ingest.SessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.TimeZone
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * US-9 through the phone's own path: a file picker hands over a TCX, and a row
 * appears in the database.
 *
 * The time-zone assertions are the reason this file exists. `TcxParser` used to
 * stamp every imported session with offset 0 — a TCX from Google Health is
 * written in UTC and names no zone — and the symptom was a summer workout
 * displaying two hours early. The instants were right the whole time, so nothing
 * looked corrupt; only the time of day was wrong, and only for imports.
 */
@RunWith(RobolectricTestRunner::class)
class TcxImporterTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: HecateDatabase
    private lateinit var importer: TcxImporter
    private var originalZone: TimeZone? = null
    private val files = ArrayList<File>()

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
        Fixtures.workManager(context)
        db = Fixtures.database(context)
        importer = TcxImporter(context, SessionRepository(context, db))
    }

    @After
    fun tearDown() {
        db.close()
        files.forEach { it.delete() }
        originalZone?.let { TimeZone.setDefault(it) }
    }

    @Test
    fun `a file becomes a session`(): Unit = runBlocking {
        val report = importer.import(listOf(tcx("2026-07-15T08:00:00Z")))

        assertEquals(1, report.imported)
        assertEquals(0, report.failed, report.messages.toString())
        val row = db.sessions().all().single()
        assertEquals(SessionSource.IMPORT_TCX.name, row.source)
        // US-9: it came out of Health Connect, so it never goes back in.
        assertEquals(HcWriteState.SKIPPED, row.hcWriteState)
        assertTrue(db.sessions().samples(row.id).size >= 3)
    }

    @Test
    fun `a summer workout keeps summer time`(): Unit = runBlocking {
        importer.import(listOf(tcx("2026-07-15T08:00:00Z")))

        val row = db.sessions().all().single()
        assertEquals(1_784_102_400_000L, row.startedAtUtc)
        // 08:00 UTC is 10:00 in Berlin in July. Offset 0 would display 08:00.
        assertEquals(120, row.tzOffsetMinutes)
    }

    @Test
    fun `a winter workout keeps winter time`(): Unit = runBlocking {
        importer.import(listOf(tcx("2026-01-15T08:00:00Z")))

        // The same import, run at the same moment, on the other side of a
        // daylight-saving change. A fix that used "now" rather than the workout's
        // own instant gets one of these two wrong.
        assertEquals(60, db.sessions().all().single().tzOffsetMinutes)
    }

    @Test
    fun `a file that names its own offset is believed`(): Unit = runBlocking {
        importer.import(listOf(tcx("2026-07-15T13:45:00+05:45")))

        val row = db.sessions().all().single()
        assertEquals(345, row.tzOffsetMinutes)
        assertEquals(1_784_102_400_000L, row.startedAtUtc)
    }

    @Test
    fun `the same file twice is one session`(): Unit = runBlocking {
        val file = tcx("2026-07-15T08:00:00Z")
        importer.import(listOf(file))
        val second = importer.import(listOf(file))

        // The id is derived from the start time, so a re-import is recognised
        // before the time-window check even runs.
        assertEquals(1, second.duplicates)
        assertEquals(1, db.sessions().count())
    }

    @Test
    fun `a file that is not a TCX is counted, not thrown`(): Unit = runBlocking {
        val junk = File.createTempFile("junk", ".tcx", context.cacheDir).also {
            it.writeText("<html>nope</html>")
            files.add(it)
        }
        val report = importer.import(listOf(Uri.fromFile(junk), tcx("2026-07-15T08:00:00Z")))

        // One bad file out of a multi-selection must not cost the user the rest.
        assertEquals(1, report.failed)
        assertEquals(1, report.imported)
        assertNotNull(report.messages.firstOrNull())
    }

    /** One activity of four trackpoints, one second apart, starting at [start]. */
    private fun tcx(start: String): Uri {
        val startMs = java.time.OffsetDateTime.parse(start).toInstant().toEpochMilli()
        val points = (0..3).joinToString("") { i ->
            val t = java.time.format.DateTimeFormatter.ISO_INSTANT
                .format(java.time.Instant.ofEpochMilli(startMs + i * 1000L))
            """
            <Trackpoint>
              <Time>$t</Time>
              <Position>
                <LatitudeDegrees>${50.1 + i * 0.0002}</LatitudeDegrees>
                <LongitudeDegrees>8.6</LongitudeDegrees>
              </Position>
              <HeartRateBpm><Value>${140 + i}</Value></HeartRateBpm>
              <DistanceMeters>${i * 3.0}</DistanceMeters>
            </Trackpoint>
            """
        }
        val file = File.createTempFile("workout", ".tcx", context.cacheDir).also { files.add(it) }
        file.writeText(
            """
            <TrainingCenterDatabase>
              <Activities>
                <Activity Sport="Running">
                  <Id>$start</Id>
                  <Lap><Track>$points</Track></Lap>
                </Activity>
              </Activities>
            </TrainingCenterDatabase>
            """.trimIndent(),
        )
        return Uri.fromFile(file)
    }
}
