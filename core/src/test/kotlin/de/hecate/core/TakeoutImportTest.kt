package de.hecate.core

import de.hecate.core.archive.Archive
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.SessionSource
import de.hecate.core.takeout.Csv
import de.hecate.core.takeout.GoogleHealth
import de.hecate.core.takeout.TakeoutImport
import de.hecate.core.takeout.TakeoutSource
import kotlin.test.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The takeout import, against a takeout built by hand.
 *
 * A real export is 300 MB of someone's health history and cannot go in the
 * repository, so the fixtures here are the smallest thing shaped like one. What
 * they are shaped *like* is the export that exists: the column names, the two
 * duplicated data sources, the `+01:00` in February and `+02:00` in July, and the
 * hundreds of empty auto-detected rows are all copied from a real
 * `UserExercises_2026-01-27.csv`.
 */
class TakeoutImportTest {

    // ------------------------------------------------------------------- csv

    @Test
    fun `quoted fields keep their commas`() {
        val rows = Csv.read(
            """
            a,b,c
            1,"x, y, z",3
            4,,6
            """.trimIndent().reader().buffered(),
        )
        assertEquals(2, rows.size)
        assertEquals("x, y, z", rows[0]["b"])
        assertEquals("3", rows[0]["c"])
        // The whole reason this is not a split(","): the column after the quoted
        // one has to still be the column after the quoted one.
        assertEquals("6", rows[1]["c"])
        assertNull(rows[1]["b"])
    }

    @Test
    fun `a doubled quote is one quote`() {
        val rows = Csv.read("a\n\"say \"\"hi\"\"\"\n".reader().buffered())
        assertEquals("say \"hi\"", rows[0]["a"])
    }

    @Test
    fun `a header alone is no rows`() {
        assertEquals(0, Csv.read("a,b,c\n".reader().buffered()).size)
        assertEquals(0, Csv.read("".reader().buffered()).size)
    }

    // ---------------------------------------------------------------- offsets

    @Test
    fun `utc offsets parse`() {
        assertEquals(120, GoogleHealth.offsetMinutes("+02:00"))
        assertEquals(60, GoogleHealth.offsetMinutes("+01:00"))
        assertEquals(-330, GoogleHealth.offsetMinutes("-05:30"))
        assertEquals(0, GoogleHealth.offsetMinutes("Z"))
        assertNull(GoogleHealth.offsetMinutes("nonsense"))
    }

    @Test
    fun `activity names map to the three types`() {
        assertEquals(ExerciseType.BIKING, GoogleHealth.exerciseTypeOf("Outdoor Bike"))
        assertEquals(ExerciseType.BIKING, GoogleHealth.exerciseTypeOf("Radfahren im Freien"))
        assertEquals(ExerciseType.WALKING, GoogleHealth.exerciseTypeOf("Hike"))
        assertEquals(ExerciseType.WALKING, GoogleHealth.exerciseTypeOf("Outdoor Walk"))
        assertEquals(ExerciseType.RUNNING, GoogleHealth.exerciseTypeOf("Outdoor Run"))
        // Skiing is a real track this app has no type for. Refusing it is the
        // point; filing it under "running" would poison the clusterer.
        assertNull(GoogleHealth.exerciseTypeOf("Skiing"))
        assertNull(GoogleHealth.exerciseTypeOf("Tennis"))
    }

    // ----------------------------------------------------------------- import

    @Test
    fun `a tracked workout becomes a session`() {
        val result = read()
        val session = result.sessions.single { it.id == "gh-100" }

        assertEquals(SessionSource.IMPORT_TAKEOUT, session.header.source)
        assertEquals(ExerciseType.BIKING, session.header.exerciseType)
        assertEquals(5, session.samples.size)
        assertEquals(49.500, session.samples.first().lat, 1e-9)
        assertEquals(303.8, session.samples.first().altM!!, 1e-9)
        // Positions are 1 s apart, so the first sample is at the start and the
        // last four seconds in.
        assertEquals(0L, session.samples.first().tOffsetMs)
        assertEquals(4_000L, session.samples.last().tOffsetMs)
        assertEquals(4_000L, session.activeDurationMs)
        // Google's own total wins over the geometry: it knows about the ground
        // between two fixes that the straight line does not.
        assertEquals(1234.0, session.footer!!.totalDistanceM, 1e-9)
    }

