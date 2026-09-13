package de.hecate.core

import de.hecate.core.cluster.RouteNamer
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.SessionSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What every exercise type has to provide, checked over all of them rather than
 * one at a time — a type added without a word for one of its three jobs, or with
 * a label a second type already uses, is a picker the user cannot read.
 */
class ExerciseTypeTest {

    @Test
    fun `every type has all three of its words`() {
        for (type in ExerciseType.entries) {
            assertTrue(type.label.isNotBlank(), "${type.name} has no label")
            assertTrue(type.sessionTitle.isNotBlank(), "${type.name} has no Health Connect title")
            assertTrue(type.routeWord.isNotBlank(), "${type.name} has no route word")
        }
    }

    @Test
    fun `no two types read the same in the picker`() {
        val labels = ExerciseType.entries.map { it.label }
        assertEquals(labels.size, labels.toSet().size, labels.toString())
    }

    @Test
    fun `the words are the ones the app is expected to say`() {
        assertEquals("Laufen", ExerciseType.RUNNING.label)
        assertEquals("Spaziergang", ExerciseType.WALKING.label)
        assertEquals("Wandern", ExerciseType.HIKING.label)
        assertEquals("Fahrradfahren", ExerciseType.BIKING.label)
        assertEquals("Skifahren", ExerciseType.SKIING.label)
    }

    @Test
    fun `an auto-generated route name uses the type's own word`() {
        val name = RouteNamer.autoName(3, ExerciseType.HIKING, localHours = listOf(9, 9, 10), lengthM = 8200.0)
        assertTrue(name.contains("Wandern"), name)
        assertTrue(name.contains("8,2 km"), name)
    }

    // ------------------------------------------------------------------ parse

    @Test
    fun `a stored name comes back as itself`() {
        for (type in ExerciseType.entries) assertEquals(type, ExerciseType.parse(type.name))
        for (source in SessionSource.entries) assertEquals(source, SessionSource.parse(source.name))
    }

    /**
     * The reason [ExerciseType.parse] is not `valueOf` with a fallback: a name
     * only an older build never heard of has to be an error, because calling it a
     * run files the workout under the wrong sport for good (Appendix A.18).
     */
    @Test
    fun `a name this build does not know is null, not a run`() {
        assertNull(ExerciseType.parse("PARAGLIDING"))
        assertNull(ExerciseType.parse("running"))
        assertNull(ExerciseType.parse(null))
        assertNull(SessionSource.parse("IMPORT_STRAVA"))
        // And the one that matters: nothing silently becomes the first constant.
        assertNotNull(ExerciseType.parse("RUNNING"))
    }
}
