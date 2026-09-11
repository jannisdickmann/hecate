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
        // §6.4 lists these as manual grants; asking for them is still friendlier.
        val REQUIRED = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.POST_NOTIFICATIONS,
        )
    }
}
