package de.hecate.phone.ingest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.Reading
import de.hecate.core.model.SessionEndReason
import de.hecate.core.model.SessionEvent
import de.hecate.core.model.SessionEventType
import de.hecate.core.model.SessionSource
import de.hecate.phone.Fixtures
import de.hecate.phone.db.HcWriteState
import de.hecate.phone.db.HecateDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ingest, which is the only way anything ever enters the database.
 *
 * Two of its promises are load-bearing and neither is visible from the UI. It is
 * idempotent on the session id, which is what lets the watch retry blindly (§5.4)
 * — a redelivered session has to be recognised and acknowledged, not stored
 * twice. And it rejects a second recording of the same workout by start time
 * (§7), because the watch's own activity detection produces one and the user
 * cannot be asked to turn that off reliably.
 *
 * Both failures are silent: the app looks fine and the route member counts are
 * wrong.
 */
@RunWith(RobolectricTestRunner::class)
class SessionRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: HecateDatabase
    private lateinit var repository: SessionRepository

    @Before
    fun setUp() {
        Fixtures.workManager(context)
        db = Fixtures.database(context)
        repository = SessionRepository(context, db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `a session arrives with its samples`(): Unit = runBlocking {
        val session = Fixtures.recorded("s-1", sampleCount = 30)
        assertIs<SessionRepository.IngestResult.Stored>(repository.ingest(session))

        val row = db.sessions().byId("s-1")
        assertNotNull(row)
        assertEquals(session.header.startedAtUtcMs, row.startedAtUtc)
        assertEquals(120, row.tzOffsetMinutes)
        assertEquals(30, db.sessions().samples("s-1").size)
        // Averaged over the samples at ingest, because recomputing it means
        // reading every sample back.
        assertEquals(132.0, row.avgHr!!, 1.0)
    }

    @Test
    fun `the same session twice is stored once`(): Unit = runBlocking {
        val session = Fixtures.recorded("s-1")
        repository.ingest(session)
        val second = repository.ingest(session)

        assertIs<SessionRepository.IngestResult.Duplicate>(second)
        assertEquals(1, db.sessions().count())
        assertEquals(30, db.sessions().samples("s-1").size)
    }

    @Test
    fun `a second recording of the same workout is refused`(): Unit = runBlocking {
        // The watch's activity detection noticing the run three minutes in, with
        // roughly the run's duration. Different id, same workout.
        val start = 1_784_109_600_000L
        repository.ingest(Fixtures.recorded("watch", startedAtUtcMs = start))
        val auto = repository.ingest(Fixtures.recorded("auto", startedAtUtcMs = start + 180_000L))

        assertIs<SessionRepository.IngestResult.Duplicate>(auto)
        assertTrue(auto.reason.contains("watch"), "the report should name what it clashed with")
        assertEquals(1, db.sessions().count())
    }

    @Test
    fun `a workout later the same day is its own session`(): Unit = runBlocking {
        val start = 1_784_109_600_000L
        repository.ingest(Fixtures.recorded("morning", startedAtUtcMs = start))
        val evening = repository.ingest(Fixtures.recorded("evening", startedAtUtcMs = start + 8 * 3_600_000L))

        assertIs<SessionRepository.IngestResult.Stored>(evening)
        assertEquals(2, db.sessions().count())
    }

    @Test
    fun `a workout of a very different length is not the same workout`(): Unit = runBlocking {
        // Two minutes apart, but one is half an hour and the other is ten
        // seconds: a false positive here loses a real recording.
        val start = 1_784_109_600_000L
        repository.ingest(Fixtures.recorded("long", startedAtUtcMs = start, sampleCount = 1800))
        val short = repository.ingest(
            Fixtures.recorded("short", startedAtUtcMs = start + 120_000L, sampleCount = 10),
        )

        assertIs<SessionRepository.IngestResult.Stored>(short)
        assertEquals(2, db.sessions().count())
    }

    @Test
    fun `an imported session is never queued for Health Connect`(): Unit = runBlocking {
        // US-9: these came out of Health Connect. Writing them back gives the user
        // two copies of every workout in the one place this app cannot clean up.
        for (source in listOf(SessionSource.IMPORT_TCX, SessionSource.IMPORT_TAKEOUT)) {
            repository.ingest(
                Fixtures.recorded(
                    id = source.name,
                    startedAtUtcMs = 1_784_109_600_000L + source.ordinal * 86_400_000L,
                    source = source,
                ),
            )
            assertEquals(
                HcWriteState.SKIPPED,
                db.sessions().byId(source.name)!!.hcWriteState,
                "$source should not be written back",
            )
        }

        repository.ingest(Fixtures.recorded("watch", startedAtUtcMs = 1_790_000_000_000L))
        assertEquals(HcWriteState.PENDING, db.sessions().byId("watch")!!.hcWriteState)
    }

    @Test
    fun `a recording with nothing in it is rejected rather than stored empty`(): Unit = runBlocking {
        val result = repository.ingest(Fixtures.recorded("s-1", sampleCount = 1))
        assertIs<SessionRepository.IngestResult.Rejected>(result)
        assertEquals(0, db.sessions().count())
        // Permanent: no amount of retrying makes a recording with one position
        // storable. The wire layer acknowledges these so the watch stops keeping
        // them — three had accumulated on a watch before that was true, because a
        // refusal is otherwise never acknowledged and the file is never dropped.
        assertTrue(result.permanent)
    }

    @Test
    fun `lifecycle events are stored with the samples, not dropped`(): Unit = runBlocking {
        // Schema v3 exists for these. Before it they were parsed and thrown away,
        // and they died with the watch's copy of the file.
        val events = listOf(
            SessionEvent(tOffsetMs = 5_000, type = SessionEventType.PAUSE_MANUAL),
            SessionEvent(tOffsetMs = 9_000, type = SessionEventType.RESUME_MANUAL, note = "weiter"),
        )
        repository.ingest(Fixtures.recorded("s-1", events = events))

        assertNotNull(db.sessions().events("s-1"))
    }

    @Test
    fun `the barometer's own series is kept, and put onto every sample`(): Unit = runBlocking {
        // Schema v8 exists for this. The sensor reports about once every half
        // minute against a position a second, so merging it into the sample rows
        // and dropping the series — which is what happened to the heart rate —
        // would keep roughly one reading in seven and lose the rest for good.
        val altitudes = (0..10).map { Reading(it * 3_000L, 260.0 + it * 2.0) }
        repository.ingest(Fixtures.recorded("s-alt", sampleCount = 30, altitudes = altitudes))

        assertNotNull(db.sessions().series("s-alt")?.altitudes, "the readings themselves")
        // And every sample has an altitude, not just the ones a reading landed on.
        val samples = db.sessions().samples("s-alt")
        assertEquals(30, samples.size)
        assertTrue(samples.all { it.altM != null })
        // Interpolated rather than nearest, read in the interior where a
        // monotonic run passes through the de-spiking median unchanged: the
        // sample a second into a three-second, two-metre step is a third of the
        // way up it. A nearest join would have said 270 here — and nothing at all
        // at two samples in three.
        assertEquals(270.0, samples.first { it.tOffsetMs == 15_000L }.altM!!, 1e-6)
        assertEquals(270.0 + 2.0 / 3.0, samples.first { it.tOffsetMs == 16_000L }.altM!!, 1e-6)
    }

    @Test
    fun `deleting a session takes its series with it`(): Unit = runBlocking {
        repository.ingest(
            Fixtures.recorded("s-alt", altitudes = listOf(Reading(0L, 260.0), Reading(3_000L, 262.0))),
        )
        db.sessions().delete("s-alt")

        assertNull(db.sessions().series("s-alt"))
    }

    @Test
    fun `an auto-stopped session is stored without the tail it discarded`(): Unit = runBlocking {
        // A.30. The watch writes the whole file — it is append-only, and that is
        // what makes a recording survive a process death — and says in the footer
        // where the activity actually ended. Ingest is the one place the cut is
        // applied, so the drive home reaches neither the database nor the
        // clusterer nor Health Connect.
        val recorded = Fixtures.recorded(
            "s-1",
            sampleCount = 30,
            events = listOf(
                SessionEvent(
                    tOffsetMs = 29_000,
                    type = SessionEventType.AUTO_STOP,
                    note = "Stillstand seit 15 min — beendet",
                ),
            ),
        )
        val stopped = recorded.copy(
            footer = recorded.footer!!.copy(
                reason = SessionEndReason.AUTO_STOPPED,
                activeDurationMs = 19_000,
                totalDistanceM = 40.0,
                trimmedAfterTOffsetMs = 19_000,
            ),
        )
        assertIs<SessionRepository.IngestResult.Stored>(repository.ingest(stopped))

        val samples = db.sessions().samples("s-1")
        assertEquals(20, samples.size, "twenty seconds of activity, inclusive")
        assertTrue(samples.all { it.tOffsetMs <= 19_000 })
        val row = db.sessions().byId("s-1")!!
        assertEquals(19_000, row.activeDurationMs)
        assertEquals(40.0, row.totalDistanceM)
        // The event that says why the recording ends there is later than the cut
        // by definition, and survives it.
        assertNotNull(db.sessions().events("s-1"))
    }

    @Test
    fun `an auto-stop that leaves nothing behind is refused rather than stored`(): Unit = runBlocking {
        // Cannot happen while the rules refuse to arm before the activity has
        // travelled fifty metres, which is more than one sample's worth — but if
        // it ever does, an empty session must be refused exactly as any other
        // empty recording is, and permanently, so the watch stops offering it.
        val recorded = Fixtures.recorded("s-1", sampleCount = 30)
        val stopped = recorded.copy(
            footer = recorded.footer!!.copy(
                reason = SessionEndReason.AUTO_STOPPED,
                trimmedAfterTOffsetMs = 0,
            ),
        )
        val result = repository.ingest(stopped)
        assertIs<SessionRepository.IngestResult.Rejected>(result)
        assertTrue(result.permanent)
        assertEquals(0, db.sessions().count())
    }

    @Test
    fun `rewriting a session row takes its samples with it`(): Unit = runBlocking {
        // Not a feature — a trap, written down so the next person finds it here
        // rather than in a user's history. `SessionDao.insert` is REPLACE, and
        // SQLite implements that as DELETE then INSERT; `sample` has ON DELETE
        // CASCADE, so the delete takes every sample with it. There is no
        // update-in-place for a session row, so a caller that wants to change one
        // column must use one of the targeted @Query updates. Both callers of
        // insert() guard with countById first, which is why this is only a trap.
        repository.ingest(Fixtures.recorded("s-1"))
        val row = db.sessions().byId("s-1")!!
        db.sessions().insert(row.copy(avgHr = 99.0))

        assertEquals(99.0, db.sessions().byId("s-1")!!.avgHr)
        assertEquals(0, db.sessions().samples("s-1").size)
    }

    @Test
    fun `deleting a session takes its samples with it`(): Unit = runBlocking {
        repository.ingest(Fixtures.recorded("s-1"))
        db.sessions().delete("s-1")

        // The foreign key is only enforced because the database asks for it; a
        // build that stopped asking would leak a row per sample per deletion.
        assertEquals(0, db.sessions().samples("s-1").size)
    }

    /**
     * Refiling a recording under another sport, which is the one field a user may
     * correct after the fact. Worth a test although it is a one-line UPDATE: the
     * column is an enum stored by name through a non-null converter, so a value
     * that fails to round trip throws on read rather than quietly coming back as
     * something else.
     */
    @Test
    fun `a recording can be refiled under another sport`(): Unit = runBlocking {
        repository.ingest(Fixtures.recorded("s-type", type = ExerciseType.WALKING))
        assertEquals(ExerciseType.WALKING, db.sessions().byId("s-type")?.exerciseType)

        db.sessions().setExerciseType("s-type", ExerciseType.HIKING)

        assertEquals(ExerciseType.HIKING, db.sessions().byId("s-type")?.exerciseType)
    }

    /**
     * And it changes nothing else. The type is a correction to one fact about the
     * workout; the samples, the source and the times are facts about the
     * recording and are not the user's to revise.
     */
    @Test
    fun `refiling leaves the rest of the recording alone`(): Unit = runBlocking {
        repository.ingest(Fixtures.recorded("s-keep", type = ExerciseType.WALKING, sampleCount = 30))
        val before = db.sessions().byId("s-keep")
        assertNotNull(before)

        db.sessions().setExerciseType("s-keep", ExerciseType.HIKING)

        val after = db.sessions().byId("s-keep")
        assertNotNull(after)
        assertEquals(before.startedAtUtc, after.startedAtUtc)
        assertEquals(before.activeDurationMs, after.activeDurationMs)
        assertEquals(before.totalDistanceM, after.totalDistanceM)
        assertEquals(before.source, after.source)
        assertEquals(before.tzOffsetMinutes, after.tzOffsetMinutes)
        assertEquals(before.hcWriteState, after.hcWriteState)
        assertEquals(30, db.sessions().samples("s-keep").size)
    }
}
