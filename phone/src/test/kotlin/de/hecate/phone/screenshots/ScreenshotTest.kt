package de.hecate.phone.screenshots

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumTopAppBar
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import de.hecate.core.model.ExerciseType
import de.hecate.phone.db.RouteSummary
import de.hecate.phone.db.SessionSummary
import de.hecate.phone.ui.HecateTheme
import de.hecate.phone.ui.RecordingActions
import de.hecate.phone.ui.RecordingDetailContent
import de.hecate.phone.ui.RecordingsContent
import de.hecate.phone.ui.RouteActions
import de.hecate.phone.ui.RouteDetail
import de.hecate.phone.ui.RouteDetailContent
import de.hecate.phone.ui.RoutesContent
import de.hecate.phone.ui.SessionDetailBuilder
import de.hecate.phone.ui.SettingsActions
import de.hecate.phone.ui.SettingsContent
import de.hecate.phone.ui.SettingsState
import de.hecate.phone.ui.Standout
import de.hecate.phone.ui.HealthConnectState
import de.hecate.phone.ui.heatmapCaption
import de.hecate.phone.ui.rememberHeatMap
import de.hecate.phone.ui.rememberHeatmapRaster
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Every screen, rendered on the JVM and written to `phone/build/screenshots`.
 *
 * Not an assertion: a picture to look at, which is what found most of what
 * A.14, A.22 and A.29 record. Run with
 * `./gradlew :phone:testDebugUnitTest --tests '*ScreenshotTest*'`.
 *
 * The scrolling screens are captured tall, as a whole page, so one image shows
 * everything a thumb would reach; the "-screen" variants are phone-sized.
 * The bars around the panels are a stand-in for `HecateApp`'s, which needs a
 * live view model; the screens inside them are the real ones.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = TALL)
class ScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val out = File(System.getProperty("user.dir"), "build/screenshots").apply { mkdirs() }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        compose.setContent { HecateTheme { content() } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.waitForIdle()
        // Rasters are built on Dispatchers.Default, which the test clock does
        // not drive: give them real time, then let the result compose.
        Thread.sleep(1_500)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Panel(selected: Int, title: String, content: @Composable () -> Unit) {
        Scaffold(
            topBar = { MediumTopAppBar(title = { Text(title) }) },
            bottomBar = {
                NavigationBar {
                    listOf(
                        Icons.AutoMirrored.Filled.List to "Aufzeichnungen",
                        Icons.Filled.Place to "Routen",
                        Icons.Filled.Settings to "Einstellungen",
                    ).forEachIndexed { i, (icon, label) ->
                        NavigationBarItem(selected = i == selected, onClick = {}, icon = { Icon(icon, null) }, label = { Text(label) })
                    }
                }
            },
        ) { Box(Modifier.padding(it).fillMaxSize()) { content() } }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Detail(content: @Composable () -> Unit) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {},
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                    navigationIcon = { IconButton(onClick = {}) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) } },
                    actions = { IconButton(onClick = {}) { Icon(Icons.Filled.Share, null) } },
                )
            },
        ) { Box(Modifier.padding(it).fillMaxSize()) { content() } }
    }

    private val samples = Synthetic.samples()
    private val session = Synthetic.session(samples)
    private val members = Synthetic.routeDurations(session.activeDurationMs).mapIndexed { i, ms ->
        SessionSummary(
            "m$i", Synthetic.START_UTC - i * 4 * 86_400_000L - (i % 3) * 3_600_000L, Synthetic.TZ,
            ExerciseType.RUNNING, ms, session.totalDistanceM, session.source, 1, 7, "Mainufer-Runde",
            avgHr = 132.0 + (i * 5 % 14),
            weatherCode = listOf(1, 3, 61, 0, 2, 80, 3)[i % 7], tempMaxC = 8.0 + i % 9, weatherIsDay = true,
        )
    }.sortedByDescending { it.startedAtUtc }
    private val detail = SessionDetailBuilder.build(session, samples, Synthetic.route, members, emptyList(), emptyList())
    private val weather = Synthetic.weather(samples)
    private val history = Synthetic.history()
    private val now = Synthetic.START_UTC + 3 * 3_600_000L

    private fun buildRouteDetail() = RouteDetail(
        id = 7, name = "Mainufer-Runde", exerciseType = ExerciseType.RUNNING, lengthM = session.totalDistanceM,
        memberCount = members.size, geometry = detail.geometry.filterIndexed { i, _ -> i % 4 == 0 },
        medianDurationMs = members.map { it.activeDurationMs }.sorted().let { (it[6] + it[7]) / 2 },
        bestDurationMs = members.minOf { it.activeDurationMs }, worstDurationMs = members.maxOf { it.activeDurationMs },
        priority = 1, elevation = detail.elevation, elevationStats = detail.elevationStats,
        speed = detail.speed, speedStats = detail.speedStats, heartRate = detail.heartRate,
        heartRateStats = detail.heartRateStats, sessions = members,
    )

    private val routes = listOf(
        RouteSummary(7, "Mainufer-Runde", ExerciseType.RUNNING, 8_240.0, 14, 2_798_000, 2_481_000, 1, false),
        RouteSummary(8, "Zur Arbeit", ExerciseType.BIKING, 12_600.0, 31, 1_706_000, 1_552_000, 0, false),
        RouteSummary(9, "Wilhelmsbad", ExerciseType.WALKING, 4_100.0, 9, 2_950_000, 2_701_000, 0, false),
        RouteSummary(10, "Kinzig-Damm", ExerciseType.RUNNING, 11_900.0, 4, 3_930_000, 3_801_000, 0, false),
        RouteSummary(11, "Hohe Straße", ExerciseType.HIKING, 15_300.0, 1, 13_200_000, 13_200_000, 0, true),
    )
    private val trends = mapOf(
        7L to members.sortedBy { it.startedAtUtc }.map { it.activeDurationMs },
        8L to (0 until 31).map { 1_780_000L - it * 3_500L + ((it * 7) % 5 - 2) * 12_000L },
        9L to (0 until 9).map { 2_950_000L + ((it * 13) % 7 - 3) * 40_000L },
        10L to listOf(3_990_000L, 3_930_000L, 3_801_000L, 3_870_000L),
    )

    @Composable
    private fun RecordingsPanel() = RecordingsContent(
        all = history,
        sessions = history,
        types = listOf(ExerciseType.RUNNING, ExerciseType.BIKING, ExerciseType.WALKING, ExerciseType.HIKING),
        filter = null,
        standouts = mapOf("s0" to Standout(fast = true), "s5" to Standout(highHr = true)),
        onFilter = {}, onOpen = {}, nowUtcMs = now,
    )

    @Composable
    private fun RoutesPanel() {
        val heat = remember { Synthetic.heat() }
        RoutesContent(routes, false, rememberHeatMap(heat, rememberHeatmapRaster(heat.grid)), heatmapCaption(heat), trends, {}, {})
    }

    private val settings = SettingsState(
        healthConnect = HealthConnectState.CONNECTED, peakHr = 186, weatherCount = 212, sessionCount = 248, weatherRunning = true,
    )

    @Test fun recordingDetail() = shoot("1-recording-detail") { Detail { RecordingDetailContent(detail, 186, weather, RecordingActions()) } }

    @Test @Config(qualifiers = TALL_NIGHT)
    fun recordingDetailDark() = shoot("1-recording-detail-dark") { Detail { RecordingDetailContent(detail, 186, weather, RecordingActions()) } }

    @Test @Config(qualifiers = SCREEN)
    fun recordingDetailCursor() = shoot("2-recording-detail-scrubbing") {
        Detail { RecordingDetailContent(detail, 186, weather, RecordingActions(), initialCursorMs = 31 * 60_000L) }
    }

    @Test @Config(qualifiers = SCREEN)
    fun recordingDetailScreen() = shoot("1-recording-detail-screen") { Detail { RecordingDetailContent(detail, 186, weather, RecordingActions()) } }

    @Test fun recordingsList() = shoot("3-recordings") { Panel(0, "Aufzeichnungen") { RecordingsPanel() } }

    @Test @Config(qualifiers = SCREEN_NIGHT)
    fun recordingsListDark() = shoot("3-recordings-dark") { Panel(0, "Aufzeichnungen") { RecordingsPanel() } }

    @Test fun routesList() = shoot("4-routes") { Panel(1, "Routen") { RoutesPanel() } }

    @Test @Config(qualifiers = SCREEN_NIGHT)
    fun routesListDark() = shoot("4-routes-dark") { Panel(1, "Routen") { RoutesPanel() } }

    @Test fun routeDetail() = shoot("5-route-detail") { Detail { RouteDetailContent(buildRouteDetail(), 186, RouteActions()) } }

    @Test @Config(qualifiers = TALL_NIGHT)
    fun routeDetailDark() = shoot("5-route-detail-dark") { Detail { RouteDetailContent(buildRouteDetail(), 186, RouteActions()) } }

    @Test fun settings() = shoot("6-settings") { Panel(2, "Einstellungen") { SettingsContent(settings, SettingsActions()) } }

    @Test @Config(qualifiers = TALL_NIGHT)
    fun settingsDark() = shoot("6-settings-dark") { Panel(2, "Einstellungen") { SettingsContent(settings, SettingsActions()) } }

    @Test @Config(qualifiers = SCREEN)
    fun emptyRecordings() = shoot("7-recordings-empty") {
        Panel(0, "Aufzeichnungen") { RecordingsContent(emptyList(), emptyList(), emptyList(), null, emptyMap(), {}, {}) }
    }

}

private const val TALL = "w400dp-h2900dp-xxhdpi"
private const val TALL_NIGHT = "w400dp-h2900dp-night-xxhdpi"
private const val SCREEN = "w400dp-h860dp-xxhdpi"
private const val SCREEN_NIGHT = "w400dp-h860dp-night-xxhdpi"
