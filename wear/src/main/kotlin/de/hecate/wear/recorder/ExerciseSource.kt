package de.hecate.wear.recorder

import android.Manifest
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
import androidx.health.services.client.data.ExerciseTypeCapabilities
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.WarmUpConfig
import androidx.core.content.ContextCompat
import de.hecate.core.model.ExerciseType
import de.hecate.wear.ui.READ_HEART_RATE

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
        // Current speed, which the live screen claimed to show for a year
        // without ever asking for it — the cell fell through to
        // distance-over-time and called an hour's average "right now". Dropped
        // by [supported] on an exercise type that does not produce it, and the
        // display falls back to the average *and says so*.
        DataType.SPEED,
        // Altitude, and the only place it comes from: Health Services does not
        // put one on the position. `LocationData.altitude` on a real Pixel Watch 4
        // is Double.MAX_VALUE on every fix — the "no altitude here" sentinel, not
        // a reading — which is why every wrist recording came back with a track
        // and no height profile while a takeout import of the same ground carried
        // an altitude on every sample (#6). The app had simply never asked for the
        // data type that has one.
        //
        // The watch has a barometer, and its Health Services offers this under
        // ABSOLUTE_ELEVATION for all five exercise types this app records. It is
        // the absolute reading rather than ELEVATION_GAIN_TOTAL deliberately: a
        // gain total would fill the Aufstieg row and draw no chart, and SKIING —
        // the one type that would most like a profile — does not offer the gain
        // at all. Battery, as for every type in this set; a barometer is among
        // the cheapest sensors on the watch.
        DataType.ABSOLUTE_ELEVATION,
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
                // Speed is a location product and carries location's permission
                // with it; naming it without the grant would have Health
                // Services reject the config as a whole, which is exactly the
                // failure this filter exists for.
                DataType.LOCATION, DataType.SPEED ->
                    granted(Manifest.permission.ACCESS_FINE_LOCATION)
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

    /**
     * What Health Services says this exercise type can do, or null when it will
     * not say. Read once per session start; it is a local IPC, not a sensor.
     */
    private suspend fun capabilitiesFor(type: HsExerciseType): ExerciseTypeCapabilities? =
        runCatching { client.getCapabilitiesAsync().await().getExerciseTypeCapabilities(type) }
            .onFailure { Log.w(TAG, "Fähigkeiten für $type nicht lesbar", it) }
            .getOrNull()

    /**
     * The wanted types minus the ones this *exercise type* does not produce.
     *
     * The permission filter above is only half of it. A configuration is also
     * rejected as a whole for naming a data type the exercise does not support,
     * and the two lists are not the same list.
     */
    private fun <T : DataType<*, *>> supported(types: Set<T>, caps: ExerciseTypeCapabilities?): Set<T> {
        val available = caps?.supportedDataTypes ?: return types
        return types.filterTo(HashSet()) { it in available }
    }

    /** Warm the sensors up and wait for a fix before the session proper starts. */
    suspend fun prepare(type: ExerciseType) {
        val hs = map(type)
        val caps = capabilitiesFor(hs)
        client.prepareExerciseAsync(WarmUpConfig(hs, supported(permitted(wantedWarmUpTypes), caps))).await()
    }

    suspend fun start(type: ExerciseType) {
        val hs = map(type)
        val caps = capabilitiesFor(hs)
        // US-1 asks for auto-pause to be honoured, and it still is wherever
        // Health Services offers it — but asking for it where it is not offered
        // does not cost auto-pause, it costs the whole recording. Skiing is such a
        // type: `startExercise` fails outright with "Auto pause is not supported
        // for the exercise type SKIING", the session is discarded, and the user
        // is standing at the top of a piste with nothing recording.
        //
        // Same lesson as A.17 and the permission filter above: build the config
        // from what is actually on offer, never from what ought to be. Unknown
        // capabilities mean off, because a session without auto-pause is worth
        // having and a session that will not start is not.
        val autoPause = caps?.supportsAutoPauseAndResume ?: false
        val asked = permitted(wantedDataTypes)
        val config = ExerciseConfig.builder(hs)
            .setDataTypes(supported(asked, caps))
            .setIsAutoPauseAndResumeEnabled(autoPause)
            .setIsGpsEnabled(true)
            .build()
        // A.17's lesson again: say what was dropped and why, on the device, rather
        // than leaving a silently thinner recording to be discovered afterwards.
        val dropped = asked - config.dataTypes
        if (dropped.isNotEmpty()) Log.w(TAG, "$hs kann nicht: ${dropped.joinToString { it.name }}")
        if (!autoPause) Log.i(TAG, "$hs ohne Auto-Pause: von Health Services nicht unterstützt")
        Log.i(TAG, "$hs startet mit ${config.dataTypes.joinToString { it.name }}")
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
