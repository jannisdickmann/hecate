package de.hecate.core

import de.hecate.core.model.ExerciseType
import de.hecate.core.model.RouteBundle
import de.hecate.core.model.RouteEntry
import de.hecate.core.model.RouteGeometry
import de.hecate.core.model.RoutePoint
import de.hecate.core.model.RoutePrior
import de.hecate.core.model.RouteProfile
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The order the watch offers exercise types in: most-recorded first.
 *
 * The counting is here rather than on the watch because it is arithmetic over
 * the bundle, and arithmetic belongs where a laptop can check it.
 */
class BundleFrequencyTest {

    @Test
    fun `the most recorded type comes first`() {
        val bundle = bundleOf(
            ExerciseType.RUNNING to 2,
            ExerciseType.BIKING to 9,
            ExerciseType.WALKING to 5,
        )
        assertEquals(
            listOf(ExerciseType.BIKING, ExerciseType.WALKING, ExerciseType.RUNNING),
            bundle.typesByFrequency().take(3),
        )
    }

    @Test
    fun `a type's routes are counted together`() {
        // Four rides over two routes beat five runs over one.
        val bundle = bundleOf(
            ExerciseType.RUNNING to 5,
            ExerciseType.BIKING to 3,
            ExerciseType.BIKING to 3,
        )
        assertEquals(ExerciseType.BIKING, bundle.typesByFrequency().first())
    }

    @Test
    fun `every type is offered, including the ones never recorded`() {
        val bundle = bundleOf(ExerciseType.SKIING to 1)
        val order = bundle.typesByFrequency()
        assertEquals(ExerciseType.entries.size, order.size)
        assertEquals(ExerciseType.entries.toSet(), order.toSet())
        assertEquals(ExerciseType.SKIING, order.first())
    }

    @Test
    fun `an empty bundle leaves the list exactly as the enum declares it`() {
        // A first install, or a phone with too little history to cluster: the
        // picker has to look like it always did rather than like a shuffle.
        assertEquals(ExerciseType.entries.toList(), RouteBundle.EMPTY.typesByFrequency())
    }

    @Test
    fun `types with the same count keep the enum's order`() {
        val bundle = bundleOf(ExerciseType.SKIING to 4, ExerciseType.WALKING to 4)
        assertEquals(
            listOf(ExerciseType.WALKING, ExerciseType.SKIING),
            bundle.typesByFrequency().take(2),
        )
    }

    private fun bundleOf(vararg routes: Pair<ExerciseType, Int>) = RouteBundle(
        version = 1,
        builtAtUtcMs = 0,
        routes = routes.mapIndexed { i, (type, count) ->
            RouteEntry(
                geometry = RouteGeometry(
                    routeId = i.toLong(),
                    name = "Route $i",
                    exerciseType = type,
                    points = listOf(RoutePoint(50.1, 8.9, 0.0), RoutePoint(50.11, 8.9, 1000.0)),
                ),
                profile = RouteProfile(routeId = i.toLong(), binM = 10.0, medianCumTimeS = listOf(0.0, 600.0)),
                prior = RoutePrior(totalCount = count),
            )
        },
    )
}
