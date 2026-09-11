package de.hecate.phone.health

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.Sample
import java.time.Instant
import java.time.ZoneOffset

/**
 * §5.4: writes a completed session to Health Connect so it still shows up in
 * Google Health (US-2).
 *
 * One-way, deliberately. §1.3: reading other apps' routes from Health Connect is
 * restricted to manual grants with no background reads, so this app owns its own
 * data and never depends on reading anything back.
 */
class HealthConnectWriter(private val context: Context) {

    val availability: Int get() = HealthConnectClient.getSdkStatus(context)

    val isAvailable: Boolean get() = availability == HealthConnectClient.SDK_AVAILABLE

    /** The recordings come off the watch, and saying so is what stops Google
     *  Health presenting them as hand-typed entries. */
    private val watch by lazy { Device(type = Device.TYPE_WATCH) }

    private val client: HealthConnectClient? by lazy {
        runCatching { HealthConnectClient.getOrCreate(context) }
            .onFailure { Log.w(TAG, "Health Connect nicht verfügbar", it) }
            .getOrNull()
    }

    suspend fun hasPermissions(): Boolean {
        val c = client ?: return false
        return runCatching {
            c.permissionController.getGrantedPermissions().containsAll(PERMISSIONS)
        }.getOrDefault(false)
    }

    sealed interface WriteResult {
        data object Written : WriteResult
        data class Failed(val reason: String, val retryable: Boolean) : WriteResult
        data class NotPossible(val reason: String) : WriteResult
    }

    /**
     * Write one session: the exercise session with its route, plus heart rate and
     * distance series.
     */
    suspend fun write(
        sessionId: String,
        startedAtUtcMs: Long,
        tzOffsetMinutes: Int,
        exerciseType: ExerciseType,
        activeDurationMs: Long,
        totalDistanceM: Double,
        samples: List<Sample>,
    ): WriteResult {
        val c = client ?: return WriteResult.NotPossible("Health Connect nicht installiert")
        if (!hasPermissions()) return WriteResult.NotPossible("Berechtigungen fehlen")
        if (samples.isEmpty()) return WriteResult.NotPossible("keine Messpunkte")

        val zone = ZoneOffset.ofTotalSeconds(tzOffsetMinutes * 60)
        val start = Instant.ofEpochMilli(startedAtUtcMs)
        val end = Instant.ofEpochMilli(startedAtUtcMs + (samples.last().tOffsetMs))

        return try {
            val routeLocations = samples
                .filter { !it.paused }
                .map {
                    ExerciseRoute.Location(
                        time = Instant.ofEpochMilli(startedAtUtcMs + it.tOffsetMs),
                        latitude = it.lat,
                        longitude = it.lon,
                        altitude = it.altM?.let { a -> Length.meters(a) },
                        horizontalAccuracy = it.accuracyM?.let { a -> Length.meters(a) },
                    )
                }

            val hrSamples = samples.mapNotNull { s ->
                s.hrBpm?.takeIf { it in 20..250 }?.let {
                    HeartRateRecord.Sample(
                        time = Instant.ofEpochMilli(startedAtUtcMs + s.tOffsetMs),
                        beatsPerMinute = it.toLong(),
                    )
                }
            }

            val records = buildList {
                add(
                    ExerciseSessionRecord(
                        startTime = start,
                        startZoneOffset = zone,
                        endTime = end,
                        endZoneOffset = zone,
                        exerciseType = hcType(exerciseType),
                        title = titleFor(exerciseType),
                        exerciseRoute = if (routeLocations.size >= 2) ExerciseRoute(routeLocations) else null,
                        metadata = Metadata.activelyRecorded(device = watch, clientRecordId = sessionId),
                    ),
                )
                if (hrSamples.isNotEmpty()) {
                    add(
                        HeartRateRecord(
                            startTime = start,
                            startZoneOffset = zone,
                            endTime = end,
                            endZoneOffset = zone,
                            samples = hrSamples,
                            metadata = Metadata.activelyRecorded(device = watch, clientRecordId = "$sessionId-hr"),
                        ),
                    )
                }
                if (totalDistanceM > 0) {
                    add(
                        DistanceRecord(
                            startTime = start,
                            startZoneOffset = zone,
                            endTime = end,
                            endZoneOffset = zone,
                            distance = Length.meters(totalDistanceM),
                            metadata = Metadata.activelyRecorded(device = watch, clientRecordId = "$sessionId-dist"),
                        ),
                    )
                }
            }

            // clientRecordId makes the write idempotent: a retry of a write that
            // actually succeeded updates rather than duplicates.
            c.insertRecords(records)
            Log.i(TAG, "Sitzung $sessionId in Health Connect geschrieben (aktiv ${activeDurationMs}ms)")
            WriteResult.Written
        } catch (e: Exception) {
            Log.w(TAG, "Health-Connect-Schreiben für $sessionId fehlgeschlagen", e)
            WriteResult.Failed(e.message ?: e::class.java.simpleName, retryable = true)
        }
    }

    private fun titleFor(type: ExerciseType) = when (type) {
        ExerciseType.RUNNING -> "Lauf"
        ExerciseType.WALKING -> "Spaziergang"
        ExerciseType.BIKING -> "Radfahrt"
    }

    private fun hcType(type: ExerciseType): Int = when (type) {
        ExerciseType.RUNNING -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        ExerciseType.WALKING -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        ExerciseType.BIKING -> ExerciseSessionRecord.EXERCISE_TYPE_BIKING
    }

    companion object {
        private const val TAG = "HealthConnectWriter"

        /** §6.4: these must be granted by hand in Health Connect after install. */
        val PERMISSIONS = setOf(
            HealthPermission.getWritePermission(ExerciseSessionRecord::class),
            HealthPermission.getWritePermission(HeartRateRecord::class),
            HealthPermission.getWritePermission(DistanceRecord::class),
            HealthPermission.PERMISSION_WRITE_EXERCISE_ROUTE,
        )
    }
}
