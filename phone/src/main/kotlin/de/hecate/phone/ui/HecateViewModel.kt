package de.hecate.phone.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.health.connect.client.HealthConnectClient
import androidx.work.WorkInfo
import androidx.work.WorkManager
import de.hecate.core.Tuning
import de.hecate.core.geo.LatLon
import de.hecate.core.heat.HeatmapCodec
import de.hecate.core.heat.HeatmapGrid
import de.hecate.core.io.BundleCodec
import de.hecate.core.model.ExerciseType
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.SessionEvent
import de.hecate.core.profile.Elevation
import de.hecate.core.profile.Series
import de.hecate.phone.AppGraph
import de.hecate.core.archive.Archive
import de.hecate.phone.db.RouteSummary
import de.hecate.phone.db.SessionSummary
import de.hecate.phone.tcx.TcxImporter
import de.hecate.phone.work.Jobs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Which screen is showing (§4: no Compose Navigation).
 *
 * "Recording" is the user's word for what the database calls a session. The
 * rename stops at this layer deliberately: `session` is the wire format, the
 * table name, the archive's directory and the watch's filename, and renaming it
 * downward would change the backup format to relabel a screen.
 */
sealed interface Screen {
    data object Recordings : Screen
    data object Routes : Screen
    data object Settings : Screen
    data class SessionDetail(val id: String) : Screen
    data class RouteDetail(val id: Long) : Screen
}

/** The heatmap raster together with the geometry drawn on top of it.
 *
 *  Named for the state rather than the view, because [HeatmapView] is the
 *  composable that draws it. */
data class HeatmapState(val grid: HeatmapGrid, val routes: List<List<LatLon>>)

/**
 * What the Health Connect switch in settings is currently showing.
 *
 * Four states rather than a boolean because "off" has three different causes and
 * they need different words: not installed, too old, or simply not granted yet.
 */
enum class HealthConnectState {
    UNAVAILABLE,
    UPDATE_REQUIRED,
    DISCONNECTED,
    CONNECTED,
}

/** Everything the route detail screen renders (US-8). */
data class RouteDetail(
    val id: Long,
    val name: String,
    val exerciseType: ExerciseType,
    val lengthM: Double,
    val memberCount: Int,
    /** The route's own geometry, carrying the per-bin median heart rate so the
     *  drawing can be coloured by effort. */
    val geometry: List<TrackPoint>,
    val medianDurationMs: Long?,
    val bestDurationMs: Long?,
    val worstDurationMs: Long?,
    val priority: Int,
    /** The route's own elevation profile, thinned for drawing, and the numbers
     *  read off the full one. Empty and null when no member recorded altitude,
     *  or when the route has not been clustered since schema v6. */
    val elevation: List<Elevation.Point> = emptyList(),
    val elevationStats: Elevation.Stats? = null,
    val sessions: List<SessionSummary>,
)

/** Everything the session detail screen renders (US-8). */
data class SessionDetail(
    val summary: SessionSummary,
    /** The recording's own track, carrying each sample's heart rate so the
     *  drawing can be coloured by effort. */
    val geometry: List<TrackPoint>,
    val routeName: String?,
    val coverage: Double?,
    val committedAtM: Double?,
    val hcState: Int,
    val hcError: String?,
    val matchLogSize: Int,
    val matchEvents: List<MatchLogEntry>,
    /** What happened to the recording itself: pauses, GPS, service restarts.
     *  Empty for sessions ingested before schema v3, and for TCX imports, which
     *  never had any. */
    val sessionEvents: List<SessionEvent>,
    /** This recording's own altitude against distance, thinned for drawing, and
     *  the numbers read off the full series. Empty and null for a recording that
     *  carries no altitude at all. */
    val elevation: List<Elevation.Point> = emptyList(),
    val elevationStats: Elevation.Stats? = null,
    /** How the watch's matcher settled on the route, or null when it never did
     *  — every imported session, and any recording whose route was assigned by
     *  the clusterer afterwards rather than live. */
    val committedByPriority: Boolean?,
)

/**
 * One view model for the whole phone app. Three screens do not need three.
 */
class HecateViewModel(app: Application) : AndroidViewModel(app) {

    private val db get() = AppGraph.db

    init {
        // Pick up anything the Data Layer is still holding. `IngestService` gets
        // the event when one arrives; nothing used to look again if that event
        // never came, and three sessions had been sitting there unnoticed. One
        // query at app start is the cheapest possible recovery path, and in the
        // ordinary case it finds nothing.
        viewModelScope.launch { AppGraph.dataLayer.sweep() }
    }

