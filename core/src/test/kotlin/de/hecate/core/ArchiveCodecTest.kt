package de.hecate.core

import de.hecate.core.archive.Archive
import de.hecate.core.archive.ArchiveCodec
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.MatchEvent
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.MatchPhase
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.Sample
import de.hecate.core.model.SessionEndReason
import de.hecate.core.model.SessionFooter
import de.hecate.core.model.SessionEvent
import de.hecate.core.model.SessionEventType
import de.hecate.core.model.SessionHeader
import de.hecate.core.model.SessionSource
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The backup archive is the one thing in this app whose bugs are invisible until
 * the day the data is already gone. These tests read every archive back.
 */
class ArchiveCodecTest {

    // ------------------------------------------------------------------ fixtures

    private fun sampleAt(t: Long) = Sample(
        tOffsetMs = t * 1000,
        activeMs = t * 1000,
        lat = 50.1 + t * 1e-5,
        lon = 8.9 + t * 1e-5,
        altM = 120.0 + t,
        hrBpm = 140 + (t % 7).toInt(),
        accuracyM = 8.5,
        paused = t % 20 == 0L,
    )

    private fun session(id: String, samples: Int = 50) = RecordedSession(
        header = SessionHeader(
            sessionId = id,
            startedAtUtcMs = 1_700_000_000_000,
            tzOffsetMinutes = 60,
            exerciseType = ExerciseType.RUNNING,
            source = SessionSource.WATCH,
        ),
        samples = (0 until samples).map { sampleAt(it.toLong()) },
        events = listOf(
            SessionEvent(tOffsetMs = 3000, type = SessionEventType.GPS_ACQUIRED),
            SessionEvent(tOffsetMs = 20_000, type = SessionEventType.PAUSE_AUTO),
            SessionEvent(tOffsetMs = 26_000, type = SessionEventType.RESUME_AUTO),
            SessionEvent(tOffsetMs = 31_000, type = SessionEventType.MATCHER_DISABLED, note = "3 Fehler"),
        ),
        footer = SessionFooter(
            endedAtUtcMs = 1_700_000_000_000 + samples * 1000L,
            activeDurationMs = samples * 1000L,
            totalDistanceM = 9000.0,
            reason = SessionEndReason.IMPORTED,
        ),
        matchLog = listOf(
            MatchLogEntry(
                tOffsetMs = 5000,
                event = MatchEvent.COMMIT,
                phase = MatchPhase.COMMITTED,
                nullWeight = 0.02,
                weights = mapOf(3L to 0.91),
                committedRouteId = 3,
                travelledM = 1200.0,
            ),
        ),
    )

    private fun manifest(version: Int = Archive.FORMAT_VERSION, sessions: Int = 1) = Archive.Manifest(
        formatVersion = version,
        exportedAtUtcMs = 1_700_000_500_000,
        appVersion = "1.0",
        sessionCount = sessions,
        routeCount = 1,
    )

    private fun database(sessionIds: List<String>) = Archive.Database(
        sessions = sessionIds.map {
            Archive.Session(
                id = it,
                startedAtUtc = 1_700_000_000_000,
                tzOffsetMinutes = 60,
                exerciseType = ExerciseType.RUNNING,
                activeDurationMs = 50_000,
                totalDistanceM = 9000.0,
                source = SessionSource.WATCH,
                hcWriteState = 1,
                matchedRouteId = 3,
                committedAtM = 1200.0,
                avgHr = 143.0,
                ingestedAt = 1_700_000_400_000,
            )
        },
        routes = listOf(
            Archive.Route(
                id = 3,
                name = "Weg zur Arbeit",
                nameIsCustom = true,
                exerciseType = ExerciseType.RUNNING,
                lengthM = 9000.0,
                memberCount = 12,
                medoidSessionId = sessionIds.first(),
                createdAt = 1_699_000_000_000,
                active = true,
                priority = 2,
            ),
        ),
        routeMembers = sessionIds.map { Archive.RouteMember(3, it) },
        routePoints = listOf(Archive.RoutePoint(3, 0, 50.1, 8.9, 0.0)),
        routeProfile = listOf(Archive.ProfileBin(3, 0, 0.0, 1.0, 2.0, 140.0)),
        meta = listOf(Archive.Meta("bundleVersion", "7")),
    )

