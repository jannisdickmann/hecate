package de.hecate.phone.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import de.hecate.phone.work.Jobs

/**
 * The phone's own update (A.36), in two roles: the update notification's tap
 * ([ACTION_START], which queues the download — a receiver, because since
 * Android 12 a notification may not reach an activity through one), and the
 * system installer's answer ([ACTION_RESULT]).
 */
class SelfUpdateReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_START -> intent.getStringExtra(EXTRA_URL)?.let { Jobs.enqueueSelfUpdate(context, it) }
            ACTION_RESULT -> onResult(context, intent)
        }
    }

    private fun onResult(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java) ?: return
                // A notification always works; starting the screen directly
                // only while Hecate has a visible window, which is the usual
                // case after a tap in Settings.
                SelfUpdate.notify(context, SelfUpdate.State.CONFIRM, Intent(confirm))
                runCatching { context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Log.i(TAG, "Bestätigung nur über die Benachrichtigung", it) }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Log.i(TAG, "Update installiert")
                SelfUpdate.dismiss(context)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                Log.i(TAG, "Update abgebrochen")
                SelfUpdate.dismiss(context)
            }
            else -> {
                Log.w(TAG, "Installer: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                SelfUpdate.notify(context, SelfUpdate.State.FAILED)
            }
        }
    }

    companion object {
        const val ACTION_START = "de.hecate.phone.update.SELF_UPDATE"
        const val ACTION_RESULT = "de.hecate.phone.update.SELF_UPDATE_RESULT"
        const val EXTRA_URL = "url"
        private const val TAG = "SelfUpdate"
    }
}