    @Test
    fun `the offset comes from the exercise, not from the machine running this`() {
        val result = read()
        // Same fixture, one workout in February and one in July, in a takeout
        // whose profile says Europe/Berlin. Getting one of these right and the
        // other wrong is exactly the bug that a fixed offset produces.
        assertEquals(60, result.sessions.single { it.id == "gh-100" }.header.tzOffsetMinutes)
        assertEquals(120, result.sessions.single { it.id == "gh-300" }.header.tzOffsetMinutes)
    }

    @Test
    fun `duplicated data sources are one track, not two`() {
        // The fixture lists every fix twice, once per data source, as the export
        // does. Five positions in the file, five samples out.
        assertEquals(5, read().sessions.single { it.id == "gh-100" }.samples.size)
    }

    @Test
    fun `heart rate is matched to the nearest position`() {
        val samples = read().sessions.single { it.id == "gh-100" }.samples
        assertEquals(120, samples[0].hrBpm)
        assertEquals(124, samples[4].hrBpm)
        // Two readings, five positions: the three in between take whichever is
        // nearer, and the exact tie goes to the earlier one.
        assertEquals(120, samples[2].hrBpm)
        assertEquals(124, samples[3].hrBpm)
    }

    @Test
    fun `a reading too far away is no reading at all`() {
        // July's file holds one heart rate half an hour after the run. A sample
        // without a heart rate is better than one carrying a number from a
        // different activity.
        val samples = read().sessions.single { it.id == "gh-300" }.samples
        assertTrue(samples.all { it.hrBpm == null })
    }

    @Test
    fun `an auto-detected row overlapping a real workout is dropped`() {
        val result = read()
        assertNull(result.sessions.firstOrNull { it.id == "gh-200" })
        val skip = result.skipped.single { it.startUtcMs == iso("2026-02-14T14:00:02Z") }
        assertTrue(skip.reason.contains("überschneidet"), "wrong reason: ${skip.reason}")
        // The watch guesses at hundreds of these. They are not worth a line each.
        assertTrue(!skip.notable)
    }

    @Test
    fun `a tracked workout with no positions is worth reporting`() {
        // The takeout keeps the exercise long after it has dropped the day's
        // position file. That is a real gap in the backfill, not routine noise.
        val skip = read().skipped.single { it.activityName == "Hike" }
        assertTrue(skip.notable)
        assertTrue(skip.reason.contains("keine Positionen"), "wrong reason: ${skip.reason}")
    }

    @Test
    fun `an unsupported sport is refused and said out loud`() {
        val skip = read().skipped.single { it.activityName == "Skiing" }
        assertTrue(skip.notable, "a workout the user started is worth reporting")
        assertTrue(skip.reason.contains("--map"), "the report should say how to force it")
    }

    @Test
    fun `an unsupported sport can be forced onto a type`() {
        val result = read(TakeoutImport.Options(typeOverrides = mapOf("skiing" to ExerciseType.WALKING)))
        assertEquals(ExerciseType.WALKING, result.sessions.single { it.id == "gh-400" }.header.exerciseType)
    }

    @Test
    fun `the same takeout twice yields the same ids`() {
        assertEquals(
            read().sessions.map { it.id },
            read().sessions.map { it.id },
        )
    }

    @Test
    fun `a workout already in the app is left out`() {
        val first = read()
        val already = first.database.sessions
        val second = read(TakeoutImport.Options(existing = already))
        assertTrue(second.sessions.isEmpty(), "a second pass should find nothing new")
        for (id in already.map { it.id }) {
            assertTrue(
                second.skipped.any { it.reason.contains(id) },
                "$id should have been reported as already present",
            )
        }
    }