    private fun write(
        file: File,
        manifest: Archive.Manifest,
        database: Archive.Database,
        sessions: List<RecordedSession>,
    ): Long {
        val writer = ArchiveCodec.Writer(file.outputStream())
        writer.use {
            it.writeManifest(manifest)
            it.writeDatabase(database)
            sessions.forEach { s -> it.writeSession(s) }
        }
        return writer.bytesWritten
    }

    private fun tempFile() = File.createTempFile("archive-test", ".zip").also { it.deleteOnExit() }

    // --------------------------------------------------------------------- tests

    @Test
    fun `a session survives the round trip intact`() {
        val original = session("s-1")
        val file = tempFile()
        write(file, manifest(), database(listOf("s-1")), listOf(original))

        ArchiveCodec.Reader(file).use { reader ->
            val back = reader.session("s-1")!!
            assertEquals(original.header, back.header)
            assertEquals(original.footer, back.footer)
            assertEquals(original.matchLog, back.matchLog)
            assertEquals(original.events, back.events)
            assertContentEquals(original.samples, back.samples)
        }
    }

    /**
     * Lifecycle events survive, notes and all.
     *
     * Split out from the round trip above because these were the one part of a
     * session the phone used to parse and then throw away: ingest read them,
     * stored them nowhere, and the export wrote an events section that was
     * always empty. The assertion above would not have caught that on its own —
     * it never looked at `events` — so this one names them.
     */
    @Test
    fun `lifecycle events survive the round trip, notes included`() {
        val original = session("s-ev")
        val file = tempFile()
        write(file, manifest(), database(listOf("s-ev")), listOf(original))

        ArchiveCodec.Reader(file).use { reader ->
            val back = reader.session("s-ev")!!
            assertEquals(original.events, back.events)
            assertEquals("3 Fehler", back.events.last().note)
            assertEquals(SessionEventType.GPS_ACQUIRED, back.events.first().type)
        }
    }

    /** A session that recorded no events is not the same as one whose events
     *  were lost; it must read back as an empty list, not fail. */
    @Test
    fun `a session with no events round trips as empty`() {
        val original = session("s-noev").copy(events = emptyList())
        val file = tempFile()
        write(file, manifest(), database(listOf("s-noev")), listOf(original))

        ArchiveCodec.Reader(file).use { reader ->
            assertTrue(reader.session("s-noev")!!.events.isEmpty())
        }
    }

    /** Every field of every sample, including the nullable ones and `paused`. */
    @Test
    fun `nullable sample fields round trip rather than defaulting`() {
        val sparse = RecordedSession(
            header = session("s-2").header,
            samples = listOf(
                Sample(tOffsetMs = 0, activeMs = 0, lat = 50.0, lon = 8.0),
                Sample(
                    tOffsetMs = 1000, activeMs = 1000, lat = 50.1, lon = 8.1,
                    altM = null, hrBpm = null, accuracyM = null, paused = true,
                ),
            ),
        )
        val file = tempFile()
        write(file, manifest(), database(listOf("s-2")), listOf(sparse))

        ArchiveCodec.Reader(file).use { reader ->
            val back = reader.session("s-2")!!
            assertContentEquals(sparse.samples, back.samples)
            assertNull(back.samples[1].altM)
            assertNull(back.samples[1].hrBpm)
            assertNull(back.samples[1].accuracyM)
            assertTrue(back.samples[1].paused)
            assertNull(back.footer, "a session written without a footer must not gain one")
        }
    }

    @Test
    fun `the database part round trips, names and priorities included`() {
        val db = database(listOf("s-1", "s-2"))
        val file = tempFile()
        write(file, manifest(sessions = 2), db, listOf(session("s-1"), session("s-2")))

        ArchiveCodec.Reader(file).use { reader ->
            assertEquals(db, reader.database)
            // The two things no amount of re-clustering could recompute.
            assertEquals("Weg zur Arbeit", reader.database.routes.single().name)
            assertEquals(2, reader.database.routes.single().priority)
            assertEquals(2, reader.manifest.sessionCount)
        }
    }

