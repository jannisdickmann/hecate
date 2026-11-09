package de.hecate.phone.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import de.hecate.core.update.AvailableUpdate
import de.hecate.core.update.UpdateTarget
import de.hecate.phone.R

/**
 * One notification per target. Tapping the phone's downloads the APK, as
 * Nexus does; tapping the watch's sends the APK to the watch (A.35), which
 * never checks for itself: it has no network and no browser.
 */
open class UpdateNotifier(private val context: Context) {

    /** True when the notification was posted; false when Android would not
     *  let it be (the permission is withheld), so it is tried again next time. */
    open fun notify(update: AvailableUpdate, installed: InstalledVersion?): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        ensureChannel(context)
        // The phone's APK opens in the browser, which installs it; the watch's
        // is sent to the watch (A.35), which has neither network nor browser.
        val apk = update.apkUrl
        val open = if (update.target == UpdateTarget.WATCH && apk != null) {
            PendingIntent.getBroadcast(
                context,
                update.target.ordinal,
                Intent(context, WatchInstallReceiver::class.java).putExtra(WatchInstallReceiver.EXTRA_URL, apk),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        } else {
            PendingIntent.getActivity(
                context,
                update.target.ordinal,
                Intent(Intent.ACTION_VIEW, update.downloadUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val (title, text) = wording(update, installed)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hecate)
            .setContentTitle(title)
            .setContentText(text)
                        .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .build()
        return runCatching {
            NotificationManagerCompat.from(context).notify(notificationId(update.target), notification)
        }.isSuccess
    }

    enum class Transfer(val title: String, val ongoing: Boolean) {
        DOWNLOADING("Wird geladen…", true),
        SENDING("Wird an die Uhr gesendet…", true),
        CHECKING("Wird auf der Uhr geprüft…", true),
        SENT("Auf der Uhr installieren", false),
        NO_WATCH("Uhr nicht verbunden", false),
        WATCH_TOO_OLD("Uhr-App zu alt", false),
        REJECTED("Von der Uhr abgelehnt", false),
        FAILED("Senden fehlgeschlagen", false),
    }

    /** The one notification about a transfer to the watch, replaced as it goes. */
    open fun watchTransfer(state: Transfer) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(TRANSFER_ID, transferNotification(state)) }
    }

    /** Also the foreground notification of the transfer's worker. [percent]
     *  fills the bar; null leaves it indeterminate. */
    fun transferNotification(state: Transfer, percent: Int? = null): Notification {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hecate)
            .setContentTitle(state.title)
            .setOngoing(state.ongoing)
            .setAutoCancel(!state.ongoing)
            .setOnlyAlertOnce(true)
        if (state.ongoing) builder.setProgress(100, percent ?: 0, percent == null)
        return builder.build()
    }

    companion object {
        const val CHANNEL = "updates"
        const val TRANSFER_ID = 7_199

        fun notificationId(target: UpdateTarget) = 7_100 + target.ordinal

        fun wording(update: AvailableUpdate, installed: InstalledVersion?): Pair<String, String> {
            val device = if (update.target == UpdateTarget.PHONE) "Telefon" else "Uhr"
            return "Update für $device: ${update.versionName}" to (installed?.let { "Installiert: ${it.name}" } ?: "")
        }

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Neue Versionen von Hecate für Telefon und Uhr"
                },
            )
        }
    }
}