    @Test
    fun `a workout the watch recorded under another id is still recognised`() {
        // The case the ids cannot catch: Hecate recorded this ride itself, so the
        // app holds it under a UUID, and the watch logged the same ride to Google
        // Health, so the takeout holds it under an exercise id. Only the clock
        // relates them.
        val mine = Archive.Session(
            id = "7f3c-not-a-google-id",
            startedAtUtc = iso("2026-02-14T14:00:00Z") + 30_000L,
            tzOffsetMinutes = 60,
            exerciseType = "BIKING",
            activeDurationMs = 4_000L,
            totalDistanceM = 1234.0,
            source = SessionSource.WATCH.name,
            hcWriteState = 1,
        )
        val result = read(TakeoutImport.Options(existing = listOf(mine)))
        assertNull(result.sessions.firstOrNull { it.id == "gh-100" })
        assertTrue(result.skipped.any { it.reason.contains("7f3c-not-a-google-id") })
    }

    @Test
    fun `the row a restore reads says the session came from an export`() {
        val row = read().database.sessions.single { it.id == "gh-100" }
        assertEquals(SessionSource.IMPORT_TAKEOUT.name, row.source)
        // 3 is HcWriteState.SKIPPED. These workouts came out of Health Connect;
        // writing them back would duplicate every one of them there.
        assertEquals(3, row.hcWriteState)
        assertEquals(122.0, row.avgHr!!, 1.0)
    }

