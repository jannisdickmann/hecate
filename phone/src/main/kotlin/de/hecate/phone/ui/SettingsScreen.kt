package de.hecate.phone.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.hecate.core.Tuning
import de.hecate.core.map.KarteIndex
import de.hecate.core.map.TileStore
import de.hecate.core.model.Intensity
import de.hecate.core.update.AvailableUpdate
import de.hecate.core.weather.OpenMeteo
import de.hecate.phone.update.UpdateStatus

/** What the settings panel shows. */
data class SettingsState(
    val clustering: Boolean = false,
    val healthConnect: HealthConnectState = HealthConnectState.DISCONNECTED,
    val peakHr: Int = Tuning.DEFAULT_PEAK_HR_BPM,
    val weatherEnabled: Boolean = true,
    val weatherCount: Int = 0,
    val sessionCount: Int = 0,
    val weatherRunning: Boolean = false,
    val mapStats: TileStore.Stats = TileStore.Stats(0, 0, null, 0),
    val mapPrefetch: MapPrefetch? = null,
    val updates: UpdateStatus = UpdateStatus(),
)

/** What the settings panel can ask for. */
class SettingsActions(
    val onRecluster: () -> Unit = {},
    val onPeakHr: (Int) -> Unit = {},
    val onConnectHealth: () -> Unit = {},
    val onDisconnectHealth: () -> Unit = {},
    val onWeather: (Boolean) -> Unit = {},
    val onPrefetchMap: (restart: Boolean) -> Unit = {},
    val onImportMap: () -> Unit = {},
    val onDeleteMap: () -> Unit = {},
    val onPickTcx: () -> Unit = {},
    val onExportArchive: () -> Unit = {},
    val onRestoreArchive: () -> Unit = {},
    val onCheckUpdates: () -> Unit = {},
)

/**
 * Everything that is not history. A panel costs one slot in the bottom bar and
 * lets each action carry a sentence saying what it does.
 */
@Composable
fun SettingsScreen(
    vm: HecateViewModel,
    onPickTcx: () -> Unit,
    onExportArchive: () -> Unit,
    onRestoreArchive: () -> Unit,
    onImportMap: () -> Unit,
    onRequestHealthConnect: () -> Unit,
) {
    val clustering by vm.clustering.collectAsStateWithLifecycle()
    val hc by vm.healthConnect.collectAsStateWithLifecycle()
    val peakHr by vm.peakHr.collectAsStateWithLifecycle()
    val weatherEnabled by vm.weatherEnabled.collectAsStateWithLifecycle()
    val weatherCount by vm.weatherCount.collectAsStateWithLifecycle()
    val weatherRunning by vm.weatherRunning.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val mapStats by vm.mapStats.collectAsStateWithLifecycle()
    val prefetch by vm.mapPrefetch.collectAsStateWithLifecycle()
    val updates by vm.updates.collectAsStateWithLifecycle()

    // Both the grant and its removal can happen in Health Connect's own
    // settings, so the switch is re-read every time this panel appears.
    LaunchedEffect(Unit) { vm.refreshHealthConnect() }

    SettingsContent(
        SettingsState(clustering, hc, peakHr, weatherEnabled, weatherCount, sessions.size, weatherRunning, mapStats, prefetch, updates),
        SettingsActions(
            onRecluster = { vm.recluster() },
            onPeakHr = { vm.setPeakHr(it) },
            onConnectHealth = onRequestHealthConnect,
            onDisconnectHealth = { vm.disconnectHealthConnect() },
            onWeather = { vm.setWeatherEnabled(it) },
            onPrefetchMap = { vm.prefetchMap(restart = it) },
            onImportMap = onImportMap,
            onDeleteMap = { vm.deleteMap() },
            onPickTcx = onPickTcx,
            onExportArchive = onExportArchive,
            onRestoreArchive = onRestoreArchive,
            onCheckUpdates = { vm.checkForUpdates() },
        ),
    )
}