    private val _screen = MutableStateFlow<Screen>(Screen.Recordings)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    /**
     * Where back goes, most recent last.
     *
     * Switching panels used to leave nothing behind, so the system back button
     * fell through to the launcher and the app closed — from a panel the user had
     * arrived at one tap earlier. Bounded because it is a convenience, not a
     * history: a user who taps between two panels forty times wants the last few
     * back presses to work, not all forty.
     */
    private val _backStack = MutableStateFlow<List<Screen>>(emptyList())
    val canGoBack: StateFlow<Boolean> = _backStack
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _healthConnect = MutableStateFlow(HealthConnectState.UNAVAILABLE)
    val healthConnect: StateFlow<HealthConnectState> = _healthConnect.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _routeDetail = MutableStateFlow<RouteDetail?>(null)
    val routeDetail: StateFlow<RouteDetail?> = _routeDetail.asStateFlow()

    private val _sessionDetail = MutableStateFlow<SessionDetail?>(null)
    val sessionDetail: StateFlow<SessionDetail?> = _sessionDetail.asStateFlow()

    val sessions: StateFlow<List<SessionSummary>> = db.sessions().summaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The activities the filter offers: the ones that actually occur, in the
     * enum's own order.
     *
     * Derived from the history rather than from [ExerciseType.entries], because
     * a filter chip for a sport the user has never recorded is a chip that can
     * only ever produce an empty list. Someone who has never skied should not
     * have to read the word.
     */
    val activityTypes: StateFlow<List<ExerciseType>> = sessions
        .map { list -> ExerciseType.entries.filter { type -> list.any { it.exerciseType == type } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Which activity the recordings list is restricted to, or null for all of
     * them.
     *
     * Here rather than in the screen so that it survives a trip into a recording
     * and back — the list is restored to where it was scrolled (A.13), and
     * coming back to the same scroll position in a differently filtered list
     * would be worse than not restoring it at all. Not persisted across a
     * process death on purpose: a filter the user cannot see the cause of is a
     * list with recordings missing from it.
     */
    private val _activityFilter = MutableStateFlow<ExerciseType?>(null)

    /**
     * The filter as it actually applies: what the user picked, unless that
     * activity has since stopped occurring.
     *
     * Delete the last ski tour while the list is filtered to skiing and the
     * alternative is an empty list, no chip selected, and — once only one
     * activity is left and the row hides itself — no way back out of a state
     * the user cannot see. Derived rather than corrected by a collector, so
     * that nothing has to be subscribed for the correction to have happened.
     */
    val activityFilter: StateFlow<ExerciseType?> =
        combine(_activityFilter, activityTypes) { picked, types -> picked?.takeIf { it in types } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** The recordings the list shows: [sessions] through [activityFilter]. */
    val filteredSessions: StateFlow<List<SessionSummary>> =
        combine(sessions, activityFilter) { list, filter ->
            if (filter == null) list else list.filter { it.exerciseType == filter }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The heart rate the intensity bands are measured against.
     *
     * Watched rather than read once, so changing it in settings recolours every
     * chart that is already on screen. The default stands in until the user says
     * otherwise — see [Tuning.DEFAULT_PEAK_HR_BPM] for why it is a placeholder
     * rather than a calculation.
     */
    val peakHr: StateFlow<Int> = db.meta().watch(de.hecate.phone.db.Settings.PEAK_HR_BPM)
        .map { rows -> rows.firstOrNull()?.toIntOrNull() ?: Tuning.DEFAULT_PEAK_HR_BPM }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Tuning.DEFAULT_PEAK_HR_BPM)

    val routes: StateFlow<List<RouteSummary>> = db.routes().summaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * The heatmap, and the geometry of the routes it accepted (US-8).
     *
     * Read from the row `ClusterWorker` writes rather than computed here: it is
     * O(samples) over the entire history, which is not work a screen does when
     * you visit it. Watched rather than fetched so the picture refreshes itself
     * when a re-clustering finishes.
     *
     * Only the routes the heatmap kept are drawn. Taking the route list instead
     * would put a line on the picture for a route the raster deliberately
     * excluded, in an extent that was computed without it.
     */
    val heatmap: StateFlow<HeatmapState?> = db.heatmap().watch()
        .map { row ->
            val grid = row?.let {
                runCatching { HeatmapCodec.readGzip(ByteArrayInputStream(it.payload)) }.getOrNull()
            } ?: return@map null
            HeatmapState(
                grid = grid,
                routes = grid.routeIds.mapNotNull { id ->
                    db.routes().points(id).takeIf { it.size >= 2 }?.map { LatLon(it.lat, it.lon) }
                },
            )
        }
        // After the map, not before it: `flowOn` moves what is *upstream* of it,
        // and the work worth moving is the decoding. At a fixed cell size the
        // stored raster is millions of cells, and inflating and parsing it is
        // real work rather than the microseconds a 192x108 grid used to be.
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /**
     * Whether a re-clustering is queued or running.
     *
     * Clustering is O(n²) over every recording (§2.6) and on a real history it
     * takes long enough that a button which appears to do nothing is the natural
     * reading. WorkManager already knows; this just asks it.
     */
    val clustering: StateFlow<Boolean> =
        WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(Jobs.CLUSTER)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Which recordings stand out on their own route, keyed by recording id.
     *
     * Recomputed from the list itself rather than stored: it is a property of the
     * comparison, not of the recording, so every ingest and every re-clustering
     * changes it for rows that did not themselves change.
     */
    val standouts: StateFlow<Map<String, Standout>> = sessions
        .map(Standouts::compute)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun show(screen: Screen) {
        val current = _screen.value
        if (screen == current) return
        _backStack.value = (_backStack.value + current).takeLast(BACK_STACK_LIMIT)
        enter(screen)
    }

    /** True when back was handled here; false leaves it to the system, which is
     *  what closes the app from the first screen the user saw. */
    fun back(): Boolean {
        val stack = _backStack.value
        val previous = stack.lastOrNull() ?: return false
        _backStack.value = stack.dropLast(1)
        enter(previous)
        return true
    }

    /** Go to a screen without recording where we came from — for the cases where
     *  the screen we are leaving has just ceased to exist. */
    private fun enter(screen: Screen) {
        _screen.value = screen
        when (screen) {
            is Screen.RouteDetail -> loadRoute(screen.id)
            is Screen.SessionDetail -> loadSession(screen.id)
            else -> Unit
        }
    }

    fun clearStatus() {
        _status.value = null
    }

    fun importTcx(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        _busy.value = true
        val report: TcxImporter.Report = AppGraph.importer.import(uris)
        _busy.value = false
        _status.value = buildString {
            append("${report.imported} importiert")
            if (report.duplicates > 0) append(", ${report.duplicates} Duplikate")
            if (report.failed > 0) append(", ${report.failed} fehlgeschlagen")
            // The counts alone leave the user with no way to find out what was
            // wrong with a file, and the reasons were being computed and dropped.
            // It matters more than it used to: a sport this app does not record
            // is now refused rather than quietly filed as a run, so "1
            // fehlgeschlagen" is the only sign that a ski tour did not come in.
            report.messages.firstOrNull()?.let { first ->
                append(" — ").append(first)
                if (report.messages.size > 1) append(" (+${report.messages.size - 1})")
            }
        }
    }

    /**
     * §1.3 of the open points: write the whole database to a file the user picked.
     * What happens to that file afterwards — Drive, a cable, nothing — is theirs
     * to decide; the app neither knows nor asks (NFR-7).
     */
    fun exportArchive(uri: Uri) = viewModelScope.launch {
        _busy.value = true
        val result = runCatching { AppGraph.archive.export(uri) }
        _busy.value = false
        _status.value = result.fold(
            onSuccess = { r ->
                "Gesichert: ${r.sessions} Aufzeichnungen, ${r.routes} Routen (${Format.fileSize(r.bytes)})"
            },
            onFailure = { "Sicherung fehlgeschlagen: ${it.message ?: "unbekannter Fehler"}" },
        )
    }

    /**
     * Read a backup back in. Merges: anything already present is left alone, so
     * this can never cost the user data they still have.
     */
    fun restoreArchive(uri: Uri) = viewModelScope.launch {
        _busy.value = true
        val result = runCatching { AppGraph.archive.restore(uri) }
        _busy.value = false
        _status.value = result.fold(
            onSuccess = { r: Archive.RestoreReport ->
                buildString {
                    append("${r.sessionsRestored} Aufzeichnungen wiederhergestellt")
                    if (r.routesRestored > 0) append(", ${r.routesRestored} Routen")
                    if (r.sessionsSkipped > 0) append(", ${r.sessionsSkipped} schon vorhanden")
                    if (r.failed > 0) append(", ${r.failed} fehlgeschlagen")
                }
            },
            onFailure = { "Wiederherstellen fehlgeschlagen: ${it.message ?: "unbekannter Fehler"}" },
        )
    }

    fun rename(routeId: Long, name: String) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        db.routes().rename(routeId, name.trim())
        loadRoute(routeId)
        _status.value = "Umbenannt"
    }

    /**
     * Set the user's ranking for a route, −2..2 (US-4 manual override).
     *
     * Takes effect on the watch with the next bundle push, so the bundle is
     * rebuilt immediately rather than waiting for the next workout to be
     * ingested — otherwise changing a priority would appear to do nothing until
     * the next run, which is the run it was meant to affect.
     */
    fun setPriority(routeId: Long, priority: Int) = viewModelScope.launch {
        val clamped = priority.coerceIn(Tuning.MIN_ROUTE_PRIORITY, Tuning.MAX_ROUTE_PRIORITY)
        db.routes().setPriority(routeId, clamped)
        loadRoute(routeId)
        Jobs.enqueueClustering(getApplication())
        _status.value = "Priorität ${Format.priority(clamped)}"
    }

    /**
     * The heart rate the intensity bands are measured against.
     *
     * Clamped here as well as filtered in the field, because the range is the
     * rule and a rule enforced only by the one screen that happens to call this
     * is not enforced. Nothing stored changes either way; this only decides
     * which colour a stretch of a chart is drawn in.
     */
    fun setPeakHr(bpm: Int) = viewModelScope.launch {
        val clamped = bpm.coerceIn(Tuning.MIN_PEAK_HR_BPM, Tuning.MAX_PEAK_HR_BPM)
        db.meta().put(
            de.hecate.phone.db.MetaEntity(de.hecate.phone.db.Settings.PEAK_HR_BPM, clamped.toString()),
        )
        _status.value = "Maximalpuls $clamped bpm"
    }

    /** Restrict the recordings list to one activity, or to all of them. */
    fun setActivityFilter(type: ExerciseType?) {
        _activityFilter.value = type
    }

    fun recluster() = viewModelScope.launch {
        Jobs.enqueueClustering(getApplication())
        _status.value = "Neu-Clustering gestartet"
    }

    /**
     * Re-read whether Health Connect is installed and granted.
     *
     * Called whenever settings comes into view and after the permission sheet
     * returns, because both the grant and its removal can happen outside this app
     * — in Health Connect's own settings — and the switch must not claim
     * otherwise.
     */
    fun refreshHealthConnect() = viewModelScope.launch {
        val hc = AppGraph.healthConnect
        _healthConnect.value = when {
            hc.availability == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                HealthConnectState.UPDATE_REQUIRED
            !hc.isAvailable -> HealthConnectState.UNAVAILABLE
            hc.hasPermissions() -> HealthConnectState.CONNECTED
            else -> HealthConnectState.DISCONNECTED
        }
    }

    fun disconnectHealthConnect() = viewModelScope.launch {
        val ok = AppGraph.healthConnect.revokeAll()
        refreshHealthConnect()
        _status.value =
            if (ok) "Health Connect getrennt"
            else "Health Connect konnte nicht getrennt werden"
    }

    fun retryHealthConnect() = viewModelScope.launch {
        Jobs.enqueueHealthConnect(getApplication())
        _status.value = "Health-Connect-Schreiben angestoßen"
    }

    fun deleteSession(id: String) = viewModelScope.launch {
        db.sessions().delete(id)
        // Its detail screen is now a screen about nothing, so it must not be
        // reachable by pressing back either.
        _backStack.value = _backStack.value.filterNot { it == Screen.SessionDetail(id) }
        enter(Screen.Recordings)
        Jobs.enqueueClustering(getApplication())
        _status.value = "Aufzeichnung gelöscht"
    }

    private fun loadRoute(routeId: Long) = viewModelScope.launch {
        val dao = db.routes()
        val route = dao.byId(routeId) ?: return@launch
        val points = dao.points(routeId)
        val durations = dao.memberDurations(routeId)
        val members = dao.memberSessions(routeId)
        val profileRows = dao.profile(routeId)

        // Straight off the profile bins, which are Tuning.RESAMPLE_M apart by
        // construction, so bin index is distance along the route.
        val medianHr = profileRows.map { it.medianHr }
        val routeElevation = Elevation.ofBins(
            profileRows.map { it.medianAltM },
            Tuning.RESAMPLE_M,
            medianHr,
        )

        _routeDetail.value = RouteDetail(
            id = route.id,
            name = route.name,
            exerciseType = route.exerciseType,
            lengthM = route.lengthM,
            memberCount = route.memberCount,
            priority = route.priority,
            // The route's heart rate is the per-bin median over its members, and
            // a route point's distance along the route is what says which bin it
            // is in — the geometry is resampled at exactly the profile's bin
            // width, so this is a lookup rather than an interpolation.
            geometry = points.map { p ->
                TrackPoint(
                    LatLon(p.lat, p.lon),
                    medianHr.getOrNull((p.cumDistM / Tuning.RESAMPLE_M).toInt()),
                )
            },
            medianDurationMs = durations.median(),
            bestDurationMs = durations.minOrNull(),
            worstDurationMs = durations.maxOrNull(),
            elevation = Elevation.simplify(routeElevation, CHART_POINTS),
            elevationStats = Elevation.stats(routeElevation),
            sessions = members,
        )
    }

    private fun loadSession(id: String) = viewModelScope.launch {
        val dao = db.sessions()
        val entity = dao.byId(id) ?: return@launch
        val samples = dao.samples(id)
        val route = entity.matchedRouteId?.let { db.routes().byId(it) }
        val log = dao.matchLog(id)?.let {
            runCatching { BundleCodec.decodeMatchLog(it.payload.toString(Charsets.UTF_8)) }.getOrDefault(emptyList())
        } ?: emptyList()
        val events = dao.events(id)?.let {
            runCatching { BundleCodec.decodeEvents(it.payload.toString(Charsets.UTF_8)) }.getOrDefault(emptyList())
        } ?: emptyList()

        // Paused samples are dropped once, here, so that the track drawing, the
        // altitude profile and the smoothed heart rate all index the same series
        // — the colours would otherwise slide along the line by however many
        // samples a level crossing took.
        val moving = samples.filterNot { it.paused }

        // Heart rate is smoothed before anything reads it, and for a reason that
        // has nothing to do with the sensor being noisy: the screen quantises it
        // into four bands, so a steady effort sitting on a boundary would cross
        // it every few seconds and the drawing would alternate between two
        // colours every few pixels. See Tuning.HR_MEDIAN_WINDOW.
        val heartRate = Series.rollingMedian(
            DoubleArray(moving.size) { i ->
                moving[i].hrBpm?.takeIf { it > 0 }?.toDouble() ?: Double.NaN
            },
            Tuning.HR_MEDIAN_WINDOW,
        )
        fun hrAt(i: Int): Double? = heartRate[i].takeIf { !it.isNaN() }

        // Only the fields the profile reads; the rest of a Sample is of no
        // interest to a chart of height against distance.
        val sessionElevation = Elevation.of(
            moving.mapIndexed { i, it ->
                de.hecate.core.model.Sample(
                    tOffsetMs = it.tOffsetMs,
                    activeMs = it.activeMs,
                    lat = it.lat,
                    lon = it.lon,
                    altM = it.altM,
                    hrBpm = hrAt(i)?.roundToInt(),
                    paused = it.paused,
                )
            },
        )

        _sessionDetail.value = SessionDetail(
            summary = SessionSummary(
                id = entity.id,
                startedAtUtc = entity.startedAtUtc,
                tzOffsetMinutes = entity.tzOffsetMinutes,
                exerciseType = entity.exerciseType,
                activeDurationMs = entity.activeDurationMs,
                totalDistanceM = entity.totalDistanceM,
                source = entity.source,
                hcWriteState = entity.hcWriteState,
                matchedRouteId = entity.matchedRouteId,
                routeName = route?.name,
                avgHr = entity.avgHr,
            ),
            geometry = moving.mapIndexed { i, it -> TrackPoint(LatLon(it.lat, it.lon), hrAt(i)) },
            routeName = route?.name,
            coverage = entity.matchCoverage,
            committedAtM = entity.committedAtM,
            hcState = entity.hcWriteState,
            hcError = entity.hcError,
            matchLogSize = log.size,
            committedByPriority = log.lastOrNull {
                it.event == de.hecate.core.model.MatchEvent.COMMIT ||
                    it.event == de.hecate.core.model.MatchEvent.COMMIT_BY_PRIORITY
            }?.let { it.event == de.hecate.core.model.MatchEvent.COMMIT_BY_PRIORITY },
            // Only the interesting lines: commits, demotions, deviations.
            matchEvents = log.filter { it.event != de.hecate.core.model.MatchEvent.SAMPLE }.take(50),
            // Uncapped on purpose: a handful per session, and the one that
            // matters is usually the one you did not expect to be there.
            sessionEvents = events,
            elevation = Elevation.simplify(sessionElevation, CHART_POINTS),
            elevationStats = Elevation.stats(sessionElevation),
        )
    }

    private fun List<Long>.median(): Long? {
        if (isEmpty()) return null
        val s = sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    companion object {
        private const val BACK_STACK_LIMIT = 16

        /** Points kept for an elevation chart. A phone is a few hundred pixels
         *  wide and an hour's ride is thousands of samples — see
         *  [Elevation.simplify], which keeps the peaks rather than averaging
         *  them away. */
        private const val CHART_POINTS = 400
    }
}
