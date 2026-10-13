package de.hecate.phone.weather

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.hecate.core.weather.WeatherClient
import de.hecate.core.weather.WeatherException
import de.hecate.core.weather.WeatherRateLimited
import de.hecate.core.weather.WeatherTrack
import de.hecate.phone.Fixtures
import de.hecate.phone.db.HecateDatabase
import de.hecate.phone.db.MetaEntity
import de.hecate.phone.db.Settings
import de.hecate.phone.ingest.SessionRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class WeatherRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: HecateDatabase
    private lateinit var sessions: SessionRepository

    @Before
    fun setUp() {
        Fixtures.workManager(context)
        db = Fixtures.database(context)
        sessions = SessionRepository(context, db)
    }

    @After
    fun tearDown() = db.close()

    /** One location's answer around the fixture's start, 2026-07-15 10:00 UTC:
     *  steady drizzle at 18 °C. */
    private val answer = """
        {"latitude":50.1,"longitude":8.6,"minutely_15":{
          "time":[1784109600,1784110500,1784111400],
          "temperature_2m":[18.0,18.5,19.0],
          "precipitation":[0.1,0.1,0.1],
          "weather_code":[53,53,53],
          "is_day":[1,1,1]}}
    """.trimIndent()

    private fun repo(client: WeatherClient) = WeatherRepository(db, client, pauseMs = 0, now = { 7L })

    @Test
    fun `every recording without weather gets it, and the list row carries the summary`(): Unit = runBlocking {
        sessions.ingest(Fixtures.recorded("a"))
        sessions.ingest(Fixtures.recorded("b", startedAtUtcMs = 1_784_109_600_000L + 86_400_000L))
        val urls = mutableListOf<String>()
        val outcome = repo { urls += it; answer }.backfill()

        assertEquals(2, outcome.fetched)
        assertEquals(2, urls.size)
        assertTrue(db.weather().missing().isEmpty())

        val row = assertNotNull(db.weather().get("a"))
        val track = assertNotNull(WeatherTrack.decode(row.payload))
        assertEquals(18.0, track.readings.single().tempC)
        assertEquals(7L, row.fetchedAtUtc)

        val summary = db.sessions().summaries().first().single { it.id == "a" }
        assertEquals(53, summary.weatherCode)
        assertEquals(18.0, summary.tempMinC)
        assertEquals(true, summary.weatherIsDay)
    }

    @Test
    fun `a second pass asks for nothing`(): Unit = runBlocking {
        sessions.ingest(Fixtures.recorded("a"))
        repo { answer }.backfill()
        var calls = 0
        repo { calls++; answer }.backfill()
        assertEquals(0, calls)
    }

    @Test
    fun `a failure leaves the recording to be asked about again`(): Unit = runBlocking {
        sessions.ingest(Fixtures.recorded("a"))
        val outcome = repo { throw WeatherException("down") }.backfill()
        assertEquals(1, outcome.failed)
        assertNull(db.weather().get("a"))
        assertEquals(1, db.weather().missing().size)
    }

    @Test
    fun `a spent quota stops the pass`(): Unit = runBlocking {
        sessions.ingest(Fixtures.recorded("a"))
        sessions.ingest(Fixtures.recorded("b", startedAtUtcMs = 1_784_109_600_000L + 86_400_000L))
        var calls = 0
        val outcome = repo { calls++; throw WeatherRateLimited(0) }.backfill()
        assertTrue(outcome.rateLimited)
        assertEquals(1, calls)
    }

    @Test
    fun `switched off, nothing is asked`(): Unit = runBlocking {
        sessions.ingest(Fixtures.recorded("a"))
        db.meta().put(MetaEntity(Settings.WEATHER_ENABLED, "0"))
        var calls = 0
        val outcome = repo { calls++; answer }.backfill()
        assertEquals(0, calls)
        assertEquals(0, outcome.fetched)
        assertFalse(outcome.rateLimited)
    }

    @Test
    fun `deleting a recording takes its weather with it`(): Unit = runBlocking {
        sessions.ingest(Fixtures.recorded("a"))
        repo { answer }.backfill()
        db.sessions().delete("a")
        assertNull(db.weather().get("a"))
    }
}
