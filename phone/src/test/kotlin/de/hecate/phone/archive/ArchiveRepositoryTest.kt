package de.hecate.phone.archive

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import de.hecate.core.Tuning
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.MatchEvent
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.MatchPhase
import de.hecate.core.model.SessionSource
import de.hecate.phone.Fixtures
import de.hecate.phone.db.HcWriteState
import de.hecate.phone.db.HecateDatabase
import de.hecate.phone.db.RouteMemberEntity
import de.hecate.phone.db.RoutePointEntity
import de.hecate.phone.db.RouteProfileEntity
import de.hecate.phone.db.MetaEntity
import de.hecate.phone.ingest.SessionRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Export, then restore into an empty database, and check what came back.
 *
 * §1.3 of the open points calls this the one thing worth doing first and says it
 * has never been done: *export, wipe, restore, and check the route names and
 * priorities came back*. This is that, minus the device — the Storage Access
 * Framework half is still unverified, and a `file://` Uri here stands in for the
 * `content://` one a real picker hands over.
 *
 * What that substitution does and does not cover is worth being honest about. It
 * exercises every line of [ArchiveRepository] and [de.hecate.core.archive.ArchiveCodec],
 * the Room mapping in both directions, and the restore's transactions and
 * foreign-key ordering. It says nothing about whether a picked `content://` Uri
 * really opens for writing on a phone.
 */
