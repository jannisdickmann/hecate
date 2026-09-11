package de.hecate.wear.recorder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.LocationAvailability
import androidx.health.services.client.data.LocationData
import androidx.health.services.client.data.SampleDataPoint
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.MatchPhase
import de.hecate.core.model.MatchState
import de.hecate.core.model.Sample
import de.hecate.core.model.SessionEndReason
import de.hecate.core.model.SessionEvent
import de.hecate.core.model.SessionEventType
import de.hecate.core.model.SessionFooter
import de.hecate.core.model.SessionHeader
import de.hecate.wear.AppGraph
import de.hecate.wear.notify.Haptics
import de.hecate.wear.notify.OngoingNotification
import de.hecate.wear.ui.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.TimeZone
import java.util.UUID

/**
 * The recorder (§5.3).
 *
 * A foreground service of type `health|location`, which is mandatory: without it
 * the exercise session dies with `AUTO_ENDED_PERMISSION_LOST` the moment the
 * screen goes off.
 *
 * The ordering inside [onSample] is deliberate and is the whole of NFR-1: the
 * sample is written to the append-only file *before* anything else touches it.
 * Matching, metrics and display all happen downstream of a durable write, so
 * there is no failure in any of them that can cost a sample.
 */
class RecorderService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var source: ExerciseSource

    private var writer: SessionWriter? = null
    private var matcher: MatcherHost? = null

    private var sessionId: String? = null
    private var startedElapsedNs: Long = 0
    private var lastState: MatchState? = null
    private var routeNames: Map<Long, String> = emptyMap()
    private var lastNotificationUpdateMs = 0L
    private var sawFirstLocation = false

    private val bus get() = AppGraph.recorder

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        source = ExerciseSource(this)
        source.setCallback(callback)
        // Any file left behind by a process that died mid-workout goes out now
        // (§5.3, "Uploader ... on service start (to catch reboot leftovers)").
        scope.launch { AppGraph.uploader.uploadPending() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val type = runCatching {
                    ExerciseType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: ExerciseType.RUNNING.name)
                }.getOrDefault(ExerciseType.RUNNING)
                startForegroundNow(type)
                scope.launch { beginSession(type) }
            }
            ACTION_PAUSE -> scope.launch { source.pause() }
            ACTION_RESUME -> scope.launch { source.resume() }
            ACTION_STOP -> scope.launch { endSession(SessionEndReason.USER_STOPPED) }
            ACTION_UPLOAD -> scope.launch { AppGraph.uploader.uploadPending() }
            else -> {
                // Restarted by the system (START_STICKY). Re-register and see what
                // Health Services says the current state is; if a session is still
                // running, the callback reattaches to it.
                startForegroundNow(bus.state.value.exerciseType)
                recordEvent(SessionEventType.SERVICE_RESTARTED)
            }
        }
        return START_STICKY
    }

    private fun startForegroundNow(type: ExerciseType) {
        val n = OngoingNotification.build(this, getString(de.hecate.wear.R.string.app_name), "…")
        startForeground(
            OngoingNotification.NOTIFICATION_ID,
            n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        bus.update { it.copy(exerciseType = type) }
    }

    // ------------------------------------------------------------------ session

    private suspend fun beginSession(type: ExerciseType) {
        if (bus.state.value.isRecording) return

        val id = UUID.randomUUID().toString()
        sessionId = id
        // NFR-6: the monotonic clock times the session; the wall clock is sampled
        // exactly once, here, for display and for Health Connect.
        startedElapsedNs = SystemClock.elapsedRealtimeNanos()
        val startedAtUtcMs = System.currentTimeMillis()
        val tzOffsetMinutes = TimeZone.getDefault().getOffset(startedAtUtcMs) / 60_000

        val bundle = AppGraph.bundles.load()
        routeNames = bundle.routes.associate { it.geometry.routeId to it.geometry.name }

        val w = SessionWriter(AppGraph.sessions.fileFor(id))
        w.open()
        w.writeHeader(
            SessionHeader(
                sessionId = id,
                startedAtUtcMs = startedAtUtcMs,
                tzOffsetMinutes = tzOffsetMinutes,
                exerciseType = type,
                bundleVersion = bundle.version.takeIf { it > 0 },
                appVersion = BuildInfo.VERSION,
            ),
        )
        writer = w

        matcher = MatcherHost(bundle, type, startedAtUtcMs, tzOffsetMinutes) { entry ->
            writer?.writeMatch(entry)
        }
        if (matcher?.disabled == true) recordEvent(SessionEventType.MATCHER_DISABLED)

        bus.update {
            RecorderUiState(
                phase = RecorderPhase.PREPARING,
                exerciseType = type,
                sessionId = id,
                bundleVersion = bundle.version,
                pendingUploads = it.pendingUploads,
            )
        }

        // PREPARING: warm the sensors and wait for a fix before the session starts,
        // so the first few hundred metres — the ones the matcher needs most — are
        // not spent acquiring GNSS.
        runCatching { source.prepare(type) }
            .onFailure { Log.w(TAG, "prepareExercise fehlgeschlagen", it) }
        Haptics.tick(this)
    }

    /** Called once a fix exists (or the wait was given up on). */
    private suspend fun startExercise() {
        val type = bus.state.value.exerciseType
        runCatching { source.start(type) }
            .onSuccess { bus.update { it.copy(phase = RecorderPhase.ACTIVE) } }
            .onFailure {
                Log.e(TAG, "startExercise fehlgeschlagen", it)
                bus.update { s -> s.copy(message = "Start fehlgeschlagen") }
                endSession(SessionEndReason.SERVICE_DIED)
            }
    }

    private suspend fun endSession(reason: SessionEndReason) {
        val id = sessionId ?: return
        bus.update { it.copy(phase = RecorderPhase.ENDING) }
        runCatching { source.end() }

        val st = bus.state.value
        writer?.writeFooter(
            SessionFooter(
                endedAtUtcMs = System.currentTimeMillis(),
                activeDurationMs = st.activeDurationMs,
                totalDistanceM = st.distanceM,
                reason = reason,
            ),
        )
        writer?.close()
        writer = null
        matcher = null
        sessionId = null
        lastState = null
        sawFirstLocation = false

        Haptics.tick(this)
        AppGraph.uploader.uploadPending()
        bus.reset()
        bus.update { it.copy(pendingUploads = AppGraph.sessions.unacked().size) }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, "Sitzung $id beendet ($reason)")
    }

    private fun recordEvent(type: SessionEventType, note: String? = null) {
        val w = writer ?: return
        w.writeEvent(SessionEvent(tOffsetMs = nowOffsetMs(), type = type, note = note))
    }

    private fun nowOffsetMs(): Long =
        (SystemClock.elapsedRealtimeNanos() - startedElapsedNs) / 1_000_000

    // ----------------------------------------------------------------- callback

    private val callback = object : ExerciseSource.Callback {
        override fun onRegistered() = Unit

        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "Health Services Registrierung fehlgeschlagen", throwable)
            bus.update { it.copy(message = "Health Services nicht verfügbar") }
        }

        override fun onUpdate(update: ExerciseUpdate) {
            scope.launch { handleUpdate(update) }
        }

        override fun onAvailability(dataType: DataType<*, *>, availability: Availability) {
            if (availability is LocationAvailability) {
                val ready = availability == LocationAvailability.ACQUIRED_TETHERED ||
                    availability == LocationAvailability.ACQUIRED_UNTETHERED
                val was = bus.state.value.gpsReady
                bus.update { it.copy(gpsReady = ready, locationAvailable = ready) }
                if (!was && ready) {
                    recordEvent(SessionEventType.GPS_ACQUIRED)
                    if (bus.state.value.phase == RecorderPhase.PREPARING) {
                        scope.launch { startExercise() }
                    }
                } else if (was && !ready) {
                    recordEvent(SessionEventType.GPS_LOST)
                }
            } else if (dataType == DataType.HEART_RATE_BPM) {
                bus.update { it.copy(heartRateAvailable = availability.toString().startsWith("AVAILABLE")) }
            }
        }
    }

    private suspend fun handleUpdate(update: ExerciseUpdate) {
        // Health Services has a dozen exercise states; the recorder cares about
        // four situations. The category predicates are used rather than an
        // exhaustive list of constants, so an alpha-version addition to the enum
        // lands in the right bucket instead of in the "unexpected" branch.
        val exerciseState = update.exerciseStateInfo.state
        when {
            exerciseState.isEnded || exerciseState.isEnding -> {
                if (sessionId != null) {
                    val reason = if (bus.state.value.phase == RecorderPhase.ENDING) {
                        SessionEndReason.USER_STOPPED
                    } else {
                        SessionEndReason.AUTO_ENDED
                    }
                    endSession(reason)
                }
                return
            }
            exerciseState.isPaused -> {
                // US-1: auto-pause events from Health Services are honoured and
                // recorded, not fought.
                val auto = exerciseState == ExerciseState.AUTO_PAUSED ||
                    exerciseState == ExerciseState.AUTO_PAUSING
                if (bus.state.value.phase != RecorderPhase.PAUSED) {
                    recordEvent(if (auto) SessionEventType.PAUSE_AUTO else SessionEventType.PAUSE_MANUAL)
                }
                bus.update { it.copy(phase = RecorderPhase.PAUSED, autoPaused = auto) }
            }
            exerciseState == ExerciseState.ACTIVE -> {
                if (bus.state.value.phase == RecorderPhase.PAUSED) {
                    recordEvent(
                        if (bus.state.value.autoPaused) SessionEventType.RESUME_AUTO
                        else SessionEventType.RESUME_MANUAL,
                    )
                }
                bus.update { it.copy(phase = RecorderPhase.ACTIVE, autoPaused = false) }
            }
            exerciseState == ExerciseState.PREPARING || exerciseState == ExerciseState.USER_STARTING ->
                bus.update { it.copy(phase = RecorderPhase.PREPARING) }
            else -> {
                // §5.3: any unexpected state preserves the file rather than
                // guessing what it means.
                Log.w(TAG, "Unerwarteter Zustand: $exerciseState")
                writer?.sync()
            }
        }

        val activeMs = update.activeDurationCheckpoint
            ?.let { cp ->
                cp.activeDuration.toMillis() +
                    (System.currentTimeMillis() - cp.time.toEpochMilli()).coerceAtLeast(0)
            }
            ?: bus.state.value.activeDurationMs

        ingest(update.latestMetrics, activeMs)
    }

    /**
     * Turn one batch of Health Services metrics into samples.
     *
     * Batches arrive per second with the screen on and in bursts while ambient
     * (§1.3). Samples are written and matched in timestamp order either way, so
     * the result is identical — which is exactly what the replay harness checks.
     */
    private fun ingest(metrics: DataPointContainer, activeMs: Long) {
        val locations = runCatching { metrics.getData(DataType.LOCATION) }.getOrDefault(emptyList())
        val hrPoints = runCatching { metrics.getData(DataType.HEART_RATE_BPM) }.getOrDefault(emptyList())
        val distance = runCatching { metrics.getData(DataType.DISTANCE_TOTAL)?.total }.getOrNull()

        val hr = hrPoints.lastOrNull()?.value?.toInt()?.takeIf { it in 20..250 }
        if (hr != null || distance != null) {
            bus.update {
                it.copy(
                    heartRateBpm = hr ?: it.heartRateBpm,
                    distanceM = distance ?: it.distanceM,
                    activeDurationMs = activeMs,
                )
            }
        }

        if (locations.isEmpty()) {
            maybeUpdateNotification()
            return
        }

        val paused = bus.state.value.phase == RecorderPhase.PAUSED
        for (point in locations.sortedBy { it.timeDurationFromBoot.toMillis() }) {
            val sample = toSample(point, activeMs, hr, distance, paused) ?: continue
            onSample(sample)
        }
        maybeUpdateNotification()
    }

    private fun toSample(
        point: SampleDataPoint<LocationData>,
        activeMs: Long,
        hr: Int?,
        distance: Double?,
        paused: Boolean,
    ): Sample? {
        val d = point.value
        if (!d.latitude.isFinite() || !d.longitude.isFinite()) return null
        val bootMs = point.timeDurationFromBoot.toMillis()
        val tOffsetMs = bootMs - startedElapsedNs / 1_000_000
        if (tOffsetMs < 0) return null
        sawFirstLocation = true
        return Sample(
            tOffsetMs = tOffsetMs,
            activeMs = activeMs,
            lat = d.latitude,
            lon = d.longitude,
            altM = d.altitude?.takeIf { it.isFinite() },
            hrBpm = hr,
            accuracyM = d.horizontalPositionErrorMeters?.takeIf { it.isFinite() },
            cumDistanceM = distance,
            paused = paused,
        )
    }

    /**
     * The critical path, in order: durable write first, everything else after.
     */
    private fun onSample(sample: Sample) {
        writer?.writeSample(sample)

        val state = matcher?.update(sample)
        if (matcher?.disabled == true && !bus.state.value.matcherDisabled) {
            recordEvent(SessionEventType.MATCHER_DISABLED)
            bus.update { it.copy(matcherDisabled = true) }
        }
        if (state != null) applyMatch(state)
    }

    private fun applyMatch(state: MatchState) {
        lastState = state
        val name = state.committedRouteId?.let { routeNames[it] }
        bus.update {
            it.copy(
                matchPhase = state.phase,
                routeName = if (state.phase == MatchPhase.COMMITTED) name else null,
                remainingM = state.remainingM,
                etaSecondsFromNow = state.etaSecondsFromNow,
                deltaVsMedianS = state.deltaVsMedianS,
                offRoute = state.offRoute,
            )
        }
        // US-7: haptic plus notification text, no screen takeover.
        if (state.deviationAlert) {
            Haptics.deviation(this)
            OngoingNotification.update(this, "Abweichung", "Du bist von der Route ab")
            lastNotificationUpdateMs = 0
        }
    }

    private fun maybeUpdateNotification() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastNotificationUpdateMs < NOTIFICATION_INTERVAL_MS) return
        lastNotificationUpdateMs = now
        val s = bus.state.value
        val title = when {
            s.offRoute -> "Abweichung"
            s.matchPhase == MatchPhase.COMMITTED && s.routeName != null -> s.routeName
            s.matchPhase == MatchPhase.UNKNOWN -> "Unbekannt"
            else -> "Suche…"
        }
        OngoingNotification.update(
            this,
            title,
            "${Format.distance(s.distanceM)} · ${Format.duration(s.activeDurationMs)}",
        )
    }

    override fun onDestroy() {
        // A destroyed service must never take an in-progress recording with it:
        // the file is synced and closed, and whatever was written survives.
        writer?.sync()
        writer?.close()
        scope.launch { source.clearCallback() }
        scope.cancel()
        super.onDestroy()
    }

    /** Filled in by the build; kept out of BuildConfig so :core stays Android-free. */
    object BuildInfo {
        const val VERSION = "1.0"
    }

    companion object {
        private const val TAG = "RecorderService"
        private const val NOTIFICATION_INTERVAL_MS = 5_000L

        const val ACTION_START = "de.hecate.wear.START"
        const val ACTION_PAUSE = "de.hecate.wear.PAUSE"
        const val ACTION_RESUME = "de.hecate.wear.RESUME"
        const val ACTION_STOP = "de.hecate.wear.STOP"
        const val ACTION_UPLOAD = "de.hecate.wear.UPLOAD"
        const val EXTRA_TYPE = "type"

        fun start(context: Context, type: ExerciseType) = context.startForegroundService(
            Intent(context, RecorderService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TYPE, type.name),
        )

        fun command(context: Context, action: String) = context.startForegroundService(
            Intent(context, RecorderService::class.java).setAction(action),
        )
    }
}
