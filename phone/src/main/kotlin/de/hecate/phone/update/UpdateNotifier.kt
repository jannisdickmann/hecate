package de.hecate.phone.update

import android.Manifest
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
 * One notification per target; tapping it downloads the APK, as Nexus does.
 * The watch never shows this itself: it has no network and no browser.
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
        val open = PendingIntent.getActivity(
            context,
            update.target.ordinal,
            Intent(Intent.ACTION_VIEW, update.downloadUrl.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
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

    companion object {
        const val CHANNEL = "updates"

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
