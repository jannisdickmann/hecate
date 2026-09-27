package de.hecate.core

import de.hecate.core.gpx.Gpx
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.Sample
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GpxTest {

    private fun sample(t: Long, lat: Double = 50.0, paused: Boolean = false, hr: Int? = null, alt: Double? = null) =
        Sample(tOffsetMs = t, activeMs = t, lat = lat, lon = 8.9, altM = alt, hrBpm = hr, paused = paused)

    private fun parse(xml: String) = DocumentBuilderFactory.newInstance()
        .apply { isNamespaceAware = true }
        .newDocumentBuilder()
        .parse(xml.byteInputStream())

    @Test
    fun `a pause ends a segment and its samples are left out`() {
        val segments = Gpx.segments(
            1_000_000L,
            listOf(sample(0), sample(1000), sample(2000, paused = true), sample(3000, paused = true), sample(4000)),
        )
        assertEquals(listOf(2, 1), segments.map { it.size })
        assertEquals(1_000_000L + 4000, segments[1][0].timeUtcMs)
    }

    @Test
    fun `samples without a position are skipped`() {
        val segments = Gpx.segments(0, listOf(sample(0), sample(1000, lat = Double.NaN), sample(2000)))
        assertEquals(listOf(2), segments.map { it.size })
    }

    @Test
    fun `nothing but pauses is no segment at all`() {
        assertTrue(Gpx.segments(0, listOf(sample(0, paused = true))).isEmpty())
    }

    @Test
    fun `a zero heart rate is not written`() {
        val p = Gpx.segments(0, listOf(sample(0, hr = 0), sample(1000, hr = 140))).single()
        assertNull(p[0].hrBpm)
        assertEquals(140, p[1].hrBpm)
    }

    @Test
    fun `the document parses and carries time, elevation and heart rate`() {
        val xml = Gpx.write(
            name = "Lauf <früh> & \"schnell\"",
            type = ExerciseType.RUNNING,
            timeUtcMs = 1_709_537_400_000L,
            segments = Gpx.segments(1_709_537_400_000L, listOf(sample(0, hr = 120, alt = 105.25), sample(1000))),
        )
        val doc = parse(xml)
        val ns = "http://www.topografix.com/GPX/1/1"
        assertEquals("1.1", doc.documentElement.getAttribute("version"))
        assertEquals("Lauf <früh> & \"schnell\"", doc.getElementsByTagNameNS(ns, "name").item(0).textContent)
        assertEquals("running", doc.getElementsByTagNameNS(ns, "type").item(0).textContent)

        val points = doc.getElementsByTagNameNS(ns, "trkpt")
        assertEquals(2, points.length)
        assertEquals("50.0000000", (points.item(0) as org.w3c.dom.Element).getAttribute("lat"))
        assertEquals("2024-03-04T07:30:00Z", doc.getElementsByTagNameNS(ns, "time").item(1).textContent)
        assertEquals("105.3", doc.getElementsByTagNameNS(ns, "ele").item(0).textContent)
        val hr = doc.getElementsByTagNameNS("http://www.garmin.com/xmlschemas/TrackPointExtension/v1", "hr")
        assertEquals(1, hr.length)
        assertEquals("120", hr.item(0).textContent)
    }

    @Test
    fun `a route has no times and takes its elevation from the profile bin`() {
        val segment = Gpx.routeSegment(
            points = listOf(50.0 to 8.9, 50.0001 to 8.9, 50.0002 to 8.9),
            cumDistM = listOf(0.0, Tuning.RESAMPLE_M * 1.5, Tuning.RESAMPLE_M * 9),
            medianAltM = listOf(100.0, 110.0),
        )
        assertEquals(listOf(100.0, 110.0, null), segment.map { it.eleM })
        val xml = Gpx.write("Runde", ExerciseType.BIKING, null, listOf(segment))
        assertFalse(xml.contains("<time>"))
        assertTrue(xml.contains("<type>cycling</type>"))
        assertEquals(3, parse(xml).getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "trkpt").length)
    }

    @Test
    fun `control characters are dropped rather than written`() {
        val xml = Gpx.write("a\u0001b", null, null, emptyList())
        assertEquals("ab", parse(xml).getElementsByTagNameNS("http://www.topografix.com/GPX/1/1", "name").item(0).textContent)
    }

    @Test
    fun `file names are safe and never empty`() {
        assertEquals("Lauf 2024-03-04 07-30.gpx", Gpx.fileName("Lauf 2024-03-04 07-30"))
        assertEquals("Runde_Wald_ Nord.gpx", Gpx.fileName("Runde/Wald: Nord"))
        assertEquals("hecate.gpx", Gpx.fileName("  ..  "))
    }
}