@RunWith(RobolectricTestRunner::class)
class ArchiveRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var source: HecateDatabase
    private lateinit var target: HecateDatabase
    private lateinit var file: File

    @Before
    fun setUp() {
        Fixtures.workManager(context)
        source = Fixtures.database(context)
        target = Fixtures.database(context)
        file = File.createTempFile("hecate-backup", ".zip", context.cacheDir)
    }

    @After
    fun tearDown() {
        source.close()
        target.close()
        file.delete()
    }

    // ------------------------------------------------------------- round trip

    @Test
    fun `a full database survives export and restore`(): Unit = runBlocking {
        seed(source)
        val exported = ArchiveRepository(context, source).export(uri())
        assertEquals(3, exported.sessions)
        assertEquals(2, exported.routes)
        assertTrue(exported.bytes > 0)

        val report = ArchiveRepository(context, target).restore(uri())
        assertEquals(3, report.sessionsRestored)
        assertEquals(2, report.routesRestored)
        assertEquals(0, report.failed, report.messages.toString())

        // The two things nothing can recompute. A re-clustering would give these
        // routes fresh auto-names at the default priority, which is the whole
        // argument for the archive existing.
        val routes = target.routes().all().associateBy { it.id }
        assertEquals("Zur Arbeit", routes.getValue(1).name)
        assertEquals(2, routes.getValue(1).priority)
        assertTrue(routes.getValue(1).nameIsCustom)
        assertEquals("Feierabendrunde", routes.getValue(2).name)
        assertEquals(-2, routes.getValue(2).priority)
    }

    @Test
    fun `a priority from the old scale is clamped rather than left unreachable`(): Unit = runBlocking {
        seed(source)
        // 9 is what an archive written before schema v7 carries. Nothing crashes
        // on it — priority is an integer the matcher only ever compares — but the
        // picker has no button for a 9, so the route would show no priority at
        // all and could not be changed without first choosing a different one.
        source.routes().upsert(Fixtures.route(1, "Von früher", priority = 9, medoidSessionId = "s-1"))
        ArchiveRepository(context, source).export(uri())
        ArchiveRepository(context, target).restore(uri())

        assertEquals(Tuning.MAX_ROUTE_PRIORITY, target.routes().byId(1)!!.priority)
    }

    @Test
    fun `the samples come back, not just the session row`(): Unit = runBlocking {
        seed(source)
        ArchiveRepository(context, source).export(uri())
        ArchiveRepository(context, target).restore(uri())

        val before = source.sessions().samples("s-1")
        val after = target.sessions().samples("s-1")
        assertEquals(before.size, after.size)
        assertEquals(before.first().lat, after.first().lat)
        assertEquals(before.first().hrBpm, after.first().hrBpm)
        assertEquals(before.last().activeMs, after.last().activeMs)
    }

    @Test
    fun `geometry, members and profile come back with their route`(): Unit = runBlocking {
        seed(source)
        ArchiveRepository(context, source).export(uri())
        ArchiveRepository(context, target).restore(uri())

        assertEquals(3, target.routes().points(1).size)
        assertEquals(2, target.routes().profile(1).size)
        // The elevation profile rides in the archive with the rest of the bin,
        // so a restored route draws its altitude without waiting to be
        // re-clustered.
        assertEquals(listOf(300.0, 312.0), target.routes().profile(1).map { it.medianAltM })
        assertEquals(source.routes().allMembers().size, target.routes().allMembers().size)
        assertEquals("7", target.meta().get("bundleVersion"))
    }

    @Test
    fun `what the watch decided on the day is carried, not recomputed`(): Unit = runBlocking {
        // committedAtM is a fact about a decision made on a wrist: where along
        // the route the matcher stopped hedging. Nothing can derive it again
        // later, so a restore that dropped it would lose it silently and for ever.
        val committed = MatchLogEntry(
            tOffsetMs = 12_000,
            event = MatchEvent.SAMPLE,
            phase = MatchPhase.COMMITTED,
            nullWeight = 0.05,
            committedRouteId = 1,
            travelledM = 412.5,
        )
        SessionRepository(context, source).ingest(
            Fixtures.recorded("s-1", startedAtUtcMs = 1_784_109_600_000L).copy(matchLog = listOf(committed)),
        )
        source.sessions().setHcState("s-1", HcWriteState.WRITTEN, null)

        ArchiveRepository(context, source).export(uri())
        ArchiveRepository(context, target).restore(uri())

        val restored = target.sessions().byId("s-1")!!
        assertEquals(412.5, restored.committedAtM)
        assertEquals(1, restored.matchedRouteId)
        // Not reset to PENDING: the workout is already in Health Connect, and a
        // restore that re-queued it would write every one of them a second time.
        assertEquals(HcWriteState.WRITTEN, restored.hcWriteState)
        // And the log itself, which is what the replay harness compares against.
        assertNotNull(target.sessions().matchLog("s-1"))
    }

    // ------------------------------------------------------------------ merge

    /**
     * The archive carries the type as the enum's own name, so a build that gained
     * a type writes a word an older build has never seen. That is the case this
     * covers from the good side: every type this build records survives the round
     * trip as itself, rather than arriving back as a run.
     */
    @Test
    fun `every exercise type survives the round trip`(): Unit = runBlocking {
        for ((i, type) in ExerciseType.entries.withIndex()) {
            val id = "type-$i"
            val row = sessionRow(id, 1_784_109_600_000L + i * 86_400_000L, SessionSource.WATCH)
            source.sessions().insert(row.copy(exerciseType = type))
            source.sessions().insertSamples(sampleRows(id))
        }
        ArchiveRepository(context, source).export(uri())
        ArchiveRepository(context, target).restore(uri())

        for ((i, type) in ExerciseType.entries.withIndex()) {
            assertEquals(type, target.sessions().byId("type-$i")!!.exerciseType, type.name)
        }
    }

    @Test
    fun `restoring over a populated database changes nothing already there`(): Unit = runBlocking {
        seed(source)
        ArchiveRepository(context, source).export(uri())

        // The same session under the same id, but renamed route and a different
        // priority — the local copy is the one the user is looking at.
        seed(target)
        target.routes().upsert(Fixtures.route(1, "Mein Name", priority = -1, medoidSessionId = "s-1"))

        val report = ArchiveRepository(context, target).restore(uri())
        assertEquals(3, report.sessionsSkipped)
        assertEquals(2, report.routesSkipped)
        assertEquals("Mein Name", target.routes().byId(1)!!.name)
        assertEquals(-1, target.routes().byId(1)!!.priority)
    }

    @Test
    fun `an imported session that is already here under another id is skipped`(): Unit = runBlocking {
        // The case A.15 added the rule for. Hecate recorded this ride, so the app
        // holds it under a UUID; the watch logged the same ride to Google Health,
        // so the takeout holds it under an exercise id. Only the clock relates
        // them, and restoring both gives the clusterer two members for one ride.
        val start = 1_784_109_600_000L
        SessionRepository(context, source).ingest(
            Fixtures.recorded("gh-993", startedAtUtcMs = start + 60_000L, source = SessionSource.IMPORT_TAKEOUT),
        )
        ArchiveRepository(context, source).export(uri())

        SessionRepository(context, target).ingest(Fixtures.recorded("watch-uuid", startedAtUtcMs = start))
        val report = ArchiveRepository(context, target).restore(uri())

        assertEquals(0, report.sessionsRestored)
        assertEquals(1, report.sessionsSkipped)
        assertNull(target.sessions().byId("gh-993"))
        assertEquals(1, target.sessions().count())
    }

    @Test
    fun `a recorded session that looks similar is still restored`(): Unit = runBlocking {
        // The narrowing has to stay narrow. A backup of the app's own recordings
        // is merged by id and nothing else — two watch sessions minutes apart are
        // the user's business, and A.10 promises the restore is exact.
        val start = 1_784_109_600_000L
        source.sessions().insert(sessionRow("backup-1", start + 60_000L, SessionSource.WATCH))
        source.sessions().insertSamples(sampleRows("backup-1"))
        ArchiveRepository(context, source).export(uri())

        SessionRepository(context, target).ingest(Fixtures.recorded("local-1", startedAtUtcMs = start))
        val report = ArchiveRepository(context, target).restore(uri())

        assertEquals(1, report.sessionsRestored)
        assertNotNull(target.sessions().byId("backup-1"))
        assertEquals(2, target.sessions().count())
    }

    // ---------------------------------------------------------------- refusal

    @Test
    fun `a file that is not an archive is refused with something readable`() {
        file.writeText("this is not a zip")
        val thrown = runCatching { runBlocking { ArchiveRepository(context, target).restore(uri()) } }
            .exceptionOrNull()
        assertTrue(thrown is de.hecate.core.archive.Archive.ArchiveException, "was $thrown")
        assertTrue(thrown.message!!.isNotBlank())
        assertEquals(0, runBlocking { target.sessions().count() })
    }

    // --------------------------------------------------------------- plumbing

    private fun uri(): Uri = Uri.fromFile(file)

    /** Three sessions, two named routes, geometry, a profile and a meta row. */
    private suspend fun seed(db: HecateDatabase) {
        val repository = SessionRepository(context, db)
        repository.ingest(Fixtures.recorded("s-1", startedAtUtcMs = 1_784_109_600_000L))
        repository.ingest(Fixtures.recorded("s-2", startedAtUtcMs = 1_784_196_000_000L, lat0 = 50.2))
        repository.ingest(
            Fixtures.recorded(
                "s-3",
                startedAtUtcMs = 1_784_282_400_000L,
                lat0 = 50.3,
                source = SessionSource.IMPORT_TCX,
            ),
        )

        db.routes().upsert(Fixtures.route(1, "Zur Arbeit", priority = 2, medoidSessionId = "s-1"))
        db.routes().upsert(Fixtures.route(2, "Feierabendrunde", priority = -2, medoidSessionId = "s-2"))
        db.routes().insertMembers(
            listOf(RouteMemberEntity(1, "s-1"), RouteMemberEntity(1, "s-3"), RouteMemberEntity(2, "s-2")),
        )
        db.routes().insertPoints(
            (0..2).map { RoutePointEntity(1, it, 50.1 + it * 0.001, 8.6, it * 100.0) },
        )
        db.routes().insertProfile(
            (0..1).map { RouteProfileEntity(1, it, it * 60.0, it * 55.0, it * 65.0, 140.0, 300.0 + it * 12.0) },
        )
        db.meta().put(MetaEntity("bundleVersion", "7"))
    }

    private fun sessionRow(id: String, startedAtUtc: Long, source: SessionSource) =
        de.hecate.phone.db.SessionEntity(
            id = id,
            startedAtUtc = startedAtUtc,
            endedAtUtc = startedAtUtc + 29_000L,
            tzOffsetMinutes = 120,
            exerciseType = ExerciseType.RUNNING,
            activeDurationMs = 29_000L,
            totalDistanceM = 60.0,
            source = source,
            hcWriteState = HcWriteState.SKIPPED,
            ingestedAt = 0L,
        )

    private fun sampleRows(sessionId: String) = (0 until 30).map { i ->
        de.hecate.phone.db.SampleEntity(
            sessionId = sessionId,
            tOffsetMs = i * 1000L,
            activeMs = i * 1000L,
            lat = 50.1 + i * 0.00002,
            lon = 8.6,
            altM = 100.0,
            hrBpm = 130,
            accuracyM = 5.0,
            paused = false,
        )
    }
}
