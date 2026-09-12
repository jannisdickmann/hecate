package de.hecate.phone.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import de.hecate.phone.AppGraph
import de.hecate.phone.archive.Archive
import de.hecate.phone.health.HealthConnectWriter
import de.hecate.phone.work.Jobs

/**
 * The only activity. Four screens, switched by state (§4: no Compose Navigation).
 *
 * Two things need the activity rather than the view model: the file picker for
 * TCX import (US-9) and the Health Connect permission flow, which has to be
 * launched from an activity result contract.
 */
class MainActivity : ComponentActivity() {

    private val vm: HecateViewModel by viewModels()

    private val pickTcx = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        vm.importTcx(uris)
    }

    /**
     * §1.3 of the open points: backup to a file the user places themselves. The
     * Storage Access Framework means no storage permission and no opinion from
     * this app about where the file belongs.
     */
    private val createArchive = registerForActivityResult(
        ActivityResultContracts.CreateDocument(Archive.MIME),
    ) { uri: Uri? ->
        uri?.let { vm.exportArchive(it) }
    }

    private val openArchive = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri?.let { vm.restoreArchive(it) }
    }

    private val requestHealthConnect = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted: Set<String> ->
        if (granted.containsAll(HealthConnectWriter.PERMISSIONS)) {
            Jobs.enqueueHealthConnect(this)
            toast("Health Connect eingerichtet")
        } else {
            toast("Health-Connect-Berechtigungen fehlen weiterhin")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        setContent {
            HecateApp(
                vm = vm,
                onPickTcx = {
                    // Google Health exports TCX; a few exporters use text/xml.
                    pickTcx.launch(arrayOf("*/*"))
                },
                onExportArchive = { createArchive.launch(Archive.suggestedFileName(System.currentTimeMillis())) },
                // "*/*" for the same reason the TCX picker uses it: providers
                // disagree about the type of a .zip, and a backup the user cannot
                // select is worse than a picker that shows too much.
                onRestoreArchive = { openArchive.launch(arrayOf("*/*")) },
                onRequestHealthConnect = { startHealthConnectFlow() },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Anything the watch delivered while the app was closed is already in the
        // database; this only nudges the writes that are still outstanding.
        Jobs.enqueueHealthConnect(this)
    }

    private fun startHealthConnectFlow() {
        when (AppGraph.healthConnect.availability) {
            HealthConnectClient.SDK_AVAILABLE ->
                requestHealthConnect.launch(HealthConnectWriter.PERMISSIONS)
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                toast("Health Connect muss aktualisiert werden")
            else ->
                toast("Health Connect ist auf diesem Gerät nicht verfügbar")
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