    /** Many sessions in one archive, each readable by id and not mixed up. */
    @Test
    fun `sessions are addressed by id, not by position`() {
        val ids = (1..12).map { "session-$it" }
        val sessions = ids.mapIndexed { i, id -> session(id, samples = 10 + i) }
        val file = tempFile()
        write(file, manifest(sessions = ids.size), database(ids), sessions)

        ArchiveCodec.Reader(file).use { reader ->
            for ((i, id) in ids.withIndex()) {
                val back = reader.session(id)!!
                assertEquals(id, back.header.sessionId)
                assertEquals(10 + i, back.samples.size, "wrong session came back for $id")
            }
            assertNull(reader.session("never-written"))
        }
    }

    /** The failure that would otherwise only show up on a big history: a writer
     *  whose last entry is not flushed before the ZIP closes. */
    @Test
    fun `the last session in a large archive is complete`() {
        val ids = (1..40).map { "s-$it" }
        val sessions = ids.map { session(it, samples = 600) }
        val file = tempFile()
        val bytes = write(file, manifest(sessions = ids.size), database(ids), sessions)

        assertEquals(file.length(), bytes, "reported size must match the file on disk")
        ArchiveCodec.Reader(file).use { reader ->
            val last = reader.session("s-40")!!
            assertEquals(600, last.samples.size)
            assertEquals(sessions.last().samples.last(), last.samples.last())
        }
    }

    @Test
    fun `an archive from a future format version is refused`() {
        val file = tempFile()
        write(file, manifest(version = Archive.FORMAT_VERSION + 1), database(listOf("s-1")), listOf(session("s-1")))

        val e = assertFailsWith<Archive.ArchiveException> {
            ArchiveCodec.Reader(file).use { it.database }
        }
        assertTrue(e.message!!.contains("Format"), "the message should say what is wrong: ${e.message}")
    }

    @Test
    fun `a zip that is not a hecate archive is refused`() {
        val file = tempFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("holiday.jpg"))
            zip.write(ByteArray(32))
            zip.closeEntry()
        }
        assertFailsWith<Archive.ArchiveException> { ArchiveCodec.Reader(file).use { it.manifest } }
    }

    @Test
    fun `a manifest that is not json is refused rather than silently empty`() {
        val file = tempFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(Archive.MANIFEST))
            zip.write("this is not json".toByteArray())
            zip.closeEntry()
        }
        assertFailsWith<Archive.ArchiveException> { ArchiveCodec.Reader(file).use { it.manifest } }
    }

    /** Forward compatibility: a reader must tolerate fields a later version added,
     *  or a newer export becomes unreadable for no reason. */
    @Test
    fun `unknown fields in the database part are ignored`() {
        val file = tempFile()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(Archive.MANIFEST))
            zip.write(Archive.json.encodeToString(manifest()).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(Archive.DATABASE))
            zip.write(
                """{"sessions":[],"routes":[],"somethingFromTheFuture":{"a":1},"meta":[]}""".toByteArray(),
            )
            zip.closeEntry()
        }
        ArchiveCodec.Reader(file).use { reader ->
            assertTrue(reader.database.sessions.isEmpty())
            assertTrue(reader.database.routes.isEmpty())
        }
    }

    /** The entry layout is a promise to the replay harness, not an internal
     *  detail: `unzip sessions/ into fixtures/` has to keep working. */
    @Test
    fun `session entries are plain jsonl under sessions slash`() {
        val file = tempFile()
        write(file, manifest(), database(listOf("s-1")), listOf(session("s-1")))

        ZipFile(file).use { zip ->
            val entry = zip.getEntry("sessions/s-1.jsonl")
            assertTrue(entry != null, "expected sessions/s-1.jsonl in the archive")
            val lines = zip.getInputStream(entry).bufferedReader().readLines().filter { it.isNotBlank() }
            // header + 50 samples + 4 event lines + 1 match line + footer
            assertEquals(57, lines.size)
            assertTrue(lines.first().contains("\"k\":\"h\""), "first line should be the header: ${lines.first()}")
            assertTrue(lines.last().contains("\"k\":\"f\""), "last line should be the footer: ${lines.last()}")
        }
    }

    @Test
    fun `the suggested file name is sortable and ends in zip`() {
        val name = Archive.suggestedFileName(1_700_000_000_000)
        assertTrue(name.startsWith("hecate-sicherung-"), name)
        assertTrue(name.endsWith(".zip"), name)
        assertTrue(Regex("""hecate-sicherung-\d{8}-\d{4}\.zip""").matches(name), name)
    }
}
