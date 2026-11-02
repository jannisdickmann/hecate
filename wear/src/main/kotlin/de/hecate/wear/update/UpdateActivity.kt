package de.hecate.wear.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.core.content.IntentCompat
import androidx.wear.compose.material3.Text
import de.hecate.wear.AppGraph
import de.hecate.wear.recorder.RecorderPhase
import de.hecate.wear.ui.HecateWearTheme

/**
 * Installs the APK [WatchUpdate] received, through the system installer (A.35).
 *
 * An activity rather than a receiver: the installer's confirmation is an
 * activity of its own, and only an app with a visible window may start one.
 * The session's result comes back here as a new intent for the same reason.
 * Never during a recording — installing replaces the process, and with it
 * the recorder.
 */
class UpdateActivity : ComponentActivity() {

    private val status = mutableStateOf("Wird installiert…")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        setContent {
            HecateWearTheme {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(status.value, textAlign = TextAlign.Center)
                }
            }
        }
        if (savedInstanceState == null && intent?.action != ACTION_RESULT) start()
        else intent?.let(::onResult)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_RESULT) onResult(intent)
    }

    private fun start() {
        val apk = WatchUpdate.apk(this)
        when {
            AppGraph.recorder.state.value.phase != RecorderPhase.IDLE -> status.value = "Nach der Aufzeichnung"
            WatchUpdate.verify(this, apk) == null -> {
                apk.delete()
                WatchUpdate.dismiss(this)
                status.value = "Kein Update"
            }
            !packageManager.canRequestPackageInstalls() -> {
                status.value = "Installation nicht erlaubt"
                try {
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()),
                    )
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "Keine Einstellung für unbekannte Quellen", e)
                }
            }
            else -> runCatching { install() }.onFailure {
                Log.e(TAG, "Installation fehlgeschlagen", it)
                status.value = "Installation fehlgeschlagen"
            }
        }
    }

    private fun install() {
        val apk = WatchUpdate.apk(this)
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(packageName)
            setSize(apk.length())
            // Once Hecate installed itself, the next update needs no tap.
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("hecate.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getActivity(
                this,
                id,
                Intent(this, UpdateActivity::class.java).setAction(ACTION_RESULT),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            session.commit(result.intentSender)
        }
    }

    private fun onResult(intent: Intent) {
        when (val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { startActivity(it) }
            PackageInstaller.STATUS_SUCCESS -> {
                WatchUpdate.apk(this).delete()
                WatchUpdate.dismiss(this)
                status.value = "Installiert"
            }
            else -> {
                Log.w(TAG, "Installer: $code ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                status.value = "Installation fehlgeschlagen"
            }
        }
    }

    private companion object {
        const val TAG = "UpdateActivity"
        const val ACTION_RESULT = "de.hecate.wear.update.RESULT"
    }
}
