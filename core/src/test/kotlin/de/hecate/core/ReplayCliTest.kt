package de.hecate.core

import de.hecate.core.archive.Archive
import de.hecate.core.archive.ArchiveCodec
import de.hecate.core.cluster.BundleBuilder
import de.hecate.core.geo.Track
import de.hecate.core.io.BundleCodec
import de.hecate.core.io.SessionCodec
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.SessionSource
import de.hecate.core.replay.ReplayMain
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two commands that exist so a replay can be *picked*: `sessions` lists what
 * is available and what each one matches, `extract` takes one out of a backup.
 *
 * Both are the front of `tools/emu replay`, and the failure they are written
 * against is the quiet one — an extract that produces a file the watch cannot
 * read, or a listing that says a session matches when a replay of it would not.
 */
class ReplayCliTest {

    private fun day(n: Int) = Synthetic.MONDAY_0730_UTC + n * Synthetic.DAY_MS

    private fun tempDir(): File =
        Files.createTempDirectory("replay-cli").toFile().also { it.deleteOnExit() }

    /** Three runs of the same route, which is [Tuning.MIN_MEMBERS], plus one
     *  novel path that belongs to no route. */
    private fun history(): List<RecordedSession> = buildList {
        repeat(3) { add(Synthetic.record("commute-$it", Synthetic.workRoute, day(it), seed = it + 1)) }
        add(Synthetic.record("elsewhere", Synthetic.novelRoute, day(9), seed = 77))
    }

    private fun bundleOf(sessions: List<RecordedSession>) = BundleBuilder.bundleFrom(
        sessions.mapNotNull { Track.of(it) },
        sessions.associate { it.id to it.samples },
    )

    /** A backup holding [sessions], as the phone would have written it. */
    private fun archive(dir: File, sessions: List<RecordedSession>): File {
        val file = File(dir, "backup.zip")
        file.outputStream().use { stream ->
            ArchiveCodec.Writer(stream).use { w ->
                w.writeManifest(
                    Archive.Manifest(
                        formatVersion = Archive.FORMAT_VERSION,
                        exportedAtUtcMs = day(10),
                        appVersion = "test",
                        sessionCount = sessions.size,
                        routeCount = 0,
                    ),
                )
                w.writeDatabase(
                    Archive.Database(
                        sessions = sessions.map { s ->
                            Archive.Session(
                                id = s.id,
                                startedAtUtc = s.header.startedAtUtcMs,
                                tzOffsetMinutes = s.header.tzOffsetMinutes,
                                exerciseType = s.header.exerciseType,
                                activeDurationMs = s.activeDurationMs,
                                totalDistanceM = Track.of(s)?.totalDistanceM ?: 0.0,
                                source = SessionSource.WATCH,
                                hcWriteState = 0,
                            )
                        },
                    ),
                )
                for (s in sessions) w.writeSession(s)
            }
        }
        return file
    }

    @Test
    fun `sessions lists a backup and names what each one will match`() {
        val dir = tempDir()
        val sessions = history()
        val zip = archive(dir, sessions)
        val bundle = File(dir, "bundle.json").apply {
            writeText(BundleCodec.encode(bundleOf(sessions)))
        }

        assertEquals(0, ReplayMain.dispatch(arrayOf("sessions", "--archive", zip.path)))
        assertEquals(
            0,
            ReplayMain.dispatch(arrayOf("sessions", "--archive", zip.path, "--bundle", bundle.path)),
        )
        // A directory of session files is the other half of where fixtures live.
        val fixtures = File(dir, "fixtures").apply { mkdirs() }
        for (s in sessions) File(fixtures, "${s.id}.jsonl").writeText(SessionCodec.encodeSession(s))
        assertEquals(
            0,
            ReplayMain.dispatch(arrayOf("sessions", "--sessions", fixtures.path, "--bundle", bundle.path)),
        )
    }

    @Test
    fun `sessions refuses what it cannot read rather than listing nothing`() {
        val dir = tempDir()
        assertEquals(2, ReplayMain.dispatch(arrayOf("sessions")))
        assertEquals(2, ReplayMain.dispatch(arrayOf("sessions", "--archive", File(dir, "nope.zip").path)))
        val notAnArchive = File(dir, "notes.zip").apply { writeText("this is not a ZIP") }
        assertEquals(2, ReplayMain.dispatch(arrayOf("sessions", "--archive", notAnArchive.path)))
        assertEquals(
            2,
            ReplayMain.dispatch(arrayOf("sessions", "--archive", notAnArchive.path, "--type", "SWIMMING")),
        )
    }

