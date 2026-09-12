package de.hecate.phone

import android.content.Context
import androidx.room.Room
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.Sample
import de.hecate.core.model.SessionEndReason
import de.hecate.core.model.SessionEvent
import de.hecate.core.model.SessionFooter
import de.hecate.core.model.SessionHeader
import de.hecate.core.model.SessionSource
import de.hecate.phone.db.HecateDatabase
import de.hecate.phone.db.RouteEntity

/**
 * Shared setup for the Robolectric tests in this module.
 *
 * Two things every test here needs and neither of which is interesting: a Room
 * database that does not survive the test, and a WorkManager that runs nothing.
 * Both repositories enqueue clustering as their last act, and without an
 * initialised WorkManager that call throws — so the tests would be failing on
 * the one line that has nothing to do with what they assert.
 */
object Fixtures {

    /** In memory: every test gets an empty database and none of them collide. */
    fun database(context: Context): HecateDatabase =
        Room.inMemoryDatabaseBuilder(context, HecateDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    /**
     * A WorkManager that accepts an enqueue and never runs the worker.
     *
     * The workers themselves are not under test here: `ClusterWorker` is a thin
     * wrapper over `:core`'s clusterer, which has its own tests, and
     * `HealthConnectWorker` needs a Health Connect the JVM does not have.
     */
    fun workManager(context: Context) {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setTaskExecutor(SynchronousExecutor())
                .build(),
        )
    }

    /**
     * A session shaped like something the watch would send: a straight line
     * walked at roughly 2 m/s, one sample a second.
     *
     * The geometry only has to be consistent — `Track` has to be able to make
     * something of it, and two sessions built with different [lat0] have to be
     * different routes. The matcher's own behaviour is `:core`'s business.
     */
    fun recorded(
        id: String,
        startedAtUtcMs: Long = 1_784_109_600_000L,
        source: SessionSource = SessionSource.WATCH,
        tzOffsetMinutes: Int = 120,
        type: ExerciseType = ExerciseType.RUNNING,
        sampleCount: Int = 30,
        lat0: Double = 50.1,
        lon0: Double = 8.6,
        events: List<SessionEvent> = emptyList(),
    ): RecordedSession {
        val samples = (0 until sampleCount).map { i ->
            Sample(
                tOffsetMs = i * 1000L,
                activeMs = i * 1000L,
                lat = lat0 + i * 0.00002,
                lon = lon0,
                altM = 100.0 + i,
                hrBpm = 130 + (i % 5),
                accuracyM = 5.0,
            )
        }
        val activeMs = (sampleCount - 1).coerceAtLeast(0) * 1000L
        return RecordedSession(
            header = SessionHeader(
                sessionId = id,
                startedAtUtcMs = startedAtUtcMs,
                tzOffsetMinutes = tzOffsetMinutes,
                exerciseType = type,
                source = source,
            ),
            samples = samples,
            events = events,
            footer = SessionFooter(
                endedAtUtcMs = startedAtUtcMs + activeMs,
                activeDurationMs = activeMs,
                totalDistanceM = sampleCount * 2.0,
                reason = SessionEndReason.USER_STOPPED,
            ),
        )
    }

    /** A route row with a name and a priority — the two things only a human
     *  produces, and the reason the backup format exists at all. */
    fun route(
        id: Long,
        name: String,
        priority: Int = 5,
        medoidSessionId: String,
        nameIsCustom: Boolean = true,
    ) = RouteEntity(
        id = id,
        name = name,
        nameIsCustom = nameIsCustom,
        exerciseType = ExerciseType.RUNNING.name,
        lengthM = 5000.0,
        memberCount = 3,
        medoidSessionId = medoidSessionId,
        createdAt = 1_700_000_000_000L,
        active = true,
        priority = priority,
    )
}
