package de.hecate.core

import de.hecate.core.model.ExerciseType
import de.hecate.core.tcx.TcxParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TcxParserTest {

    private fun tcx(sport: String = "Running", extra: String = ""): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">
          <Activities>
            <Activity Sport="$sport">
              <Id>2024-03-04T07:30:00.000Z</Id>
              <Lap StartTime="2024-03-04T07:30:00.000Z">
                <TotalTimeSeconds>3.0</TotalTimeSeconds>
                <DistanceMeters>12.0</DistanceMeters>
                <Track>
                  <Trackpoint>
                    <Time>2024-03-04T07:30:00.000Z</Time>
                    <Position><LatitudeDegrees>50.1333</LatitudeDegrees><LongitudeDegrees>8.9167</LongitudeDegrees></Position>
                    <AltitudeMeters>105.0</AltitudeMeters>
                    <DistanceMeters>0.0</DistanceMeters>
                    <HeartRateBpm><Value>120</Value></HeartRateBpm>
                  </Trackpoint>
                  <Trackpoint>
                    <Time>2024-03-04T07:30:01Z</Time>
                    <Position><LatitudeDegrees>50.13335</LatitudeDegrees><LongitudeDegrees>8.9167</LongitudeDegrees></Position>
                    <DistanceMeters>5.6</DistanceMeters>
                    <HeartRateBpm><Value>124</Value></HeartRateBpm>
                  </Trackpoint>
                  <Trackpoint>
                    <Time>2024-03-04T07:30:02Z</Time>
                    <Position><LatitudeDegrees>50.1334</LatitudeDegrees><LongitudeDegrees>8.9167</LongitudeDegrees></Position>
                    <DistanceMeters>12.0</DistanceMeters>
                  </Trackpoint>
                  $extra
                </Track>
              </Lap>
            </Activity>
          </Activities>
        </TrainingCenterDatabase>
    """.trimIndent()

    @Test
    fun `parses a minimal tcx`() {
        val s = TcxParser.parse(tcx().byteInputStream()) { _, i -> "imp-$i" }
        assertEquals(1, s.size)
        val session = s[0]
        assertEquals("imp-0", session.id)
        assertEquals(ExerciseType.RUNNING, session.header.exerciseType)
        assertEquals(de.hecate.core.model.SessionSource.IMPORT_TCX, session.header.source)
        assertEquals(3, session.samples.size)
        assertEquals(0L, session.samples[0].tOffsetMs)
        assertEquals(2000L, session.samples[2].tOffsetMs)
        assertEquals(124, session.samples[1].hrBpm)
        assertNotNull(session.footer)
        assertEquals(12.0, session.footer!!.totalDistanceM)
    }

    @Test
    fun `sport attribute maps to exercise type`() {
        assertEquals(ExerciseType.BIKING, TcxParser.exerciseTypeOf("Biking"))
        assertEquals(ExerciseType.WALKING, TcxParser.exerciseTypeOf("Walking"))
        assertEquals(ExerciseType.RUNNING, TcxParser.exerciseTypeOf("Other"))
    }

    @Test
    fun `trackpoints without a position are skipped`() {
        val extra = "<Trackpoint><Time>2024-03-04T07:30:03Z</Time></Trackpoint>"
        val session = TcxParser.parse(tcx(extra = extra).byteInputStream()) { _, i -> "imp-$i" }[0]
        assertEquals(3, session.samples.size)
    }

    @Test
    fun `a file with no activity is rejected`() {
        assertFailsWith<TcxParser.TcxException> {
            TcxParser.parse("<TrainingCenterDatabase/>".byteInputStream()) { _, i -> "x$i" }
        }
    }

    @Test
    fun `malformed xml is rejected with a readable error`() {
        assertFailsWith<TcxParser.TcxException> {
            TcxParser.parse("not xml at all".byteInputStream()) { _, i -> "x$i" }
        }
    }

    @Test
    fun `iso 8601 timestamps parse`() {
        assertEquals(0L, TcxParser.parseIso8601Millis("1970-01-01T00:00:00Z"))
        assertEquals(1_709_537_400_000L, TcxParser.parseIso8601Millis("2024-03-04T07:30:00.000Z"))
        assertEquals(1_709_537_400_000L, TcxParser.parseIso8601Millis("2024-03-04T09:30:00+02:00"))
        assertEquals(1_709_537_400_500L, TcxParser.parseIso8601Millis("2024-03-04T07:30:00.5Z"))
    }

    @Test
    fun `external entities are never resolved`() {
        // The factory flags that used to guarantee this are best-effort — Android
        // rejects some of them outright — so the entity resolver carries the weight.
        // Either the document is refused or the entity expands to nothing; what must
        // never happen is the file's contents reaching a parsed field.
        val secret = java.io.File.createTempFile("hecate-xxe", ".txt").apply {
            writeText("TOP-SECRET-CONTENT")
            deleteOnExit()
        }
        val xxe = """<?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE TrainingCenterDatabase [ <!ENTITY xxe SYSTEM "file://${secret.absolutePath}"> ]>
            <TrainingCenterDatabase>
              <Activities>
                <Activity Sport="&xxe;">
                  <Id>2024-03-04T07:30:00.000Z</Id>
                </Activity>
              </Activities>
            </TrainingCenterDatabase>
        """.trimIndent()

        val parsed = runCatching { TcxParser.parse(xxe.byteInputStream()) { _, i -> "x$i" } }
        val rendered = parsed.getOrNull()?.toString().orEmpty()
        assertTrue("TOP-SECRET-CONTENT" !in rendered, "external entity was resolved: $rendered")
    }

    @Test
    fun `duplicate detection uses start time and duration`() {
        val t = 1_709_537_400_000L
        assertTrue(de.hecate.core.geo.Track.likelyDuplicate(t, 3_600_000, t + 60_000, 3_500_000))
        assertTrue(!de.hecate.core.geo.Track.likelyDuplicate(t, 3_600_000, t + 3_600_000, 3_600_000))
        assertTrue(!de.hecate.core.geo.Track.likelyDuplicate(t, 3_600_000, t + 60_000, 600_000))
    }
}
