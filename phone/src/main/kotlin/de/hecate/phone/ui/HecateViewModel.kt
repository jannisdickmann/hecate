package de.hecate.phone.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.hecate.core.Tuning
import de.hecate.core.geo.LatLon
import de.hecate.core.io.BundleCodec
import de.hecate.core.model.MatchLogEntry
import de.hecate.phone.AppGraph
import de.hecate.phone.archive.Archive
import de.hecate.phone.db.RouteSummary
import de.hecate.phone.db.SessionSummary
import de.hecate.phone.tcx.TcxImporter
import de.hecate.phone.work.Jobs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which of the three screens is showing (§4: no Compose Navigation). */
sealed interface Screen {
    data object Sessions : Screen
    data object Routes : Screen
    data class SessionDetail(val id: String) : Screen
    data class RouteDetail(val id: Long) : Screen
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
    /** Whether the watch worked the route out or assumed it from the ranking. */
    val committedByPriority: Boolean,
)

/**
 * One view model for the whole phone app. Three screens do not need three.
 */
class HecateViewModel(app: Application) : AndroidViewModel(app) {

    private val db get() = AppGraph.db

    private val _screen = MutableStateFlow<Screen>(Screen.Sessions)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

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

    fun show(screen: Screen) {
        _screen.value = screen
        when (screen) {
            is Screen.RouteDetail -> loadRoute(screen.id)
            is Screen.SessionDetail -> loadSession(screen.id)
            else -> Unit
        }
    }

    fun back() {
        _screen.value = when (val s = _screen.value) {
            is Screen.RouteDetail -> Screen.Routes
            is Screen.SessionDetail -> Screen.Sessions
            else -> s
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
                "Gesichert: ${r.sessions} Sitzungen, ${r.routes} Routen (${Format.fileSize(r.bytes)})"
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
                    append("${r.sessionsRestored} Sitzungen wiederhergestellt")
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

    fun retryHealthConnect() = viewModelScope.launch {
        Jobs.enqueueHealthConnect(getApplication())
        _status.value = "Health-Connect-Schreiben angestoßen"
    }

    fun deleteSession(id: String) = viewModelScope.launch {
        db.sessions().delete(id)
        _screen.value = Screen.Sessions
        Jobs.enqueueClustering(getApplication())
        _status.value = "Sitzung gelöscht"
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
        )
    }

    private fun List<Long>.median(): Long? {
        if (isEmpty()) return null
        val s = sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
