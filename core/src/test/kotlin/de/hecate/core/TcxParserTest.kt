package de.hecate.core

import de.hecate.core.model.ExerciseType
import de.hecate.core.tcx.TcxParser
import java.time.ZoneId
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
    fun `a UTC export takes its offset from the zone it happened in`() {
        // The bug this exists for: an import that hardcoded offset 0 displayed
        // every workout two hours early all summer, and one hour early all
        // winter, because a TCX from Google Health is stamped in UTC and says
        // nothing about where the user was.
        val summer = parse("2026-07-04T05:30:00Z", ZoneId.of("Europe/Berlin"))
        assertEquals(120, summer.header.tzOffsetMinutes)

        val winter = parse("2026-01-04T05:30:00Z", ZoneId.of("Europe/Berlin"))
        assertEquals(60, winter.header.tzOffsetMinutes)
    }

    @Test
    fun `an export that names its own offset is believed over the zone`() {
        // A file that spells out +05:45 was recorded in Nepal, whatever zone the
        // phone doing the import is in.
        val session = parse("2026-07-04T11:15:00+05:45", ZoneId.of("Europe/Berlin"))
        assertEquals(345, session.header.tzOffsetMinutes)
        // and the instant is still the instant
        assertEquals(TcxParser.parseIso8601Millis("2026-07-04T05:30:00Z"), session.header.startedAtUtcMs)
    }

    @Test
    fun `the offset is resolved at the workout, not at the import`() {
        // Same zone, same import, two sides of a daylight-saving change.
        val before = parse("2026-03-28T06:00:00Z", ZoneId.of("Europe/Berlin"))
        val after = parse("2026-03-30T06:00:00Z", ZoneId.of("Europe/Berlin"))
        assertEquals(60, before.header.tzOffsetMinutes)
        assertEquals(120, after.header.tzOffsetMinutes)
    }

    /** One activity whose `<Id>` and trackpoints sit at [start]. */
    private fun parse(start: String, zone: ZoneId): de.hecate.core.model.RecordedSession {
        val startMs = TcxParser.parseIso8601Millis(start)!!
        val times = (0..2).map { i ->
            java.time.format.DateTimeFormatter.ISO_INSTANT
                .format(java.time.Instant.ofEpochMilli(startMs + i * 1000L))
        }
        val points = times.joinToString("") { t ->
            """
            <Trackpoint><Time>$t</Time>
              <Position><LatitudeDegrees>50.1</LatitudeDegrees><LongitudeDegrees>8.6</LongitudeDegrees></Position>
            </Trackpoint>
            """
        }
        val xml = """
            <TrainingCenterDatabase><Activities><Activity Sport="Running">
              <Id>$start</Id><Lap><Track>$points</Track></Lap>
            </Activity></Activities></TrainingCenterDatabase>
        """.trimIndent()
        return TcxParser.parse(xml.byteInputStream(), zone) { _, i -> "tz-$i" }.single()
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
