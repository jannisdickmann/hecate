package de.hecate.phone.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import de.hecate.phone.work.Jobs

/** The watch-update notification's tap: send the release to the watch. A
 *  receiver, because the tap starts work rather than a screen. */
class WatchInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL) ?: return
        Jobs.enqueueWatchInstall(context, url)
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
