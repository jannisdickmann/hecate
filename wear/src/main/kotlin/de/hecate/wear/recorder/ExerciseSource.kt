package de.hecate.wear.recorder

import android.content.Context
import android.content.pm.PackageManager
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
import androidx.core.content.ContextCompat
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
class ExerciseSource(private val context: Context) {

    private val client: ExerciseClient = HealthServices.getClient(context).exerciseClient

    /** The metrics the app records. Kept minimal: every extra data type is battery. */
    private val wantedDataTypes = setOf(
        DataType.LOCATION,
        DataType.HEART_RATE_BPM,
        DataType.DISTANCE_TOTAL,
        DataType.CALORIES_TOTAL,
    )

    private val wantedWarmUpTypes = setOf(DataType.LOCATION, DataType.HEART_RATE_BPM)

    /**
     * The wanted types minus the ones this app is not allowed to collect.
     *
     * Health Services rejects a config *as a whole* when it names a data type the
     * caller has no permission for. That is what made a missing heart-rate grant
     * cost a whole run: the warm-up asked for location and heart rate together,
     * the heart rate was refused, and location — fully permitted — went down with
     * it. GNSS was never even switched on, and the recorder sat waiting for a fix
     * that could not arrive.
     *
     * So the config is built from what is actually granted. A workout with no
     * heart rate is worth having; a workout with nothing is not.
     */
    private fun <T : DataType<*, *>> permitted(types: Set<T>): Set<T> =
        types.filterTo(HashSet()) { type ->
            when (type) {
                DataType.LOCATION -> granted(android.Manifest.permission.ACCESS_FINE_LOCATION)
                DataType.HEART_RATE_BPM -> granted(READ_HEART_RATE) || granted(BODY_SENSORS)
                else -> true
            }
        }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** True when location can be collected at all; the recorder tells the user. */
    fun canRecordLocation(): Boolean = DataType.LOCATION in permitted(wantedWarmUpTypes)

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
        client.prepareExerciseAsync(WarmUpConfig(map(type), permitted(wantedWarmUpTypes))).await()
    }

    suspend fun start(type: ExerciseType) {
        val config = ExerciseConfig.builder(map(type))
            .setDataTypes(permitted(wantedDataTypes))
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

        /** See `MainActivity.READ_HEART_RATE`. */
        const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"
        const val BODY_SENSORS = "android.permission.BODY_SENSORS"

        val NO_OP = object : ExerciseUpdateCallback {
            override fun onRegistered() = Unit
            override fun onRegistrationFailed(throwable: Throwable) = Unit
            override fun onExerciseUpdateReceived(update: ExerciseUpdate) = Unit
            override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) = Unit
            override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) = Unit
        }

        /**
         * The app's type to the one Health Services starts a session with.
         *
         * Health Services knows finer distinctions than this — MOUNTAIN_BIKING,
         * ALPINE_SKIING, CROSS_COUNTRY_SKIING — and none of them is used, because
         * Health Connect has no constant to write them back as and the workout
         * would end up as plain BIKING or SKIING in Google Health anyway. Better
         * that the watch and the phone agree about what was recorded.
         */
        fun map(type: ExerciseType): HsExerciseType = when (type) {
            ExerciseType.RUNNING -> HsExerciseType.RUNNING
            ExerciseType.WALKING -> HsExerciseType.WALKING
            ExerciseType.HIKING -> HsExerciseType.HIKING
            ExerciseType.BIKING -> HsExerciseType.BIKING
            ExerciseType.SKIING -> HsExerciseType.SKIING
        }
    }
}
