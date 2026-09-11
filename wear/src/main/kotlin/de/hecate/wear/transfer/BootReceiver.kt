package de.hecate.wear.transfer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import de.hecate.wear.AppGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * §7: "upload leftovers on boot".
 *
 * A watch reboot during an activity ends the exercise session — there is no way
 * to keep one across a restart — but the partial file is intact and must not sit
 * on the watch waiting for the user to open the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        // Both declared actions are protected broadcasts, so only the system can
        // send them — but the receiver must say so itself, or an intent with no
        // action at all reaches this code.
        if (intent?.action !in HANDLED) return
        AppGraph.init(context)
        val pending = AppGraph.sessions.unacked()
        if (pending.isEmpty()) return
        Log.i(TAG, "Nach Neustart: ${pending.size} Sitzung(en) noch nicht übertragen")
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppGraph.uploader.uploadPending()
            } finally {
                result.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"

        val HANDLED = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
