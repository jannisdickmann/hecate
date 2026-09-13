package de.hecate.core

import de.hecate.core.model.ActivityNames
import de.hecate.core.model.ExerciseType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The import tables, and specifically the order they are tried in.
 *
 * Every case below is a name where a shorter keyword would have claimed the
 * workout first and got it wrong. They are the reason [ActivityNames] is an
 * ordered list rather than a `when`, and they are what a reordering breaks.
 */
class ActivityNamesTest {

    @Test
    fun `every recorded type is reachable from a plausible name`() {
        assertEquals(ExerciseType.RUNNING, ActivityNames.typeOf("Outdoor Run"))
        assertEquals(ExerciseType.WALKING, ActivityNames.typeOf("Spaziergang"))
        assertEquals(ExerciseType.HIKING, ActivityNames.typeOf("Wandern"))
        assertEquals(ExerciseType.BIKING, ActivityNames.typeOf("Outdoor Bike"))
        assertEquals(ExerciseType.SKIING, ActivityNames.typeOf("Skifahren"))
    }

    @Test
    fun `german and english names reach the same type`() {
        for (name in listOf("Radfahren", "Rennrad", "Mountainbiking", "MTB", "Cycling", "Ride", "Gravel")) {
            assertEquals(ExerciseType.BIKING, ActivityNames.typeOf(name), name)
        }
        for (name in listOf("Wanderung", "Bergwandern", "Hiking", "Trekking", "Schneeschuhwandern")) {
            assertEquals(ExerciseType.HIKING, ActivityNames.typeOf(name), name)
        }
        for (name in listOf("Nordic Walking", "Gehen", "Spazierengehen", "Stroll")) {
            assertEquals(ExerciseType.WALKING, ActivityNames.typeOf(name), name)
        }
        for (name in listOf("Trailrunning", "Joggen", "Dauerlauf", "Marathon")) {
            assertEquals(ExerciseType.RUNNING, ActivityNames.typeOf(name), name)
        }
    }

    @Test
    fun `a longer word wins over the shorter one inside it`() {
        // "Langlauf" ends in "lauf" and is not a run.
        assertEquals(ExerciseType.SKIING, ActivityNames.typeOf("Langlauf"))
        assertEquals(ExerciseType.SKIING, ActivityNames.typeOf("Skilanglauf"))
        // "Skitourengehen" contains "geh" and is not a walk.
        assertEquals(ExerciseType.SKIING, ActivityNames.typeOf("Skitourengehen"))
        // "Mountainbiking" contains "mountain" and is not a hike — which is why
        // there is no bare "mountain" keyword for hiking.
        assertEquals(ExerciseType.BIKING, ActivityNames.typeOf("Mountainbiking"))
    }

    @Test
    fun `indoor variants are refused rather than filed without a route`() {
        for (name in listOf("Laufband", "Treadmill Running", "Indoor Cycling", "Heimtrainer", "Ergometer")) {
            assertNull(ActivityNames.typeOf(name), name)
        }
    }

    @Test
    fun `sports that merely sound like one of ours are refused`() {
        // Each of these contains a keyword: "lauf", "ski", "rolle".
        for (name in listOf("Eislaufen", "Wasserski", "Rollschuhlaufen", "Inline-Skating")) {
            assertNull(ActivityNames.typeOf(name), name)
        }
        // And the plain unknowns.
        for (name in listOf("Tennis", "Rudern", "Schwimmen", "Yoga", "")) {
            assertNull(ActivityNames.typeOf(name), name)
        }
        assertNull(ActivityNames.typeOf(null))
    }
}