@Composable
fun SettingsContent(state: SettingsState, actions: SettingsActions) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = HecateDesign.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(HecateDesign.Gap),
    ) {
        Spacer(Modifier.height(4.dp))
        WeatherSettings(state, actions, Modifier.appear(0))
        SectionCard("Belastung", Figure.HEART, Modifier.appear(1)) { PeakHrField(state.peakHr, actions.onPeakHr) }
        SectionCard("Routen", Figure.ROUTE, Modifier.appear(2)) {
            Text("Routen neu berechnen", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Quiet(
                "Gruppiert alle Aufzeichnungen neu zu Routen. Nötig nach dem Einlesen einer Sicherung oder " +
                    "nach vielen Importen; sonst läuft es nach jeder neuen Aufzeichnung von selbst.",
            )
            Spacer(Modifier.height(12.dp))
            if (state.clustering) {
                // Gone while the work runs rather than disabled: the job is
                // enqueued with REPLACE, so a second tap would restart it.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Berechnung läuft…", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                FilledTonalButton(onClick = actions.onRecluster) {
                    Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Neu berechnen")
                }
            }
        }
        SectionCard("Health Connect", Figure.CALENDAR, Modifier.appear(3)) {
            val hc = state.healthConnect
            SwitchRow(
                "Nach Health Connect schreiben",
                when (hc) {
                    HealthConnectState.CONNECTED ->
                        "Abgeschlossene Aufzeichnungen erscheinen in Google Health. Ausschalten entzieht die " +
                            "Berechtigung; bereits geschriebene Einträge bleiben dort bestehen."
                    HealthConnectState.DISCONNECTED ->
                        "Aus. Einschalten fragt die Berechtigung ab — das muss Android selbst tun, die App " +
                            "kann sie sich nicht geben."
                    HealthConnectState.UPDATE_REQUIRED -> "Health Connect ist zu alt und muss erst aktualisiert werden."
                    HealthConnectState.UNAVAILABLE -> "Health Connect ist auf diesem Gerät nicht verfügbar."
                },
                checked = hc == HealthConnectState.CONNECTED,
                enabled = hc == HealthConnectState.CONNECTED || hc == HealthConnectState.DISCONNECTED,
                onChange = { on -> if (on) actions.onConnectHealth() else actions.onDisconnectHealth() },
            )
        }
        SectionCard("Karte", Figure.MOUNTAIN, Modifier.appear(4)) { MapDataSettings(state, actions) }
        SectionCard("Daten", Figure.SPLITS, Modifier.appear(5)) {
            SettingsAction(
                "TCX importieren",
                "Liest Trainings aus einem Google-Health-Export ein, damit Routen sofort gelernt werden statt erst nach drei neuen Läufen.",
                actions.onPickTcx,
            )
            SettingsDivider()
            SettingsAction(
                "Sicherung exportieren",
                "Schreibt die gesamte Datenbank in eine Datei. Namen und Prioritäten sind das Einzige darin, was sonst nirgends steht.",
                actions.onExportArchive,
            )
            SettingsDivider()
            SettingsAction(
                "Sicherung einlesen",
                "Führt eine Sicherung mit dem zusammen, was schon da ist. Vorhandenes bleibt unangetastet. " +
                    "Danach lohnt sich „Routen neu berechnen“.",
                actions.onRestoreArchive,
            )
        }
        SectionCard("Version", Figure.UPDATE, Modifier.appear(6)) { UpdateSettings(state.updates, actions) }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * What is installed on phone and watch, and whether GitHub has anything newer
 * (A.34). The notification says the same once per release; this says it for as
 * long as it is true, and is the way to the release page when notifications
 * are off. The watch has no network and no browser, so its row is the phone's
 * to show.
 */
@Composable
private fun UpdateSettings(status: UpdateStatus, actions: SettingsActions) {
    val uri = LocalUriHandler.current
    VersionRow(
        "Telefon",
        status.phone?.name ?: "–",
        status.phoneUpdate,
        upToDate = status.checkedAtUtcMs != null,
        onOpen = { uri.openUri(it) },
    )
    SettingsDivider()
    VersionRow(
        "Uhr",
        status.watch?.name ?: "noch nicht gemeldet",
        status.watchUpdate ?: status.latestWatch.takeIf { status.watch == null },
        upToDate = status.checkedAtUtcMs != null && status.watch != null,
        onOpen = { uri.openUri(it) },
    )
    SettingsDivider()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Quiet(
            when {
                status.checking -> "Wird geprüft…"
                status.failed -> "GitHub war nicht erreichbar. Nächster Versuch beim nächsten Start oder morgen."
                status.checkedAtUtcMs != null -> "Zuletzt geprüft: ${Format.dateTime(status.checkedAtUtcMs, localOffsetMinutes(status.checkedAtUtcMs))}. Gefragt wird GitHub, " +
                    "einmal am Tag und bei jedem Start; gesendet wird dabei nichts über dich."
                else -> "Gefragt wird GitHub, einmal am Tag und bei jedem Start; gesendet wird dabei nichts über dich."
            },
            Modifier.weight(1f),
        )
        if (status.checking) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = actions.onCheckUpdates) { Text("Prüfen") }
        }
    }
}