    @Test
    fun `extract writes a file the replay harness reads back unchanged`() {
        val dir = tempDir()
        val sessions = history()
        val zip = archive(dir, sessions)
        val wanted = sessions.first()
        val out = File(dir, "out.jsonl")

        assertEquals(
            0,
            ReplayMain.dispatch(
                arrayOf("extract", "--archive", zip.path, "--session", wanted.id, "--out", out.path),
            ),
        )

        // The whole point of the format being the same on both sides of the ZIP:
        // what comes out is a session file, not a conversion of one.
        val back = assertNotNull(SessionCodec.decodeSession(out.readLines().asSequence()))
        assertEquals(wanted.id, back.id)
        assertEquals(wanted.samples, back.samples)
        assertEquals(wanted.header.exerciseType, back.header.exerciseType)

        // And it replays: `tools/emu replay` pushes exactly this file to the watch.
        val states = ReplayMain.run(back, bundleOf(sessions), ArrayList())
        assertTrue(states.isNotEmpty())
    }

    @Test
    fun `autostop says nothing fired on a history of ordinary workouts`() {
        // The result that matters is the boring one. These rules end recordings
        // and throw away their tails, so the first question to ask of them is how
        // often they would do that to a workout somebody actually did — and the
        // answer has to be never (A.30).
        val dir = tempDir()
        val sessions = history()
        val zip = archive(dir, sessions)

        val printed = capture { assertEquals(0, ReplayMain.dispatch(arrayOf("autostop", "--archive", zip.path))) }
        assertTrue(
            printed.contains("${sessions.size} sessions, 0 would be auto-stopped"),
            printed,
        )

        val fixtures = File(dir, "fixtures").apply { mkdirs() }
        for (session in sessions) {
            File(fixtures, "${session.id}.jsonl").writeText(SessionCodec.encodeSession(session))
        }
        assertEquals(0, ReplayMain.dispatch(arrayOf("autostop", "--sessions", fixtures.path)))
        assertEquals(
            0,
            ReplayMain.dispatch(
                arrayOf("autostop", "--session", File(fixtures, "${sessions.first().id}.jsonl").path),
            ),
        )
        assertEquals(2, ReplayMain.dispatch(arrayOf("autostop")))
    }

    @Test
    fun `autostop names the workout that ended in a car park`() {
        val dir = tempDir()
        val ordinary = Synthetic.record("walk-home", Synthetic.workRoute, day(1), seed = 5)
        // Twenty minutes standing on the last position, which is what a watch
        // still running on the kitchen table records.
        val last = ordinary.samples.last()
        val parked = ordinary.copy(
            samples = ordinary.samples + (1..20 * 60).map { i ->
                last.copy(
                    tOffsetMs = last.tOffsetMs + i * 1000L,
                    activeMs = last.activeMs + i * 1000L,
                )
            },
        )
        val file = File(dir, "parked.jsonl").apply { writeText(SessionCodec.encodeSession(parked)) }

        val printed = capture {
            assertEquals(0, ReplayMain.dispatch(arrayOf("autostop", "--session", file.path)))
        }
        assertTrue(printed.contains("STATIONARY"), printed)
        assertTrue(printed.contains("1 sessions, 1 would be auto-stopped"), printed)
    }

    /** Everything the command printed while [block] ran. */
    private fun capture(block: () -> Unit): String {
        val buffer = java.io.ByteArrayOutputStream()
        val previous = System.out
        System.setOut(java.io.PrintStream(buffer, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(previous)
        }
        return buffer.toString(Charsets.UTF_8)
    }

    @Test
    fun `extract takes a unique prefix and refuses an ambiguous one`() {
        val dir = tempDir()
        val sessions = history()
        val zip = archive(dir, sessions)
        val out = File(dir, "out.jsonl")

        // "commute-" is the prefix of three of them; "commute-1" of exactly one.
        assertEquals(
            2,
            ReplayMain.dispatch(
                arrayOf("extract", "--archive", zip.path, "--session", "commute-", "--out", out.path),
            ),
        )
        assertTrue(!out.exists())
        assertEquals(
            0,
            ReplayMain.dispatch(
                arrayOf("extract", "--archive", zip.path, "--session", "commute-1", "--out", out.path),
            ),
        )
        assertEquals("commute-1", assertNotNull(SessionCodec.decodeSession(out.readLines().asSequence())).id)

        assertEquals(
            2,
            ReplayMain.dispatch(
                arrayOf("extract", "--archive", zip.path, "--session", "nothing", "--out", out.path),
            ),
        )
    }
}
