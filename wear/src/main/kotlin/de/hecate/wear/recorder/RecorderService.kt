package de.hecate.wear.recorder

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseState
import androidx.health.services.client.data.ExerciseUpdate
import androidx.health.services.client.data.HeartRateAccuracy
import androidx.health.services.client.data.LocationAccuracy
import androidx.health.services.client.data.LocationAvailability
import androidx.health.services.client.data.LocationData
import androidx.health.services.client.data.SampleDataPoint
import de.hecate.core.Tuning
import de.hecate.core.display.RouteBelief
import de.hecate.core.display.RouteBeliefKind
import de.hecate.core.display.routeBelief
import de.hecate.core.display.HeadingUp
import de.hecate.core.geo.LatLon
import de.hecate.core.model.ActiveClock
import de.hecate.core.model.Altitudes
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.HeartRate
import de.hecate.core.model.MatchState
import de.hecate.core.model.Reading
import de.hecate.core.model.Sample
import de.hecate.core.model.SessionEndReason
import de.hecate.core.model.SessionEvent
import de.hecate.core.model.SessionEventType
import de.hecate.core.model.SessionFooter
import de.hecate.core.model.SessionHeader
import de.hecate.core.recorder.AutoStopVerdict
import de.hecate.core.recorder.AutoStopWatch
import de.hecate.wear.AppGraph
import de.hecate.wear.BuildConfig
import de.hecate.wear.R
import de.hecate.wear.notify.Haptics
import de.hecate.wear.notify.OngoingNotification
import de.hecate.wear.ui.Format
import de.hecate.wear.ui.Wording
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /**
     * The one lane everything that touches the recording runs on: the matcher,
     * the session file, the auto-stop and the counters behind them, none of
     * which is thread-safe, and all of which were written as if fed from one
     * thread.
     *
     * They were not. Health Services hands its deliveries over one at a time,
     * but each was launched onto [Dispatchers.Default], a pool, so two that
     * arrived close together — an ambient burst and the next one — ran at the
     * same time. On a walk of 9 Oct the match log shows the result: a position
     * logged after a newer one with the newer one's distance, which no single
     * thread can produce, and half a minute later the matcher threw on every
     * sample until the host switched it off for the rest of the walk. The
     * "late positions" of 5 Oct were very likely the same race seen from the
     * other side.
     *
     * One at a time, and in the order they were handed over: a dispatcher
     * limited to one runs its queue first in, first out.
     */
    private val lane = Dispatchers.Default.limitedParallelism(1)
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

    /**
     * The three rules that end a recording nobody is doing any more (issue #4,
     * A.30), and the verdict once one of them has fired.
     *
     * The verdict is kept because it carries the cut: the last sample that was
     * still part of the activity, which [endSession] writes into the footer so
     * that the phone can drop the drive home rather than file it as cycling.
     */
    private var autoStop: AutoStopWatch? = null

    @Volatile
    private var autoStopVerdict: AutoStopVerdict? = null
    private var lastState: MatchState? = null
    private var routeNames: Map<Long, String> = emptyMap()
    /** Each route's shape for the map page, read once with the bundle. */
    private var routeShapes: Map<Long, List<LatLon>> = emptyMap()
    private var lastNotificationUpdateMs = 0L

    /** The last position written, which the pause flag of the next one is
     *  decided against ([ActiveClock.unpause]). */
    private var lastSample: Sample? = null
    private var samplesWritten = 0

    /**
     * How much of this session has been spent paused, and since when, both in
     * the session's own monotonic offset.
     *
     * The active duration Health Services reports is a *checkpoint* — a value
     * that was true at some instant — and it does not arrive with every batch.
     * Stamping it on a sample therefore stamped hundreds of consecutive samples
     * with the same number, and after an auto-pause with a smaller one than
     * before: four fifths of the intervals in a real ride had no time in them,
     * and the clock stepped backwards three times. The total was always right,
     * because the footer is written at the end from a checkpoint that is current
     * by then; the *series* never was, and the series is what a speed chart and
     * a route's per-bin median times are made of.
     *
     * So each sample's active time is now derived here from the sample's own
     * offset, which comes from the monotonic clock and cannot jump, minus what
     * these two fields know about pauses. The phone repairs older recordings the
     * same way on the way out (`ActiveClock`); this is so that new ones do not
     * need repairing.
     */
    private var pausedTotalMs = 0L
    private var pausedSinceMs: Long? = null

    /**
     * The last heart rate Health Services delivered, and when.
     *
     * A sample is written per *position*, and the rate stamped on it used to be
     * whatever rate happened to arrive in the same batch — so on two real rides
     * only 28 % and 36 % of samples carried one, and the phone drew a ride whose
     * effort looked unmeasured for two thirds of its length. The sensor had not
     * stopped; the batches simply do not line up.
     *
     * A rate is therefore carried forward onto later samples for as long as it
     * is still a fair description of the effort
     * ([de.hecate.core.Tuning.HR_HOLD_MS]), and no longer: past that the strap
     * really has lost contact, and a sample with no rate is the honest record of
     * it. The phone holds older recordings the same way on the way out.
     */
    private var lastHrBpm: Int? = null
    private var lastHrAtMs = 0L

    /** The last reading written, and how many — the second only so the log can
     *  say at the end what the sensor actually gave, which is the number nobody
     *  had until a ride came back with a fifth of it. */
    private var lastRateAtMs = -1L
    private var ratesWritten = 0

    /** The last speed Health Services delivered, and when, in session time. */
    private var lastSpeedKmh: Double? = null
    private var lastSpeedAtMs = 0L

    /** The last of each series written, and how many — the counts only so the
     *  log can say at the end what each sensor actually gave. */
    private var lastAltitudeAtMs = -1L
    private var altitudesWritten = 0
    private var lastSpeedReadingAtMs = -1L
    private var speedsWritten = 0

    /** Serialises [endSession]; see there for what happens without it. */
    private val endLock = Mutex()

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
                scope.launch(lane) { beginSession(type) }
            }
            ACTION_PAUSE -> scope.launch(lane) { setPaused(true) }
            ACTION_RESUME -> scope.launch(lane) { setPaused(false) }
            ACTION_STOP -> scope.launch(lane) { endSession(SessionEndReason.USER_STOPPED) }
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
        val n = OngoingNotification.build(this, getString(R.string.app_name), "…")
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
        pausedTotalMs = 0L
        pausedSinceMs = null
        lastHrBpm = null
        lastSample = null
        lastHrAtMs = 0L
        lastAltitudeAtMs = -1L
        altitudesWritten = 0
        lastSpeedReadingAtMs = -1L
        speedsWritten = 0
        lastRateAtMs = -1L
        ratesWritten = 0
        val startedAtUtcMs = header?.startedAtUtcMs ?: System.currentTimeMillis()
        val tzOffsetMinutes = header?.tzOffsetMinutes
            ?: (TimeZone.getDefault().getOffset(startedAtUtcMs) / 60_000)
        val exerciseType = header?.exerciseType ?: type

        val bundle = AppGraph.bundles.load()
        routeNames = bundle.routes.associate { it.geometry.routeId to it.geometry.name }
        routeShapes = bundle.routes.associate { r ->
            r.geometry.routeId to r.geometry.points.map { LatLon(it.lat, it.lon) }
        }
        bus.update { it.copy(trail = emptyList(), followedRoute = null) }

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
                    appVersion = BuildConfig.VERSION_NAME,
                ),
            )
            writer = w
        }

        matcher = MatcherHost(bundle, exerciseType, startedAtUtcMs, tzOffsetMinutes) { entry ->
            writer?.writeMatch(entry)
        }
        if (matcher?.disabled == true) recordEvent(SessionEventType.MATCHER_DISABLED)

        // Fed from [onSample], which a replay reaches by the same path a workout
        // does: the rules read sample offsets and never a clock, so replaying a
        // recording that ended in a car reaches the same verdict on the same
        // sample the wrist would have (§6.5).
        autoStop = AutoStopWatch(exerciseType)
        autoStopVerdict = null

        val canLocate = source.canRecordLocation()
        bus.update {
            RecorderUiState(
                // A replay has no sensor to warm up and no fix to wait for.
                phase = if (replay == null) RecorderPhase.PREPARING else RecorderPhase.ACTIVE,
                exerciseType = exerciseType,
                sessionId = id,
                startedAtUtcMs = startedAtUtcMs,
                bundleVersion = bundle.version,
                pendingUploads = it.pendingUploads,
                // A replay needs no permission and has nothing to ask for one.
                locationPermission = canLocate || replay != null,
                // The header is a write, and dating the first one from here is
                // what keeps "nothing has been written for a minute and a half"
                // from being true of every session before its first sample.
                lastWriteAtMs = System.currentTimeMillis(),
                replayLabel = replay?.label,
                // Its first fix is dated from now for the same reason the first
                // write is, and more urgently: [startedAtUtcMs] is the
                // *recording's* start, so the health line's "never had a fix, so
                // measure from the beginning" fallback would open every replay of
                // a week-old session by reporting a week without GPS.
                gpsReady = replay != null,
                lastFixAtMs = if (replay != null) System.currentTimeMillis() else 0L,
            )
        }
        if (matcher?.disabled == true) disableMatcher()

        if (replay != null) {
            Haptics.tick(this)
            driveReplay(replay)
            return
        }

        // PREPARING: warm the sensors and wait for a fix before the session starts,
        // so the first few hundred metres — the ones the matcher needs most — are
        // not spent acquiring GNSS.
        if (!canLocate) {
            say(getString(R.string.no_location_permission))
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
                say(getString(R.string.warmup_failed))
            }
        Haptics.tick(this)
        armPrepareTimeout(id)
        armAutoStopWatchdog(id)
    }

    /**
     * Ask the auto-stop, on a timer, what the samples cannot say.
     *
     * Both of its patience rules expire in silence: an auto-pause writes no
     * samples at all, and a receiver that has quietly given up writes none that
     * are usable, so a recording left in a rucksack would never be asked about
     * again if the question only came with a position.
     *
     * Live sessions only. A replay is paced off the recording's own offsets, at
     * whatever speed was asked for, and the wall clock this reads has nothing to
     * do with either — there the samples do all the asking.
     */
    private fun armAutoStopWatchdog(id: String) {
        scope.launch(lane) {
            while (sessionId == id) {
                delay(Tuning.AUTO_STOP_TICK_MS)
                if (sessionId != id) return@launch
                val verdict = autoStop?.tick(nowOffsetMs()) ?: continue
                autoStopped(verdict)
                return@launch
            }
        }
    }

    /**
     * End the recording, and say why on the wrist.
     *
     * The event goes in before the footer so that a file whose last write is
     * torn still carries the reason, and the two firm pulses are the deviation
     * alert's rather than the stop tick's: a session that ended itself is worth
     * feeling through a sleeve.
     */
    private suspend fun autoStopped(verdict: AutoStopVerdict) {
        if (sessionId == null) return
        autoStopVerdict = verdict
        Log.i(TAG, "Automatischer Stopp: ${verdict.note}")
        recordEvent(SessionEventType.AUTO_STOP, verdict.note)
        Haptics.deviation(this)
        say(getString(R.string.auto_stopped, verdict.reason.label))
        endSession(SessionEndReason.AUTO_STOPPED)
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
        scope.launch(lane) {
            delay(PREPARE_TIMEOUT_MS)
            if (sessionId != id || bus.state.value.phase != RecorderPhase.PREPARING) return@launch
            Log.w(TAG, "Kein Fix nach ${PREPARE_TIMEOUT_MS / 1000}s — Start ohne GPS")
            recordEvent(SessionEventType.GPS_LOST, "kein Fix beim Start")
            say(getString(R.string.started_without_gps))
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
                say("Start fehlgeschlagen")
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
                    resumedAtMs = if (paused) it.resumedAtMs else System.currentTimeMillis(),
                )
            }
            return
        }
        if (paused) source.pause() else source.resume()
    }

    /** Runs a replay to its end, which is an auto-end: the recording ran out. */
    private fun driveReplay(replay: SampleFeed) {
        replayJob = scope.launch(lane) {
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

    /**
     * Close the session file, decide whether it was worth keeping, and hand it
     * to the uploader.
     *
     * **Single-flight, and that is not defensive programming.** Two things end a
     * recording — the user pressing Beenden, and Health Services reporting the
     * exercise ended — and on a real stop both arrive. The old guard read
     * `sessionId` at the top and cleared it eight lines and three suspension
     * points later, so both callers walked straight past it on different
     * threads; the first consumed the sample counter and zeroed it, the second
     * then read zero, concluded the recording was empty, and *deleted the file*.
     * Seen on the emulator with 82 positions and 100 heart rates in it.
     *
     * The lock closes the window and clearing the id inside it makes the second
     * call a no-op rather than a second ending.
     */
    private suspend fun endSession(reason: SessionEndReason) = endLock.withLock {
        val id = sessionId ?: return@withLock
        // Claimed before the first suspension point: anything else that arrives
        // now finds nothing to end.
        sessionId = null
        val replaying = feed != null
        // Null when a replay is ending itself, having just run out of samples;
        // cancelling here would then cancel the coroutine doing the ending.
        replayJob?.cancel()
        replayJob = null
        bus.update { it.copy(phase = RecorderPhase.ENDING) }
        // Nothing to end when nothing was started: a replay never touched Health
        // Services, and asking it to stop an exercise it has no record of is a
        // logged error for no reason.
        if (!replaying) runCatching { source.end() }

        val st = bus.state.value
        // An auto-stop's totals are those of the last sample that was still part
        // of the activity, not those of the moment the watch gave up: the live
        // figures include the drive home, and a footer written from them would
        // hand Health Connect a 40 km bike ride the user did not do. The samples
        // after the cut stay in the file — it is append-only, and rewriting it is
        // the one thing a recorder may not do — and the phone drops them on the
        // way in (A.30).
        val cut = autoStopVerdict?.keepThrough
        writer?.writeFooter(
            SessionFooter(
                endedAtUtcMs = System.currentTimeMillis(),
                activeDurationMs = cut?.activeMs ?: st.activeDurationMs,
                totalDistanceM = cut?.cumDistanceM ?: st.distanceM,
                reason = reason,
                trimmedAfterTOffsetMs = cut?.tOffsetMs,
            ),
        )
        writer?.close()
        writer = null
        matcher = null
        autoStop = null
        autoStopVerdict = null
        feed = null
        lastState = null

        // What the two sensors actually gave, side by side. The heart rate is
        // here because nobody had that number until a ride came back with a
        // fifth of one, and a count in the log is how the next ride says so
        // without anyone having to unpack a backup to find out.
        //
        // A recording with no positions in it is not a recording. Offering it to
        // the phone is worse than useless: ingest refuses anything under two
        // samples, a refusal is deliberately not acknowledged so that a bug here
        // cannot throw data away, and the file therefore stays on the watch and
        // is re-offered for ever. Three of these had accumulated before anyone
        // noticed. Nothing is lost by dropping it — there was nothing in it,
        // which is a claim this code has to be *sure* of: see the lock above.
        //
        // Never for a replay, which is surer still: it opened no file, and the id
        // it runs under is the stored session's own, so discarding "its" file
        // would delete the recording being replayed.
        Log.i(
            TAG,
            "Sitzung $id: $samplesWritten Messpunkte, $ratesWritten Pulswerte, " +
                "$altitudesWritten Höhenwerte, $speedsWritten Geschwindigkeiten",
        )
        val empty = samplesWritten < MIN_USEFUL_SAMPLES && !replaying
        samplesWritten = 0
        ratesWritten = 0
        if (empty) {
            Log.w(TAG, "Sitzung $id ohne Messpunkte — verworfen statt hochgeladen")
            AppGraph.sessions.discard(id)
            say(getString(R.string.nothing_recorded))
        }

        Haptics.tick(this)
        // Watched from the start screen until the phone confirms it (or the user
        // stops watching). Set before the upload starts, so a phone quick enough
        // to acknowledge while uploadPending is still running cannot confirm a
        // handoff that does not exist yet.
        if (!replaying && !empty) {
            bus.update { it.copy(handoff = Handoff(id, startedAtMs = System.currentTimeMillis())) }
        }
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

    /**
     * Put something in front of the user.
     *
     * Every one of these used to be rendered on the *start* screen only, so a
     * warm-up that failed, a start that failed, or a recording running without
     * location permission was something the user found out about after they had
     * finished. The live screen renders it too now, and the timestamp is what
     * lets it retire itself once it has been on the wrist long enough to be read.
     */
    private fun say(text: String) {
        bus.update { it.copy(message = text, messageAtMs = System.currentTimeMillis()) }
    }

    private fun nowOffsetMs(): Long =
        (SystemClock.elapsedRealtimeNanos() - startedElapsedNs) / 1_000_000

    /** Active time at a sample taken [tOffsetMs] into the session. A batch can
     *  arrive after a pause began, so the pause in progress counts only from
     *  where it started — and never to less than zero. */
    private fun activeAt(tOffsetMs: Long): Long {
        val inProgress = pausedSinceMs?.let { (tOffsetMs - it).coerceAtLeast(0) } ?: 0L
        return (tOffsetMs - pausedTotalMs - inProgress).coerceAtLeast(0)
    }

    // ----------------------------------------------------------------- callback

    private val callback = object : ExerciseSource.Callback {
        override fun onRegistered() = Unit

        override fun onRegistrationFailed(throwable: Throwable) {
            Log.e(TAG, "Health Services Registrierung fehlgeschlagen", throwable)
            say("Health Services nicht verfügbar")
        }

        override fun onUpdate(update: ExerciseUpdate) {
            scope.launch(lane) { handleUpdate(update) }
        }

        override fun onAvailability(dataType: DataType<*, *>, availability: Availability) {
            // On the lane too: it writes events into the same file the samples go to.
            scope.launch(lane) { availabilityChanged(availability) }
        }
    }

    /** Only location's availability is acted on; nothing reads the others. */
    private fun availabilityChanged(availability: Availability) {
        if (availability is LocationAvailability) {
            val ready = availability == LocationAvailability.ACQUIRED_TETHERED ||
                availability == LocationAvailability.ACQUIRED_UNTETHERED
            val lost = availability == LocationAvailability.ACQUIRING ||
                availability == LocationAvailability.NO_GNSS
            val was = bus.state.value.gpsReady
            bus.update {
                it.copy(
                    gpsReady = ready,
                    gpsSignalLost = when {
                        ready -> false
                        lost -> true
                        else -> it.gpsSignalLost
                    },
                )
            }
            if (!was && ready) {
                recordEvent(SessionEventType.GPS_ACQUIRED)
                if (bus.state.value.phase == RecorderPhase.PREPARING) {
                    scope.launch(lane) { startExercise() }
                }
            } else if (was && !ready) {
                recordEvent(SessionEventType.GPS_LOST)
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
                    pausedSinceMs = nowOffsetMs()
                    // Only the user's own pause suspends the auto-stop. An
                    // auto-pause *is* the standing still it watches for, and
                    // suspending on it would make the one case it is surest
                    // about — the watch left on the kitchen table — the one case
                    // it never sees.
                    if (!auto) autoStop?.manualPause(true, nowOffsetMs())
                }
                bus.update { it.copy(phase = RecorderPhase.PAUSED, autoPaused = auto) }
            }
            exerciseState == ExerciseState.ACTIVE -> {
                if (bus.state.value.phase == RecorderPhase.PAUSED) {
                    recordEvent(
                        if (bus.state.value.autoPaused) SessionEventType.RESUME_AUTO
                        else SessionEventType.RESUME_MANUAL,
                    )
                    pausedSinceMs?.let { pausedTotalMs += (nowOffsetMs() - it).coerceAtLeast(0) }
                    pausedSinceMs = null
                    // The resume event above is a write too, and dating it keeps a
                    // long pause from reading as "Keine Aufzeichnung" until the
                    // first sample after it lands.
                    val now = System.currentTimeMillis()
                    bus.update { it.copy(resumedAtMs = now, lastWriteAtMs = now) }
                    autoStop?.manualPause(false, nowOffsetMs())
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

        ingest(update, activeMs)
    }

    /**
     * Turn one delivery of Health Services metrics into samples and series.
     *
     * Deliveries arrive about once a second with the screen on and in bursts
     * while ambient (§1.3) — Health Services documents a maximum reporting period
     * of around 150 seconds, so one of them can carry two and a half minutes of
     * every series at once.
     *
     * That fact is what this method is organised around. Anything measured on its
     * own schedule is written from its own data points, at the times the sensor
     * stamped them; nothing is reduced to one value per delivery and then stamped
     * across every position in the batch. Doing the latter is how the heart rate
     * once lost four fifths of its readings, and it is why altitude and speed are
     * series here rather than columns.
     */
    private fun ingest(update: ExerciseUpdate, activeMs: Long) {
        val metrics = update.latestMetrics
        val locations = runCatching { metrics.getData(DataType.LOCATION) }.getOrDefault(emptyList())
        val hrPoints = runCatching { metrics.getData(DataType.HEART_RATE_BPM) }.getOrDefault(emptyList())
        val elevations = runCatching {
            metrics.getData(DataType.ABSOLUTE_ELEVATION)
        }.getOrDefault(emptyList())
        val speeds = runCatching { metrics.getData(DataType.SPEED) }.getOrDefault(emptyList())
        // Cumulative, and therefore one value for the whole delivery rather than
        // one per position in it. Read as an aggregate, which is a different call
        // from the delta types above: `getData` returns a single point for an
        // AggregateDataType and a list for a DeltaDataType.
        val distance = runCatching { metrics.getData(DataType.DISTANCE_TOTAL)?.total }.getOrNull()

        // Each series first, every reading at the time its own sensor took it.
        writeRates(hrPoints)
        writeAltitudes(elevations)
        writeSpeeds(speeds)

        // What the live screen shows, which is a different question: there the
        // only interesting reading is the most recent one, and how old it is.
        //
        // Stamped with the data point's own time, not with the moment it was
        // processed. In ambient the two differ by up to the reporting period, and
        // taking "now" told [Tuning.HR_HOLD_MS] and [Tuning.FIX_STALE_MS] that a
        // two-minute-old reading had just arrived — so the screen presented stale
        // numbers as current for exactly as long as the hold allowed.
        hrPoints.lastOrNull { usableRate(it) }?.let {
            lastHrBpm = it.value.toInt()
            lastHrAtMs = offsetOf(it)
        }
        val hr = lastHrBpm?.takeIf { nowOffsetMs() - lastHrAtMs <= Tuning.HR_HOLD_MS }

        val speed = speeds.lastOrNull { it.value.isFinite() && it.value >= 0 }
        speed?.let {
            lastSpeedKmh = it.value * 3.6
            lastSpeedAtMs = offsetOf(it)
        }
        // Held only as long as a position fix would be: speed is a location
        // product, and once the fix is stale this is not a current speed but a
        // souvenir. The display then falls back to the session average, which it
        // labels as an average rather than passing it off as this one.
        val shownSpeed = lastSpeedKmh?.takeIf { nowOffsetMs() - lastSpeedAtMs <= Tuning.FIX_STALE_MS }
        if (hr != null || distance != null || speed != null) {
            bus.update {
                it.copy(
                    heartRateBpm = hr ?: it.heartRateBpm,
                    distanceM = distance ?: it.distanceM,
                    speedKmh = shownSpeed,
                    activeDurationMs = activeMs,
                )
            }
        }

        // A delivery that carried no position still carries the time it arrived
        // in, and the timers are exactly about time passing with nothing to show
        // for it. Cheaper than waiting for the watchdog's next half minute, and
        // the one thing that still arrives while ambient batching stretches
        // everything else out.
        autoStop?.tick(nowOffsetMs())?.let { verdict -> scope.launch(lane) { autoStopped(verdict) } }

        if (locations.isEmpty()) {
            maybeUpdateNotification()
            return
        }

        // Pause state is read once for the delivery — and a delivery *does*
        // straddle a pause: the batch that ends at an auto-pause, or the first
        // one after a resume, arrives with the paused state and half a minute
        // of positions that were taken while moving (four times on a walk of
        // 8 Oct). So the delivery's state is only where a position starts;
        // Health Services' own active time at each point decides it, see
        // [ActiveClock.unpause].
        val paused = bus.state.value.phase == RecorderPhase.PAUSED
        val sorted = locations.inTimeOrder()
        for ((i, point) in sorted.withIndex()) {
            // The cumulative distance describes the delivery, so it belongs on
            // the position that ends it and on no other. Stamping it across the
            // batch gave every position in an ambient burst the same travelled
            // figure — which nothing on the phone reads, but which was written
            // into the file as though it were measured there. Readers of an older
            // session are unaffected: a null already falls back (see the replay
            // path in [onReplaySample]).
            val cumulative = if (i == sorted.size - 1) distance else null
            val sample = toSample(update, point, cumulative, paused)
                ?.let { ActiveClock.unpause(lastSample, it) }
                ?: continue
            onSample(sample)
        }
        maybeUpdateNotification()
    }

    /**
     * Whether a heart-rate reading is worth recording at all.
     *
     * The range check alone was never enough. Health Services attaches a
     * [HeartRateAccuracy] to each reading, and a watch that has lost skin contact
     * keeps producing numbers that sit comfortably inside 20..250 — Google's
     * guidance is explicit that the accuracy is there to be read. NO_CONTACT and
     * UNRELIABLE are the two that say the value is not a measurement of anybody.
     *
     * ACCURACY_LOW is kept. It means the sensor is struggling, not that it is
     * wrong, and a workout where the optics never settle is better described by
     * poor readings than by no readings — the bands the display draws are ten
     * percent of a peak wide and survive that. An absent accuracy is also kept:
     * it means the platform did not say, which is not the same as saying no.
     */
    private fun usableRate(point: SampleDataPoint<Double>): Boolean {
        if (!point.value.isFinite() || point.value.toInt() !in 20..250) return false
        val status = (point.accuracy as? HeartRateAccuracy)?.sensorStatus ?: return true
        return status != HeartRateAccuracy.SensorStatus.NO_CONTACT &&
            status != HeartRateAccuracy.SensorStatus.UNRELIABLE
    }

    /** Session time of a data point, from the boot-clock instant Health Services
     *  stamped it with: the one conversion every series and every sample goes
     *  through. */
    private fun offsetOf(point: SampleDataPoint<*>): Long =
        point.timeDurationFromBoot.toMillis() - startedElapsedNs / 1_000_000

    /** A delivery's points in the order they were measured. Every series is
     *  written in it, and each one's "nothing at or before the last time
     *  written" dedup depends on it. */
    private fun <T : Any> List<SampleDataPoint<T>>.inTimeOrder(): List<SampleDataPoint<T>> =
        sortedBy { it.timeDurationFromBoot.toMillis() }

    /**
     * Write every barometric altitude of a delivery, at the time it was measured.
     *
     * A series rather than a column on the position, because the two sensors do
     * not keep the same time and the barometer is much the slower: measured on a
     * Pixel Watch 4, readings came 57 s, 27 s and 2 s apart while positions
     * arrived every second. Joining them at this end kept 16 samples in 101, and
     * would have kept nothing at all through a GNSS dropout — which is precisely
     * where a barometer is the only sensor still working. The phone puts the
     * series onto the positions on the way out, where the decision can be changed
     * without the readings having been thrown away first.
     *
     * The bound is the one the position's own altitude gets, for the same reason:
     * a synthetic sensor reports values no place on earth has.
     */
    private fun writeAltitudes(points: List<SampleDataPoint<Double>>) {
        val w = writer ?: return
        for (point in points.inTimeOrder()) {
            val metres = point.value
                .takeIf { Altitudes.isPlausible(it) }
                ?: continue
            val tOffsetMs = offsetOf(point)
            if (tOffsetMs < 0 || tOffsetMs <= lastAltitudeAtMs) continue
            w.writeAltitude(Reading(tOffsetMs = tOffsetMs, value = metres))
            lastAltitudeAtMs = tOffsetMs
            altitudesWritten++
        }
    }

    /**
     * Write every speed reading of a delivery, at the time it was measured.
     *
     * Health Services measures speed rather than inferring it, which beats
     * differencing two positions that carry a few metres of error each. The data
     * type was already being asked for — and paid for in battery — while only the
     * last value of each delivery was read for one live cell and none of it kept.
     */
    private fun writeSpeeds(points: List<SampleDataPoint<Double>>) {
        val w = writer ?: return
        for (point in points.inTimeOrder()) {
            val mps = point.value.takeIf { it.isFinite() && it >= 0.0 } ?: continue
            val tOffsetMs = offsetOf(point)
            if (tOffsetMs < 0 || tOffsetMs <= lastSpeedReadingAtMs) continue
            w.writeSpeed(Reading(tOffsetMs = tOffsetMs, value = mps))
            lastSpeedReadingAtMs = tOffsetMs
            speedsWritten++
        }
    }

    /**
     * Write each heart-rate reading of a delivery, at the time the sensor took
     * it.
     *
     * Deduplicated by that time, because a batch can repeat what the previous
     * one ended with, and a file with the same reading twice is a file that
     * disagrees with itself about how often the sensor measured.
     */
    private fun writeRates(points: List<SampleDataPoint<Double>>) {
        val w = writer ?: return
        var wrote = false
        for (point in points.inTimeOrder()) {
            if (!usableRate(point)) continue
            val bpm = point.value.toInt()
            val tOffsetMs = offsetOf(point)
            if (tOffsetMs < 0 || tOffsetMs <= lastRateAtMs) continue
            w.writeRate(HeartRate(tOffsetMs = tOffsetMs, bpm = bpm))
            lastRateAtMs = tOffsetMs
            ratesWritten++
            wrote = true
        }
        // A heart rate is a write too: a workout whose GNSS has died but whose
        // strap is fine is degraded, not dead, and only a file that nothing at
        // all reaches is "Keine Aufzeichnung". Published once per delivery
        // rather than once per reading — a batch can carry a minute of them, and
        // sixty identical state updates are sixty recompositions.
        if (wrote) bus.update { it.copy(lastWriteAtMs = System.currentTimeMillis()) }
    }

    private fun toSample(
        update: ExerciseUpdate,
        point: SampleDataPoint<LocationData>,
        distance: Double?,
        paused: Boolean,
    ): Sample? {
        val d = point.value
        if (!d.latitude.isFinite() || !d.longitude.isFinite()) return null
        val tOffsetMs = offsetOf(point)
        if (tOffsetMs < 0) return null
        return Sample(
            tOffsetMs = tOffsetMs,
            // The platform's own answer for this exact position, not ours for the
            // delivery it arrived in.
            //
            // Health Services knows when the exercise was paused because it is
            // what paused it, and `getActiveDurationAtDataPoint` returns the
            // active duration at the point rather than at the moment the batch
            // was handled. The bookkeeping it replaces subtracted our running
            // pause totals *as they stood now*, so every position in a batch got
            // the same answer and a batch delivered after a pause got the wrong
            // one — the failure that once counted a wait at a level crossing as
            // active time. [activeAt] is the fallback for a point Health
            // Services cannot place.
            activeMs = runCatching { update.getActiveDurationAtDataPoint(point).toMillis() }
                .getOrNull()
                ?.coerceAtLeast(0L)
                ?: activeAt(tOffsetMs),
            lat = d.latitude,
            lon = d.longitude,
            // Both of these are series now, written at their own times by
            // [writeAltitudes] and [writeRates]; the phone joins them on the way
            // in. Leaving them null here is the point rather than an omission —
            // a value carried over from elsewhere in the batch is a claim that
            // this position was measured with it, and it was not.
            //
            // `LocationData.altitude` is not a fallback either. A Pixel Watch 4
            // sends Double.MAX_VALUE for it on every fix — the "no altitude here"
            // sentinel, finite, and exactly what the bound in [writeAltitudes]
            // exists to reject. That it is real hardware and not only the
            // emulator is the correction A.18 needs.
            altM = null,
            hrBpm = null,
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
        lastSample = sample
        // The map page's own track. Usable fixes only: a 200 m error ellipse
        // drawn as a zig-zag across the face is exactly the deviation the page
        // exists to show, and it would not be one.
        if (sample.usable) {
            val fix = LatLon(sample.lat, sample.lon)
            bus.update { it.copy(trail = HeadingUp.extend(it.trail, fix)) }
        }
        // The two facts the health line is made of, both wall-clock because the
        // user's question is "how long has this been going on". The fix age is
        // taken from a sample that actually passed [Sample.usable] rather than
        // from a LocationAvailability callback: the callback fires on
        // transitions, and a fix that quietly degrades to 200 m of error — the
        // failure that used to look like the runner slowing down — never
        // produces one.
        val now = System.currentTimeMillis()
        bus.update {
            it.copy(
                lastWriteAtMs = now,
                lastFixAtMs = if (sample.usable) now else it.lastFixAtMs,
                lastAccuracyM = sample.accuracyM ?: it.lastAccuracyM,
            )
        }

        // After the write and before the matcher: a decision to stop is about
        // the recording, not about the route, and it must not depend on a
        // matcher that may be disabled. Launched rather than awaited because
        // ending a session suspends and this path may not.
        autoStop?.sample(sample)?.let { verdict -> scope.launch(lane) { autoStopped(verdict) } }

        val state = matcher?.update(sample)
        if (matcher?.disabled == true && !bus.state.value.matcherDisabled) {
            recordEvent(SessionEventType.MATCHER_DISABLED)
            disableMatcher()
        }
        if (state != null) applyMatch(state)
    }

    /**
     * The matcher is out of the session; take its numbers with it.
     *
     * [MatcherHost.update] returns null once disabled, so without this the last
     * belief simply stayed on screen: a route name the app no longer stood
     * behind, an ahead/behind figure frozen mid-workout, and an arrival time
     * that drifted a minute later for every minute the user kept looking at it.
     * The headline says the matcher is off; nothing below it may keep implying
     * otherwise.
     */
    private fun disableMatcher() {
        lastState = null
        bus.update {
            it.copy(
                matcherDisabled = true,
                belief = RouteBelief(RouteBeliefKind.MATCHER_OFF),
                remainingM = null,
                arrivalAtUtcMs = null,
                deltaVsMedianS = null,
                deltaScaleS = null,
                progressFraction = null,
                positionStale = false,
            )
        }
    }

    private fun applyMatch(state: MatchState) {
        lastState = state
        val belief = routeBelief(state, matcherDisabled = false) { routeNames[it] }
        // The arrival time is fixed here, against the one wall clock reading it
        // is allowed to have. The matcher deals only in seconds-from-now because
        // it may not touch a clock at all (§5.6 determinism); turning that into
        // an instant at every recomposition, which is what the display used to
        // do, made a stale ETA walk forwards for ever.
        val arrival = state.etaSecondsFromNow
            ?.takeIf { it.isFinite() && it >= 0 }
            ?.let { System.currentTimeMillis() + (it * 1000).toLong() }
        // The route the numbers are about is the route the map draws: the
        // commitment, or the leader while the headline names it as a guess.
        val followedId = state.committedRouteId
            ?: state.leaderRouteId?.takeIf { belief.hasRoute }
        bus.update {
            val followed = when {
                followedId == null -> null
                it.followedRoute?.routeId == followedId &&
                    it.followedRoute.assumed == (state.committedRouteId == null) -> it.followedRoute
                else -> routeShapes[followedId]?.let { pts ->
                    FollowedRoute(followedId, pts, assumed = state.committedRouteId == null)
                }
            }
            it.copy(
                belief = belief,
                followedRoute = followed,
                remainingM = if (belief.hasRoute) state.remainingM else null,
                arrivalAtUtcMs = if (belief.hasRoute) arrival else null,
                progressFraction = if (belief.hasRoute) state.progressFraction else null,
                deltaVsMedianS = state.deltaVsMedianS,
                deltaScaleS = state.deltaScaleS,
                positionStale = state.positionStaleMs > Tuning.STALE_POSITION_MS,
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
        // One wording for the wrist and the watch face: both go through
        // [Wording], so the notification cannot say "Suche…" over a screen
        // showing a route name, which is what two hand-rolled title mappings
        // used to manage between them.
        val title = if (s.offRoute) "Abweichung" else Wording.route(s.belief)
        OngoingNotification.update(
            this,
            title,
            "${Format.distance(s.distanceM)} · ${Format.duration(s.activeDurationMs)}",
        )
    }

    override fun onDestroy() {
        // A destroyed service must never take an in-progress recording with it:
        // the file is synced and closed, and whatever was written survives.
        writer?.close()
        scope.launch { source.clearCallback() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RecorderService"

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