private fun localOffsetMinutes(utcMs: Long): Int = java.util.TimeZone.getDefault().getOffset(utcMs) / 60_000

@Composable
private fun VersionRow(device: String, installed: String, update: AvailableUpdate?, upToDate: Boolean, onOpen: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(device, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Quiet(
                buildString {
                    append(installed)
                    when {
                        update != null -> append(" · ${update.versionName} ist verfügbar")
                        upToDate -> append(" · aktuell")
                    }
                },
            )
        }
        if (update != null) {
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = { onOpen(update.pageUrl) }) { Text("Öffnen") }
        }
    }
}

/**
 * The weather backfill (A.33): a switch, because this is the one feature that
 * sends something about the user's movements off the phone, and saying so
 * plainly — what is sent, to whom, and what is not.
 */
@Composable
private fun WeatherSettings(state: SettingsState, actions: SettingsActions, modifier: Modifier) {
    SectionCard("Wetter", Figure.WEATHER, modifier) {
        SwitchRow(
            "Wetter zu Aufzeichnungen holen",
            "Alle 10 Minuten Wettersymbol, Temperatur und Niederschlag, nachträglich von Open-Meteo.",
            checked = state.weatherEnabled,
            onChange = actions.onWeather,
        )
        if (state.sessionCount > 0) {
            Spacer(Modifier.height(14.dp))
            val f = state.weatherCount.toFloat() / state.sessionCount
            LinearProgressIndicator(
                progress = { f.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                drawStopIndicator = {},
            )
            Spacer(Modifier.height(6.dp))
            Quiet(
                "${state.weatherCount} von ${state.sessionCount} Aufzeichnungen mit Wetter" +
                    when {
                        state.weatherRunning -> " · wird geholt…"
                        !state.weatherEnabled -> ""
                        state.weatherCount < state.sessionCount -> " · der Rest folgt, sobald das Telefon online ist"
                        else -> ""
                    },
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth().clip(HecateDesign.InnerShape)
                .background(MaterialTheme.colorScheme.surfaceContainer).padding(12.dp),
        ) {
            Quiet(
                "Gesendet werden nur das Datum und auf etwa 2 km gerundete Orte — nie eine Uhrzeit, " +
                    "nie die Spur selbst. ${OpenMeteo.ATTRIBUTION}.",
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Quiet(body)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingsDivider() = HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))

/**
 * What the app knows of the ground under the tracks, and the three things to
 * do about it (A.29). No on/off switch: deleting the data is the off switch.
 * The attribution line is the ODbL obligation, not decoration.
 */
@Composable
private fun MapDataSettings(state: SettingsState, actions: SettingsActions) {
    val stats = state.mapStats
    var confirmDelete by remember { mutableStateOf(false) }
    Text("Kartendaten", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(2.dp))
    if (stats.isEmpty) {
        Quiet("Noch keine Kartendaten. Streckenbilder werden auf leerem Grund gezeichnet; beim Öffnen einer Strecke lädt die App die Umgebung nach.")
    } else {
        Quiet(
            buildString {
                append(plural(stats.tiles, "Kachel", "Kacheln"))
                append(" · ${Format.fileSize(stats.bytes)}")
                if (stats.regions > 0) append(" · ${plural(stats.regions, "Gebiet", "Gebiete")}")
                stats.newestUtcMs?.let { append(" · zuletzt geladen am ${Format.date(it, 0)}") }
            },
        )
    }
    Spacer(Modifier.height(2.dp))
    Quiet(KarteIndex.ATTRIBUTION)
    SettingsDivider()
    val running = state.mapPrefetch
    if (running != null) {
        // Waiting and downloading are different things to be told.
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(
                when {
                    !running.running && running.interrupted -> "Unterbrochen, wird fortgesetzt…"
                    !running.running -> "Wartet auf WLAN…"
                    running.blocks == 0 -> "Kartendaten werden geladen…"
                    else -> "Kartendaten werden geladen · " + (if (running.fine) "Details" else "Überblick") +
                        " ${minOf(running.block + 1, running.blocks)}/${running.blocks}"
                },
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (!running.running) TextButton(onClick = { actions.onPrefetchMap(true) }) { Text("Jetzt starten") }
        }
    } else {
        SettingsAction(
            "Karte vorladen",
            "Lädt die Umgebung aller Aufzeichnungen im Hintergrund, nur über WLAN. Danach funktioniert die Karte auch ohne Empfang.",
            { actions.onPrefetchMap(false) },
        )
    }
    SettingsDivider()
    SettingsAction("Karte einlesen", "Übernimmt eine am Rechner erzeugte Kartendatei, statt sie hier zu laden.", actions.onImportMap)
    if (!stats.isEmpty) {
        SettingsDivider()
        SettingsAction("Karte löschen", "Entfernt alle Kartendaten. Die Aufzeichnungen bleiben unberührt.", { confirmDelete = true })
    }
    if (confirmDelete) {
        ConfirmDialog(
            title = "Kartendaten löschen?",
            body = "${plural(stats.tiles, "Kachel", "Kacheln")}, ${Format.fileSize(stats.bytes)}. Streckenbilder " +
                "werden danach wieder auf leerem Grund gezeichnet. Aufzeichnungen, Routen und Namen bleiben " +
                "unberührt, und die Karte lässt sich jederzeit neu laden.",
            confirm = "Löschen",
            onConfirm = { confirmDelete = false; actions.onDeleteMap() },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** "1 Kachel" rather than "1 Kacheln". */
private fun plural(n: Int, one: String, many: String): String = "$n ${if (n == 1) one else many}"

/**
 * The peak heart rate the intensity bands are measured against: typed, because
 * there is nothing to derive it from, and `220 − age` is out by a whole zone
 * for one person in three. The draft is committed only once it is plausible,
 * so typing "190" does not store 1, then 19. The bands are shown as one bar
 * with their bpm, because "where does Intensiv start" is the real question.
 */
@Composable
private fun PeakHrField(current: Int, onChange: (Int) -> Unit) {
    var draft by remember { mutableStateOf(current.toString()) }
    LaunchedEffect(current) { if (draft.toIntOrNull() != current) draft = current.toString() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Maximalpuls", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(2.dp))
            Quiet("Der eigene Höchstwert, nicht „220 minus Alter“. Grundlage aller Belastungsfarben.")
        }
        Spacer(Modifier.width(12.dp))
        OutlinedTextField(
            value = draft,
            onValueChange = { text ->
                draft = text.filter { it.isDigit() }.take(3)
                draft.toIntOrNull()
                    ?.takeIf { it in Tuning.MIN_PEAK_HR_BPM..Tuning.MAX_PEAK_HR_BPM }
                    ?.let(onChange)
            },
            suffix = { Text("bpm") },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.tabular,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(118.dp),
        )
    }
    Spacer(Modifier.height(14.dp))
    Row(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp))) {
        val bounds = Intensity.entries.map { it.lowerFraction } + 1.0
        for ((i, band) in Intensity.entries.withIndex()) {
            val w = (bounds[i + 1] - (if (i == 0) 0.5 else bounds[i])).toFloat()
            Box(Modifier.weight(w).fillMaxHeight().background(HecateIntensityColors.of(band)))
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth()) {
        for (band in Intensity.entries) {
            // The last label is the longest word; it gets the room.
            Column(Modifier.weight(if (band == Intensity.PEAK) 1.35f else 1f)) {
                Text(band.label, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium, maxLines = 1, softWrap = false)
                Quiet(if (band.lowerFraction == 0.0) "darunter" else "ab ${Format.hr(band.lowerFraction * current)}")
            }
        }
    }
}

/** A named action with a sentence saying what it does, the whole row tappable. */
@Composable
private fun SettingsAction(title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(HecateDesign.InnerShape).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(2.dp))
            Quiet(body)
        }
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
