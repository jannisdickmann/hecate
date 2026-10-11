package de.hecate.phone.db

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.SessionSource
import de.hecate.phone.Fixtures
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which route a recording is filed under is the clusterer's answer, not the
 * watch's: a ride of 10 Oct met the commute's last hundred metres at the front
 * door, the watch committed to it there, and the ride stayed filed under the
 * commute because nothing ever took that back.
 */
@RunWith(RobolectricTestRunner::class)
class MatchOwnershipTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var db: HecateDatabase

    @Before
    fun setUp() {
        db = Fixtures.database(context)
    }

    @After
    fun tearDown() = db.close()

    private fun route(name: String, active: Boolean) = RouteEntity(
        name = name,
        exerciseType = ExerciseType.BIKING,
        lengthM = 17_000.0,
        memberCount = 1,
        medoidSessionId = "member",
        createdAt = 0L,
        active = active,
    )

    private fun session(id: String, routeId: Long) = SessionEntity(
        id = id,
        startedAtUtc = 1_784_109_600_000L + id.hashCode(),
        tzOffsetMinutes = 120,
        exerciseType = ExerciseType.BIKING,
        activeDurationMs = 3_000_000L,
        totalDistanceM = 17_000.0,
        source = SessionSource.WATCH,
        matchedRouteId = routeId,
        matchCoverage = 0.97,
    )

    @Test
    fun `only members of an active route keep a route`(): Unit = runBlocking {
        val commute = db.routes().insert(route("Arbeit zurück Baiersdorf", active = true))
        val retired = db.routes().insert(route("Arbeit zurück", active = false))
        db.sessions().insert(session("member", commute))
        db.sessions().insert(session("retired-member", retired))
        db.sessions().insert(session("watch-said-so", commute))
        db.routes().insertMembers(
            listOf(RouteMemberEntity(commute, "member"), RouteMemberEntity(retired, "retired-member")),
        )

        db.sessions().clearMatchesOfNonMembers()

        assertEquals(commute, db.sessions().byId("member")?.matchedRouteId)
        assertNull(db.sessions().byId("watch-said-so")?.matchedRouteId, "the watch's commit outlived the clusterer")
        assertNull(db.sessions().byId("retired-member")?.matchedRouteId, "a retired route's old member list counted")
    }

    @Test
    fun `the clusterer keeps the promotions it was given back and clears the rest`(): Unit = runBlocking {
        val walk = db.routes().insert(route("Abendrunde", active = true))
        for (id in listOf("seed", "overtaken", "plain")) db.sessions().insert(session(id, walk))
        db.sessions().setTemporaryRoute("seed", true)
        db.sessions().setTemporaryRoute("overtaken", true)
        assertEquals(setOf("seed", "overtaken"), db.sessions().temporaryRouteSeeds().toSet())

        db.sessions().keepTemporaryRouteSeeds(listOf("seed"))
        assertEquals(listOf("seed"), db.sessions().temporaryRouteSeeds())

        // Nothing left standing clears every promotion.
        db.sessions().keepTemporaryRouteSeeds(emptyList())
        assertTrue(db.sessions().temporaryRouteSeeds().isEmpty())
    }
}
