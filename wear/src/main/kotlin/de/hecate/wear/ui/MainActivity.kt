package de.hecate.wear.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import de.hecate.wear.AppGraph

/**
 * The only activity. Three pages in a pager, no navigation library (§4).
 *
 * Permissions are requested here rather than in the service: a foreground
 * service that starts without location permission dies, and the user has to be
 * asked while a screen is in front of them anyway.
 */
class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* the UI reflects whatever was granted; nothing to do here */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        requestMissingPermissions()
        setContent { HecateWearApp() }
    }

    override fun onResume() {
        super.onResume()
        // Catch anything the watch is still holding, e.g. after a reboot.
        AppGraph.recorder.update { it.copy(pendingUploads = AppGraph.sessions.unacked().size) }
    }

    private fun requestMissingPermissions() {
        val needed = REQUIRED.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private companion object {
        /**
         * Heart rate, on Wear OS 6 and anything else targeting SDK 36.
         *
         * `BODY_SENSORS` used to be this, and asking for it here is what broke a
         * whole run. For an app targeting 36 the platform no longer tracks
         * `BODY_SENSORS` as a runtime permission at all — `dumpsys package` does
         * not list it among the app's runtime permissions, and requesting it is a
         * silent no-op that leaves the appop at `ignore`. The grant the platform
         * actually wants is this one, declared by the Health Connect controller.
         *
         * A string rather than a constant because `android.Manifest.permission`
         * has no entry for it; `androidx.health.connect` does, and `:wear` is not
         * going to take that dependency for one name (NFR-8).
         */
        const val READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"

        // §6.4 lists these as manual grants; asking for them is still friendlier.
        //
        // Not here, and deliberately: ACCESS_BACKGROUND_LOCATION. The platform
        // refuses it in the same dialog as foreground location, so it has to be a
        // second request after the first is granted — and on Wear that is a
        // separate trip to Settings. §6.4 covers it as a manual grant.
        val REQUIRED = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            READ_HEART_RATE,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