    @Test
    fun `a split takeout is read as one export`() {
        val dir = File.createTempFile("takeout-split", "").let { it.delete(); it.mkdirs(); it }
        try {
            // The exercise table in one part, the positions in the other, which is
            // how Takeout actually splits a large export.
            zip(File(dir, "takeout-1-001.zip"), mapOf("Takeout/Google Health/Whatever/$EXERCISES" to exercisesCsv()))
            zip(
                File(dir, "takeout-1-002.zip"),
                mapOf(
                    "Takeout/Google Health/Physical Activity/gps_location_2026-02-14.csv" to gpsCsv(),
                    "Takeout/Google Health/Physical Activity/heart_rate_2026-02-14.csv" to hrCsv(),
                ),
            )
            val files = TakeoutSource.resolve(File(dir, "takeout-1-*.zip").path, dir)
            assertEquals(2, files.size)
            val result = TakeoutSource(files).use { TakeoutImport.read(it) }
            assertNotNull(result.sessions.firstOrNull { it.id == "gh-100" })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `with no profile and no offset the fallback is Berlin`() {
        val dir = File.createTempFile("takeout-bare", "").let { it.delete(); it.mkdirs(); it }
        try {
            val bare = exercisesCsv().replace("+01:00", "").replace("+02:00", "")
            zip(
                File(dir, "takeout-001.zip"),
                mapOf(
                    "x/$EXERCISES" to bare,
                    "x/gps_location_2026-02-14.csv" to gpsCsv(),
                ),
            )
            val result = TakeoutSource(TakeoutSource.resolve(null, dir)).use { TakeoutImport.read(it) }
            assertEquals(GoogleHealth.FALLBACK_ZONE, result.fallbackZone)
            // February in Berlin is +01:00, and it is resolved at the instant of
            // the workout rather than at the moment of import.
            assertEquals(60, result.sessions.single { it.id == "gh-100" }.header.tzOffsetMinutes)
        } finally {
            dir.deleteRecursively()
        }
    }

    // --------------------------------------------------------------- fixtures

    private fun read(options: TakeoutImport.Options = TakeoutImport.Options()): TakeoutImport.Result {
        val file = File.createTempFile("takeout", ".zip")
        try {
            zip(
                file,
                mapOf(
                    "Takeout/Google Health/Health Fitness Data_GoogleData/$EXERCISES" to exercisesCsv(),
                    "Takeout/Google Health/Your Profile/Profile.csv" to profileCsv(),
                    "Takeout/Google Health/Physical Activity_GoogleData/gps_location_2026-02-14.csv" to gpsCsv(),
                    "Takeout/Google Health/Physical Activity_GoogleData/heart_rate_2026-02-14.csv" to hrCsv(),
                    "Takeout/Google Health/Physical Activity_GoogleData/gps_location_2026-07-14.csv" to gpsCsvJuly(),
                    "Takeout/Google Health/Physical Activity_GoogleData/heart_rate_2026-07-14.csv" to hrCsvJuly(),
                    "Takeout/Google Health/Physical Activity_GoogleData/gps_location_2026-03-20.csv" to gpsCsvSki(),
                    "Takeout/Google Health/Whatever/README.txt" to "not a workout",
                    "Takeout/Google Health/Elsewhere/README.txt" to "also not a workout",
                ),
            )
            return TakeoutSource(listOf(file)).use { TakeoutImport.read(it, options) }
        } finally {
            file.delete()
        }
    }

    private fun zip(file: File, entries: Map<String, String>) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun exercisesCsv() = buildString {
        appendLine("exercise_id,exercise_start,exercise_end,utc_offset,activity_name,log_type,tracker_total_distance_mm,events")
        appendLine("100,2026-02-14T14:00:00Z,2026-02-14T14:00:04Z,+01:00,Bike,TRACKER,1234000,\"[Type: SPLIT, Steps: 1,2,3]\"")
        appendLine("200,2026-02-14T14:00:02Z,2026-02-14T14:20:00Z,+01:00,Outdoor Walk,AUTO_DETECTED,0,")
        appendLine("300,2026-07-14T09:00:00Z,2026-07-14T09:00:04Z,+02:00,Outdoor Run,TRACKER,,")
        appendLine("400,2026-03-20T09:00:00Z,2026-03-20T09:00:04Z,+01:00,Skiing,TRACKER,5000000,")
        appendLine("500,2026-09-01T09:00:00Z,2026-09-01T10:00:00Z,+02:00,Hike,TRACKER,3000000,")
    }

    private fun profileCsv() = "id,timezone,locale\nCZ8N6P,Europe/Berlin,de_DE\n"

    /** Five fixes, each repeated once per data source exactly as the export does. */
    private fun gpsCsv() = buildString {
        appendLine("timestamp,latitude,longitude,altitude,data source")
        for (i in 0..4) {
            val row = "2026-02-14T14:00:0$i" + "Z,49.${500 + i},11.${100 + i},303.8"
            appendLine("$row,Google Health App")
            appendLine("$row,Pixel Watch 4")
        }
    }

    private fun gpsCsvJuly() = buildString {
        appendLine("timestamp,latitude,longitude,altitude,data source")
        for (i in 0..4) appendLine("2026-07-14T09:00:0$i" + "Z,50.${600 + i},9.${200 + i},101.0,Pixel Watch 4")
    }

    private fun gpsCsvSki() = buildString {
        appendLine("timestamp,latitude,longitude,altitude,data source")
        for (i in 0..4) appendLine("2026-03-20T09:00:0$i" + "Z,47.${100 + i},11.${400 + i},1800.0,Pixel Watch 4")
    }

    /** Two readings four seconds apart, for a workout four seconds long. */
    private fun hrCsv() = """
        timestamp,beats per minute,data source
        2026-02-14T14:00:00Z,120.0,Pixel Watch 4
        2026-02-14T14:00:04Z,124.0,Pixel Watch 4
    """.trimIndent() + "\n"

    /** One reading, half an hour after the run it is filed with. */
    private fun hrCsvJuly() = """
        timestamp,beats per minute,data source
        2026-07-14T09:30:00Z,131.0,Pixel Watch 4
    """.trimIndent() + "\n"

    private fun iso(s: String): Long = de.hecate.core.tcx.TcxParser.parseIso8601Millis(s)!!

    private companion object {
        const val EXERCISES = "UserExercises_2026-01-27.csv"
    }
}
