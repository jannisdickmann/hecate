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
import androidx.health.services.client.data.LocationAccuracy
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    /** Non-null only while a stored session is being replayed (§6.5, debug builds
     *  only). Everything that would otherwise touch the disk or the phone checks
     *  this first: a replay is a rehearsal, not a recording. */
    @Volatile
    private var feed: SampleFeed? = null

    /** The coroutine pouring that session in, so that stopping can stop it.
     *  Cleared before a replay that ran to its end calls [endSession] itself,
     *  which must not then cancel the coroutine it is running on. */
    @Volatile
    private var replayJob: Job? = null

    private var sessionId: String? = null
    private var startedElapsedNs: Long = 0
    private var lastState: MatchState? = null
    private var routeNames: Map<Long, String> = emptyMap()
    private var lastNotificationUpdateMs = 0L
    private var sawFirstLocation = false
    private var samplesWritten = 0

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
                // The extra is a String because an Intent cannot carry the enum
                // itself. It is written by the picker in this same process, so an
                // unreadable one is a bug rather than a downgrade — but starting
                // *something* still beats refusing to record (NFR-1), so it falls
                // back to running and says so.
                val raw = intent.getStringExtra(EXTRA_TYPE)
                val type = ExerciseType.parse(raw) ?: ExerciseType.RUNNING.also {
                    Log.w(TAG, "Sportart im Intent unlesbar ($raw), starte als ${it.label}")
                }
                startForegroundNow(type)
                scope.launch { beginSession(type) }
            }
            ACTION_PAUSE -> scope.launch { setPaused(true) }
            ACTION_RESUME -> scope.launch { setPaused(false) }
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

        // A replay stands in for Health Services (§6.5): the samples come out of a
        // stored session instead of a sensor, and every line below this one runs
        // the same either way. Debug builds only — nothing arms [PendingFeed] in a
        // release APK — and it writes nothing and uploads nothing, so replaying
        // yesterday's commute cannot reach the phone's history or Health Connect.
        val replay = PendingFeed.take()
        feed = replay
        val header = replay?.header

        val id = header?.sessionId ?: UUID.randomUUID().toString()
        sessionId = id
        // NFR-6: the monotonic clock times the session; the wall clock is sampled
        // exactly once, here, for display and for Health Connect.
        //
        // A replay takes the wall clock, the zone and the sport from the recording
        // instead. Not cosmetic: the matcher only considers routes of the
        // session's own type, and its start-of-session prior is conditioned on the
        // day and the hour (§5.6), so replaying Tuesday's commute as a Sunday
        // afternoon would ask it a different question than the one under test.
        startedElapsedNs = SystemClock.elapsedRealtimeNanos()
        val startedAtUtcMs = header?.startedAtUtcMs ?: System.currentTimeMillis()
        val tzOffsetMinutes = header?.tzOffsetMinutes
            ?: (TimeZone.getDefault().getOffset(startedAtUtcMs) / 60_000)
        val exerciseType = header?.exerciseType ?: type

        val bundle = AppGraph.bundles.load()
        routeNames = bundle.routes.associate { it.geometry.routeId to it.geometry.name }

        if (replay == null) {
            val w = SessionWriter(AppGraph.sessions.fileFor(id))
            w.open()
            w.writeHeader(
                SessionHeader(
                    sessionId = id,
                    startedAtUtcMs = startedAtUtcMs,
                    tzOffsetMinutes = tzOffsetMinutes,
                    exerciseType = exerciseType,
                    bundleVersion = bundle.version.takeIf { it > 0 },
                    appVersion = BuildInfo.VERSION,
                ),
            )
            writer = w
        }

        matcher = MatcherHost(bundle, exerciseType, startedAtUtcMs, tzOffsetMinutes) { entry ->
            writer?.writeMatch(entry)
        }
        if (matcher?.disabled == true) recordEvent(SessionEventType.MATCHER_DISABLED)

        bus.update {
            RecorderUiState(
                // A replay has no sensor to warm up and no fix to wait for.
                phase = if (replay == null) RecorderPhase.PREPARING else RecorderPhase.ACTIVE,
                exerciseType = exerciseType,
                sessionId = id,
                bundleVersion = bundle.version,
                pendingUploads = it.pendingUploads,
                replayLabel = replay?.label,
                gpsReady = replay != null,
                locationAvailable = replay != null,
            )
        }

        if (replay != null) {
            Haptics.tick(this)
            driveReplay(replay)
            return
        }

        // PREPARING: warm the sensors and wait for a fix before the session starts,
        // so the first few hundred metres — the ones the matcher needs most — are
        // not spent acquiring GNSS.
        if (!source.canRecordLocation()) {
            bus.update { it.copy(message = getString(de.hecate.wear.R.string.no_location_permission)) }
            Log.w(TAG, "Keine Standortberechtigung — Aufzeichnung ohne GPS")
        }
        runCatching { source.prepare(type) }
            .onFailure {
                // Swallowed into a warning once, which cost a 44-minute run: the
                // warm-up was rejected, no exercise was ever started, and the
                // service sat in PREPARING behind an ongoing notification that
                // said it was recording. Say so, and let the timeout below start
                // the session anyway.
                Log.w(TAG, "prepareExercise fehlgeschlagen", it)
                bus.update { s -> s.copy(message = getString(de.hecate.wear.R.string.warmup_failed)) }
            }
        Haptics.tick(this)
        armPrepareTimeout(id)
    }

    /**
     * PREPARING is a nice-to-have, not a gate.
     *
     * Waiting for a fix buys the matcher the opening few hundred metres, which is
     * where it needs the data most — but only if a fix is coming. When it is not,
     * the old code waited for ever: the exercise was never started, so no sample
     * and no availability callback could arrive to end the wait, and a stopped
     * session was a header and a footer with nothing in between.
     *
     * So the wait has a deadline. Past it the session starts regardless; heart
     * rate and distance are recorded, and location joins the moment it becomes
     * available, exactly as it does after a dropout mid-run.
     */
    private fun armPrepareTimeout(id: String) {
        scope.launch {
            delay(PREPARE_TIMEOUT_MS)
            if (sessionId != id || bus.state.value.phase != RecorderPhase.PREPARING) return@launch
            Log.w(TAG, "Kein Fix nach ${PREPARE_TIMEOUT_MS / 1000}s — Start ohne GPS")
            recordEvent(SessionEventType.GPS_LOST, "kein Fix beim Start")
            bus.update { it.copy(message = getString(de.hecate.wear.R.string.started_without_gps)) }
            startExercise()
        }
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

    /**
     * Pause and resume, from whichever of the two sources is running.
     *
     * The live path asks Health Services and learns the new state back through
     * [handleUpdate]; a replay has nobody to ask, so it stops the feed and says so
     * itself. Same button, same page, both honest about what they did.
     */
    private suspend fun setPaused(paused: Boolean) {
        val replay = feed
        if (replay != null) {
            replay.setPaused(paused)
            bus.update {
                it.copy(
                    phase = if (paused) RecorderPhase.PAUSED else RecorderPhase.ACTIVE,
                    autoPaused = false,
                )
            }
            return
        }
        if (paused) source.pause() else source.resume()
    }

    /** Runs a replay to its end, which is an auto-end: the recording ran out. */
    private fun driveReplay(replay: SampleFeed) {
        replayJob = scope.launch {
            try {
                replay.drive(::onReplaySample)
            } catch (cancelled: CancellationException) {
                // Stopped, or the service is going away. Either way somebody else
                // is doing the ending.
                throw cancelled
            } catch (t: Throwable) {
                Log.e(TAG, "Wiedergabe abgebrochen", t)
            }
            replayJob = null
            endSession(SessionEndReason.AUTO_ENDED)
        }
    }

    /**
     * One replayed sample, in place of [ingest].
     *
     * [onSample] goes first, exactly as it does live: it is the matcher's path,
     * and the metrics below read the belief it produces. Everything here comes
     * out of the stored sample rather than out of Health Services, which is the
     * whole of the difference between a replay and a workout.
     */
    private fun onReplaySample(sample: Sample) {
        // A stop races the sample after it: cancellation is not instant, and a
        // straggler arriving after [endSession] has reset the bus would leave the
        // watch showing a recording that is not happening — the state outlives the
        // service, so it would still be there on the next launch. Asked of the bus
        // rather than of `sessionId`, because the flow is what publishes the end
        // of the session across threads.
        if (bus.state.value.sessionId == null) return
        onSample(sample)
        bus.update {
            it.copy(
                phase = if (sample.paused) RecorderPhase.PAUSED else RecorderPhase.ACTIVE,
                activeDurationMs = sample.activeMs,
                heartRateBpm = sample.hrBpm ?: it.heartRateBpm,
                // A session imported from a takeout carries no cumulative distance
                // of its own, so the matcher's travelled figure stands in for it.
                distanceM = sample.cumDistanceM ?: lastState?.travelledM ?: it.distanceM,
            )
        }
        maybeUpdateNotification()
    }

    private suspend fun endSession(reason: SessionEndReason) {
        val id = sessionId ?: return
        val replaying = feed != null
        // Null when a replay is ending itself, having just run out of samples.
        replayJob?.cancel()
        replayJob = null
        bus.update { it.copy(phase = RecorderPhase.ENDING) }
        // Nothing to end when nothing was started: a replay never touched Health
        // Services, and asking it to stop an exercise it has no record of is a
        // logged error for no reason.
        if (!replaying) runCatching { source.end() }

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
        feed = null
        sessionId = null
        lastState = null
        sawFirstLocation = false

        // A recording with no positions in it is not a recording. Offering it to
        // the phone is worse than useless: ingest refuses anything under two
        // samples, a refusal is deliberately not acknowledged so that a bug here
        // cannot throw data away, and the file therefore stays on the watch and
        // is re-offered for ever. Three of these had accumulated before anyone
        // noticed. Nothing is lost by dropping it — there was nothing in it.
        val empty = samplesWritten < MIN_USEFUL_SAMPLES && !replaying
        samplesWritten = 0
        if (empty) {
            Log.w(TAG, "Sitzung $id ohne Messpunkte — verworfen statt hochgeladen")
            AppGraph.sessions.discard(id)
            bus.update { it.copy(message = getString(de.hecate.wear.R.string.nothing_recorded)) }
        }

        Haptics.tick(this)
        // A replay produced no file, so there is nothing new to offer the phone
        // and no reason to wake the Data Layer on its account.
        if (!replaying) AppGraph.uploader.uploadPending()
        bus.reset()
        bus.update { it.copy(pendingUploads = AppGraph.sessions.unacked().size) }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        Log.i(TAG, if (replaying) "Wiedergabe $id beendet" else "Sitzung $id beendet ($reason)")
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

        // Health Services reports the active duration as a *checkpoint*: the value
        // it had at a stated instant. The live figure is therefore that value plus
        // the time since — but only while the exercise is actually running. While
        // it is paused the checkpoint stops advancing and the wall clock does not,
        // so extrapolating there invents active seconds the workout never had.
        //
        // Not a display bug: this number is written into every sample and into the
        // footer, so a workout paused at a level crossing used to come back from
        // the watch with the wait counted as active time — and the profile, the
        // splits and "am I faster than usual" are all derived from it.
        val activeMs = update.activeDurationCheckpoint
            ?.let { cp ->
                val sinceCheckpoint = if (exerciseState == ExerciseState.ACTIVE) {
                    (System.currentTimeMillis() - cp.time.toEpochMilli()).coerceAtLeast(0)
                } else {
                    0L
                }
                cp.activeDuration.toMillis() + sinceCheckpoint
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
            // LocationData.ALTITUDE_UNAVAILABLE is documented as NaN, which
            // isFinite() rejects — but the emulator's synthetic provider sends
            // Double.MAX_VALUE instead, and that is finite. Nothing reads altitude
            // yet, which is exactly why it is worth rejecting now: 1.8e308 m would
            // sit in the database and in every backup until the first feature that
            // did read it. A bound, as for heart rate below, rather than a test
            // against one sentinel a second provider will not use.
            altM = d.altitude.takeIf { it.isFinite() && it in MIN_ALTITUDE_M..MAX_ALTITUDE_M },
            hrBpm = hr,
            // LocationData carries only lat/lon/altitude/bearing; the horizontal
            // error lives on the data point's accuracy, which is null whenever
            // Health Services has no estimate to give.
            accuracyM = (point.accuracy as? LocationAccuracy)
                ?.horizontalPositionErrorMeters
                ?.takeIf { it.isFinite() },
            cumDistanceM = distance,
            paused = paused,
        )
    }

    /**
     * The critical path, in order: durable write first, everything else after.
     */
    private fun onSample(sample: Sample) {
        writer?.writeSample(sample)
        samplesWritten++

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
                routeByPriority = state.committedByPriority,
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

        /** Dead Sea shore to well above Everest. Anything outside is a sentinel
         *  or a broken fix, not a place this watch has been. */
        private const val MIN_ALTITUDE_M = -1_000.0
        private const val MAX_ALTITUDE_M = 10_000.0
        private const val NOTIFICATION_INTERVAL_MS = 5_000L

        /**
         * How long to wait for a first fix before starting the session anyway.
         *
         * Long enough for a cold start with a stale almanac — a Pixel Watch 4 in
         * the open is usually well under half of this — and short enough that a
         * warm-up which is never going to succeed costs a minute and a half of a
         * run rather than all of it.
         */
        private const val PREPARE_TIMEOUT_MS = 90_000L

        /** Below this, the file holds a header and a footer and nothing else. */
        private const val MIN_USEFUL_SAMPLES = 2

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
