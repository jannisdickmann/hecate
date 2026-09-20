package de.hecate.core

import de.hecate.core.geo.LatLon
import de.hecate.core.map.LabelCandidate
import de.hecate.core.map.LabelPlacer
import de.hecate.core.map.MapPlace
import de.hecate.core.map.PlaceClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Which names fit, decided without a canvas.
 *
 * Every case here is a picture bug that has a name: a village label suppressed
 * by a city thirty kilometres off screen, a name half over the edge, a map that
 * reshuffles itself on a recomposition.
 */
class LabelPlacerTest {

    private fun place(name: String, cls: PlaceClass, population: Int = 0) =
        MapPlace(cls, LatLon(49.6, 11.0), name, population)

    /** Eight pixels a character, twelve tall — close enough to a real sans. */
    private val measure: (MapPlace) -> DoubleArray = { doubleArrayOf(it.name.length * 8.0, 12.0) }

    private val canvas = LabelPlacer.visibleBox(400.0, 300.0)

    @Test
    fun `the higher rank survives a collision`() {
        val placed = LabelPlacer.place(
            candidates = listOf(
                LabelCandidate(place("Kleinseebach", PlaceClass.HAMLET), 200.0, 150.0),
                LabelCandidate(place("Erlangen", PlaceClass.CITY, 115_000), 205.0, 152.0),
            ),
            measure = measure, visible = canvas, maxLabels = 10, marginPx = 4.0,
        )
        assertEquals(listOf("Erlangen"), placed.map { it.place.name })
    }

    @Test
    fun `equal ranks are separated by population, then by name`() {
        val placed = LabelPlacer.place(
            candidates = listOf(
                LabelCandidate(place("Adorf", PlaceClass.VILLAGE, 100), 200.0, 150.0),
                LabelCandidate(place("Bedorf", PlaceClass.VILLAGE, 900), 202.0, 151.0),
            ),
            measure = measure, visible = canvas, maxLabels = 10, marginPx = 4.0,
        )
        assertEquals(listOf("Bedorf"), placed.map { it.place.name })
    }

    /**
     * Culling before collision. With the order the other way round, the
     * off-screen city wins the contest for pixels nobody can see and takes the
     * visible village down with it.
     */
    @Test
    fun `a label off the canvas never suppresses a visible one`() {
        val placed = LabelPlacer.place(
            candidates = listOf(
                LabelCandidate(place("Nürnberg", PlaceClass.CITY, 520_000), 2_000.0, 150.0),
                LabelCandidate(place("Möhrendorf", PlaceClass.VILLAGE, 4_200), 200.0, 150.0),
            ),
            measure = measure, visible = canvas, maxLabels = 10, marginPx = 4.0,
        )
        assertEquals(listOf("Möhrendorf"), placed.map { it.place.name })
    }

    @Test
    fun `a label straddling the edge is dropped rather than clipped`() {
        val placed = LabelPlacer.place(
            candidates = listOf(LabelCandidate(place("Baiersdorf", PlaceClass.TOWN), 396.0, 150.0)),
            measure = measure, visible = canvas, maxLabels = 10, marginPx = 4.0,
        )
        assertTrue(placed.isEmpty(), "a label hanging off the right edge was kept")
    }

    @Test
    fun `maxLabels is respected`() {
        val candidates = List(30) { i ->
            LabelCandidate(place("Ort$i", PlaceClass.VILLAGE, 1000 - i), 20.0 + (i % 6) * 60.0, 20.0 + (i / 6) * 50.0)
        }
        val placed = LabelPlacer.place(candidates, measure, canvas, maxLabels = 5, marginPx = 4.0)
        assertEquals(5, placed.size)
    }

    /** A map that reshuffles itself on a recomposition is a map that flickers
     *  while nothing about it has changed. */
    @Test
    fun `identical input gives identical output`() {
        val candidates = List(20) { i ->
            LabelCandidate(place("Ort$i", PlaceClass.entries[i % PlaceClass.entries.size], i * 7), 30.0 + (i % 5) * 70.0, 30.0 + (i / 5) * 60.0)
        }
        val first = LabelPlacer.place(candidates, measure, canvas, 8, 4.0).map { it.place.name }
        val again = LabelPlacer.place(candidates.shuffled(kotlin.random.Random(7)), measure, canvas, 8, 4.0)
            .map { it.place.name }
        assertEquals(first, again)
    }

    @Test
    fun `nothing that was placed overlaps anything else`() {
        val candidates = List(40) { i ->
            LabelCandidate(place("Ort$i", PlaceClass.VILLAGE, i), 10.0 + (i % 8) * 45.0, 10.0 + (i / 8) * 40.0)
        }
        val placed = LabelPlacer.place(candidates, measure, canvas, 30, 4.0)
        for (i in placed.indices) {
            for (j in i + 1 until placed.size) {
                assertTrue(
                    !placed[i].box.overlaps(placed[j].box),
                    "${placed[i].place.name} overlaps ${placed[j].place.name}",
                )
            }
        }
    }

    @Test
    fun `a thumbnail gets a few names and a full screen gets a couple of dozen`() {
        val thumbnail = LabelPlacer.maxLabelsFor(360.0 * 220.0)
        val fullScreen = LabelPlacer.maxLabelsFor(400.0 * 800.0)
        assertTrue(thumbnail in 3..10, "a route drawing offered $thumbnail labels")
        assertTrue(fullScreen in 20..30, "a full screen offered $fullScreen labels")
        assertTrue(fullScreen > thumbnail)
    }
}
