package de.hecate.wear.recorder

import android.content.Context
import android.util.Log
import androidx.concurrent.futures.await
import androidx.health.services.client.HealthServices
import androidx.health.services.client.ExerciseClient
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType as HsExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.WarmUpConfig
import de.hecate.core.model.ExerciseType

/**
 * The thin layer over Health Services (§4: the only way to get fused GPS, heart
 * rate and distance on Wear OS).
 *
 * Nothing here interprets data — it starts, stops and forwards. Everything
 * interesting happens in [RecorderService], which keeps this class small enough
 * to reason about against an alpha-versioned API.
 *
 * Note the platform constraint of §1.3: Wear OS permits one exercise session
 * device-wide. Starting here terminates any Google Health session, and vice
 * versa. There is no way around it and no point pretending otherwise.
 */
class ExerciseSource(context: Context) {

    private val client: ExerciseClient = HealthServices.getClient(context).exerciseClient

    /** The metrics the app records. Kept minimal: every extra data type is battery. */
    private val dataTypes = setOf(
        DataType.LOCATION,
        DataType.HEART_RATE_BPM,
        DataType.DISTANCE_TOTAL,
        DataType.CALORIES_TOTAL,
    )

    private val warmUpTypes = setOf(DataType.LOCATION, DataType.HEART_RATE_BPM)

    fun setCallback(callback: Callback) {
        client.setUpdateCallback(object : ExerciseUpdateCallback {
            override fun onRegistered() = callback.onRegistered()

            override fun onRegistrationFailed(throwable: Throwable) =
                callback.onRegistrationFailed(throwable)

            override fun onExerciseUpdateReceived(update: ExerciseUpdate) =
                callback.onUpdate(update)

            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit

            override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) =
                callback.onAvailability(dataType, availability)
        })
    }

    suspend fun clearCallback() {
        runCatching { client.clearUpdateCallbackAsync(NO_OP).await() }
    }

    /** Warm the sensors up and wait for a fix before the session proper starts. */
    suspend fun prepare(type: ExerciseType) {
        client.prepareExerciseAsync(WarmUpConfig(map(type), warmUpTypes)).await()
    }

    suspend fun start(type: ExerciseType) {
        val config = ExerciseConfig.builder(map(type))
            .setDataTypes(dataTypes)
            .setIsAutoPauseAndResumeEnabled(true) // US-1: honour Health Services auto-pause
            .setIsGpsEnabled(true)
            .build()
        client.startExerciseAsync(config).await()
    }

    suspend fun pause() = runCatching { client.pauseExerciseAsync().await() }.onFailure { warn("pause", it) }
    suspend fun resume() = runCatching { client.resumeExerciseAsync().await() }.onFailure { warn("resume", it) }
    suspend fun end() = runCatching { client.endExerciseAsync().await() }.onFailure { warn("end", it) }

    // Reattaching after a process death needs no explicit query: registering the
    // update callback delivers the current ExerciseUpdate, state included, so the
    // recorder learns whether a session is already running by being told.

    private fun warn(what: String, t: Throwable) = Log.w(TAG, "$what fehlgeschlagen", t)

    interface Callback {
        fun onRegistered()
        fun onRegistrationFailed(throwable: Throwable)
        fun onUpdate(update: ExerciseUpdate)
        fun onAvailability(dataType: DataType<*, *>, availability: Availability)
    }

    private companion object {
        const val TAG = "ExerciseSource"

        val NO_OP = object : ExerciseUpdateCallback {
            override fun onRegistered() = Unit
            override fun onRegistrationFailed(throwable: Throwable) = Unit
            override fun onExerciseUpdateReceived(update: ExerciseUpdate) = Unit
            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
            override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) = Unit
        }

        fun map(type: ExerciseType): HsExerciseType = when (type) {
            ExerciseType.RUNNING -> HsExerciseType.RUNNING
            ExerciseType.WALKING -> HsExerciseType.WALKING
            ExerciseType.BIKING -> HsExerciseType.BIKING
        }
    }
}
