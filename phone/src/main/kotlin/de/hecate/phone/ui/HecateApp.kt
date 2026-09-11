package de.hecate.phone.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hecate.phone.db.HcWriteState
import de.hecate.phone.db.RouteSummary
import de.hecate.phone.db.SessionSummary

/**
 * §5.4: Sessions list → Session detail; Routes list → Route detail.
 *
 * Four screens, one state enum, no navigation library (§4). The bottom bar
 * switches between the two lists; the system back button leaves a detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HecateApp(
    vm: HecateViewModel,
    onPickTcx: () -> Unit,
    onRequestHealthConnect: () -> Unit,
) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(status) {
        status?.let {
            snackbar.showSnackbar(it)
            vm.clearStatus()
        }
    }

    val inDetail = screen is Screen.RouteDetail || screen is Screen.SessionDetail
    BackHandler(enabled = inDetail) { vm.back() }

    MaterialTheme {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(titleOf(screen)) },
                    navigationIcon = {
                        if (inDetail) {
                            IconButton(onClick = { vm.back() }) { Text("‹", fontSize = 24.sp) }
                        }
                    },
                    actions = {
                        when (screen) {
                            is Screen.Sessions -> {
                                TextButton(onClick = onPickTcx) { Text("Import") }
                                TextButton(onClick = onRequestHealthConnect) { Text("HC") }
                            }
                            is Screen.Routes -> TextButton(onClick = { vm.recluster() }) { Text("Neu") }
                            else -> Unit
                        }
                    },
                )
            },
            bottomBar = {
                if (!inDetail) {
                    NavigationBar {
                        NavigationBarItem(
                            selected = screen is Screen.Sessions,
                            onClick = { vm.show(Screen.Sessions) },
                            icon = { Text("≡") },
                            label = { Text("Sitzungen") },
                        )
                        NavigationBarItem(
                            selected = screen is Screen.Routes,
                            onClick = { vm.show(Screen.Routes) },
                            icon = { Text("↗") },
                            label = { Text("Routen") },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).fillMaxSize()) {
                when (screen) {
                    is Screen.Sessions -> SessionsScreen(vm)
                    is Screen.Routes -> RoutesScreen(vm)
                    is Screen.SessionDetail -> SessionDetailScreen(vm)
                    is Screen.RouteDetail -> RouteDetailScreen(vm)
                }
                if (busy) {
                    Box(
                        Modifier.fillMaxSize().background(Color(0x80000000)),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                }
            }
        }
    }
}

private fun titleOf(screen: Screen): String = when (screen) {
    is Screen.Sessions -> "Sitzungen"
    is Screen.Routes -> "Routen"
    is Screen.SessionDetail -> "Sitzung"
    is Screen.RouteDetail -> "Route"
}

// ------------------------------------------------------------------- sessions

@Composable
private fun SessionsScreen(vm: HecateViewModel) {
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    if (sessions.isEmpty()) {
        Empty(
            "Noch keine Sitzungen.\n\nAufzeichnungen der Uhr erscheinen hier automatisch. " +
                "Über „Import“ lassen sich TCX-Dateien aus Google Health einlesen, " +
                "damit Routen sofort gelernt werden.",
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(sessions, key = { it.id }) { s ->
            SessionRow(s) { vm.show(Screen.SessionDetail(s.id)) }
            HorizontalDivider()
        }
    }
}

@Composable
private fun SessionRow(s: SessionSummary, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(Format.dateTime(s.startedAtUtc, s.tzOffsetMinutes), fontWeight = FontWeight.Medium)
            Text(Format.distance(s.totalDistanceM))
        }
        Spacer(Modifier.height(2.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                buildString {
                    append(Format.exerciseType(s.exerciseType))
                    append(" · ")
                    append(Format.duration(s.activeDurationMs))
                    s.avgHr?.let { append(" · ${Format.hr(it)} bpm") }
                },
                fontSize = 13.sp,
                color = Color.Gray,
            )
            Text(
                s.routeName ?: if (s.source == "IMPORT_TCX") "importiert" else "keine Route",
                fontSize = 13.sp,
                color = if (s.routeName != null) Color(0xFF00796B) else Color.Gray,
            )
        }
    }
}

@Composable
private fun SessionDetailScreen(vm: HecateViewModel) {
    val detail by vm.sessionDetail.collectAsStateWithLifecycle()
    val d = detail ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(Format.dateTime(d.summary.startedAtUtc, d.summary.tzOffsetMinutes), fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        PolylineView(d.geometry)
        Spacer(Modifier.height(12.dp))

        KeyValue("Art", Format.exerciseType(d.summary.exerciseType))
        KeyValue("Dauer (aktiv)", Format.duration(d.summary.activeDurationMs))
        KeyValue("Distanz", Format.distance(d.summary.totalDistanceM))
        KeyValue("Ø Herzfrequenz", "${Format.hr(d.summary.avgHr)} bpm")
        KeyValue(
            "Ø Pace",
            Format.pace(
                if (d.summary.totalDistanceM > 0) {
                    (d.summary.activeDurationMs / 1000.0) / (d.summary.totalDistanceM / 1000.0)
                } else {
                    null
                },
            ) + " /km",
        )
        KeyValue("Quelle", if (d.summary.source == "IMPORT_TCX") "TCX-Import" else "Uhr")

        Spacer(Modifier.height(12.dp))
        Text("Zuordnung", fontWeight = FontWeight.Bold)
        KeyValue("Route", d.routeName ?: "keine")
        KeyValue("Festlegung bei", d.committedAtM?.let { Format.distance(it) } ?: "–")
        KeyValue("Deckung", d.coverage?.let { String.format(java.util.Locale.GERMANY, "%.2f", it) } ?: "–")
        KeyValue("Match-Log", "${d.matchLogSize} Einträge")

        if (d.matchEvents.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    for (e in d.matchEvents) {
                        Text(
                            "%6.0fs  %-16s %s".format(
                                e.tOffsetMs / 1000.0, e.event.name, e.routeId?.toString() ?: "",
                            ),
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("Health Connect", fontWeight = FontWeight.Bold)
        KeyValue("Status", Format.hcState(d.hcState))
        d.hcError?.let { KeyValue("Fehler", it) }
        if (d.hcState == HcWriteState.FAILED || d.hcState == HcWriteState.PENDING) {
            TextButton(onClick = { vm.retryHealthConnect() }) { Text("Erneut schreiben") }
        }

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { vm.deleteSession(d.summary.id) }) {
            Text("Sitzung löschen", color = Color(0xFFC62828))
        }
        Spacer(Modifier.height(24.dp))
    }
}

// --------------------------------------------------------------------- routes

@Composable
private fun RoutesScreen(vm: HecateViewModel) {
    val routes by vm.routes.collectAsStateWithLifecycle()
    if (routes.isEmpty()) {
        Empty(
            "Noch keine Routen.\n\nEine Route entsteht, sobald drei ähnliche Aufzeichnungen " +
                "vorliegen. Bis dahin hilft der TCX-Import auf der Sitzungsseite.",
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(routes, key = { it.id }) { r ->
            RouteRow(r) { vm.show(Screen.RouteDetail(r.id)) }
            HorizontalDivider()
        }
    }
}

@Composable
private fun RouteRow(r: RouteSummary, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp, 12.dp)) {
        Text(r.name, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(2.dp))
        Text(
            "${Format.exerciseType(r.exerciseType)} · ${Format.distance(r.lengthM)} · ${r.memberCount} Aufzeichnungen",
            fontSize = 13.sp,
            color = Color.Gray,
        )
        Text(
            buildString {
                append("Median ")
                append(r.medianDurationMs?.let { Format.duration(it) } ?: "–")
                append(" · Beste ")
                append(r.bestDurationMs?.let { Format.duration(it) } ?: "–")
            },
            fontSize = 13.sp,
            color = Color.Gray,
        )
    }
}

@Composable
private fun RouteDetailScreen(vm: HecateViewModel) {
    val detail by vm.routeDetail.collectAsStateWithLifecycle()
    val d = detail ?: return
    var editing by remember { mutableStateOf(false) }
    var draft by remember(d.id) { mutableStateOf(d.name) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        if (editing) {
            Surface(Modifier.fillMaxWidth(), color = Color(0x11000000)) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(fontSize = 18.sp),
                    )
                    TextButton(onClick = { vm.rename(d.id, draft); editing = false }) { Text("OK") }
                }
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(d.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                TextButton(onClick = { editing = true }) { Text("Umbenennen") }
            }
        }

        Spacer(Modifier.height(8.dp))
        PolylineView(d.geometry)
        Spacer(Modifier.height(12.dp))

        KeyValue("Art", Format.exerciseType(d.exerciseType))
        KeyValue("Länge", Format.distance(d.lengthM))
        KeyValue("Aufzeichnungen", d.memberCount.toString())
        KeyValue("Median-Zeit", d.medianDurationMs?.let { Format.duration(it) } ?: "–")
        KeyValue("Beste Zeit", d.bestDurationMs?.let { Format.duration(it) } ?: "–")
        KeyValue("Schlechteste", d.worstDurationMs?.let { Format.duration(it) } ?: "–")

        if (d.splits.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text("Kilometer-Splits", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            MonoRow("km", "Median", "Beste", "Schlecht.", header = true)
            for (s in d.splits) {
                MonoRow(
                    s.km.toString(),
                    Format.pace(s.medianS),
                    Format.pace(s.bestS),
                    Format.pace(s.worstS),
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("Aufzeichnungen", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        MonoRow("Datum", "Zeit", "Dauer", "Ø HF", header = true)
        for (s in d.sessions) {
            Box(Modifier.clickable { vm.show(Screen.SessionDetail(s.id)) }) {
                MonoRow(
                    Format.date(s.startedAtUtc, s.tzOffsetMinutes),
                    Format.timeOfDay(s.startedAtUtc, s.tzOffsetMinutes),
                    Format.duration(s.activeDurationMs),
                    Format.hr(s.avgHr),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------- bits

@Composable
private fun KeyValue(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(key, color = Color.Gray, fontSize = 14.sp)
        Text(value, fontSize = 14.sp)
    }
}

@Composable
private fun MonoRow(a: String, b: String, c: String, d: String, header: Boolean = false) {
    val style = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        fontWeight = if (header) FontWeight.Bold else FontWeight.Normal,
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(a, style = style, modifier = Modifier.weight(1.3f))
        Text(b, style = style, modifier = Modifier.weight(1f))
        Text(c, style = style, modifier = Modifier.weight(1f))
        Text(d, style = style, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Empty(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Color.Gray, fontSize = 15.sp)
    }
}
