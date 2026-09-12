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
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.SessionEvent
import de.hecate.phone.AppGraph
import de.hecate.core.archive.Archive
import de.hecate.phone.db.RouteSummary
import de.hecate.phone.db.SessionSummary
import de.hecate.phone.tcx.TcxImporter
import de.hecate.phone.work.Jobs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    val exerciseType: String,
    val lengthM: Double,
    val memberCount: Int,
    val geometry: List<LatLon>,
    val medianDurationMs: Long?,
    val bestDurationMs: Long?,
    val worstDurationMs: Long?,
    val priority: Int,
    val sessions: List<SessionSummary>,
    /** Per-kilometre median/best/worst splits, in seconds. */
    val splits: List<Split>,
) {
    data class Split(val km: Int, val medianS: Double, val bestS: Double, val worstS: Double)
}

/** Everything the session detail screen renders (US-8). */
data class SessionDetail(
    val summary: SessionSummary,
    val geometry: List<LatLon>,
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
    /** Whether the watch worked the route out or assumed it from the ranking. */
    val committedByPriority: Boolean,
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
     * Set the user's ranking for a route, 1..10 (US-4 manual override).
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
        _status.value = "Priorität $clamped"
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

        val splits = buildSplits(profileRows.map { it.medianCumTimeS }, members, routeId)

        _routeDetail.value = RouteDetail(
            id = route.id,
            name = route.name,
            exerciseType = route.exerciseType,
            lengthM = route.lengthM,
            memberCount = route.memberCount,
            priority = route.priority,
            geometry = points.map { LatLon(it.lat, it.lon) },
            medianDurationMs = durations.median(),
            bestDurationMs = durations.minOrNull(),
            worstDurationMs = durations.maxOrNull(),
            sessions = members,
            splits = splits,
        )
    }

    /**
     * Per-kilometre splits (US-8). The median column comes from the route
     * profile; best and worst are taken over the members' own crossings of the
     * same kilometre marks.
     */
    private suspend fun buildSplits(
        medianCum: List<Double>,
        members: List<SessionSummary>,
        routeId: Long,
    ): List<RouteDetail.Split> {
        if (medianCum.isEmpty()) return emptyList()
        val binsPerKm = (1000.0 / Tuning.RESAMPLE_M).toInt()
        val kmCount = medianCum.size / binsPerKm
        if (kmCount == 0) return emptyList()

        // Each member's cumulative time at each kilometre mark, reconstructed by
        // projecting the member onto the route geometry once.
        val perMember = ArrayList<DoubleArray>()
        val geometry = db.routes().points(routeId)
        if (geometry.size >= 2) {
            val poly = de.hecate.core.cluster.BundleBuilder.toPolyline(
                geometry.map { de.hecate.core.model.RoutePoint(it.lat, it.lon, it.cumDistM) },
            )
            for (m in members) {
                val samples = db.sessions().samples(m.id)
                if (samples.size < 2) continue
                val marks = DoubleArray(kmCount + 1) { Double.NaN }
                var lastPos = -1.0
                for (s in samples) {
                    if (s.paused) continue
                    val p = LatLon(s.lat, s.lon)
                    val hit = if (lastPos < 0) poly.projectAll(p) else poly.projectWindow(p, lastPos, lastPos + Tuning.SEARCH_WINDOW_M)
                    if (hit.distanceM > Tuning.CLUSTER_TOL_M * 2) continue
                    val pos = maxOf(hit.positionM, lastPos)
                    lastPos = pos
                    val km = (pos / 1000.0).toInt()
                    if (km in 0..kmCount && marks[km].isNaN()) marks[km] = s.activeMs / 1000.0
                }
                perMember.add(marks)
            }
        }

        return (1..kmCount).map { km ->
            val medianSplit = medianCum.getOrElse(km * binsPerKm) { medianCum.last() } -
                medianCum.getOrElse((km - 1) * binsPerKm) { 0.0 }
            val memberSplits = perMember.mapNotNull { marks ->
                val a = marks.getOrNull(km - 1)
                val b = marks.getOrNull(km)
                if (a == null || b == null || a.isNaN() || b.isNaN() || b <= a) null else b - a
            }
            RouteDetail.Split(
                km = km,
                medianS = medianSplit,
                bestS = memberSplits.minOrNull() ?: medianSplit,
                worstS = memberSplits.maxOrNull() ?: medianSplit,
            )
        }
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
            geometry = samples.filterNot { it.paused }.map { LatLon(it.lat, it.lon) },
            routeName = route?.name,
            coverage = entity.matchCoverage,
            committedAtM = entity.committedAtM,
            hcState = entity.hcWriteState,
            hcError = entity.hcError,
            matchLogSize = log.size,
            committedByPriority = log.lastOrNull {
                it.event == de.hecate.core.model.MatchEvent.COMMIT ||
                    it.event == de.hecate.core.model.MatchEvent.COMMIT_BY_PRIORITY
            }?.event == de.hecate.core.model.MatchEvent.COMMIT_BY_PRIORITY,
            // Only the interesting lines: commits, demotions, deviations.
            matchEvents = log.filter { it.event != de.hecate.core.model.MatchEvent.SAMPLE }.take(50),
            // Uncapped on purpose: a handful per session, and the one that
            // matters is usually the one you did not expect to be there.
            sessionEvents = events,
        )
    }

    private fun List<Long>.median(): Long? {
        if (isEmpty()) return null
        val s = sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    companion object {
        private const val BACK_STACK_LIMIT = 16
    }
}
