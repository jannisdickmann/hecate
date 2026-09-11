package de.hecate.wear.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import de.hecate.wear.R
import de.hecate.wear.ui.MainActivity

/**
 * The Ongoing Activity notification (§5.3): the recording's presence on the
 * watch face and in the recents strip, mirroring the key metric.
 *
 * Also the foreground-service notification, which is what actually keeps the
 * process alive for the duration of a workout.
 */
object OngoingNotification {

    const val CHANNEL_ID = "hecate.recording"
    const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notif_channel_desc)
                setShowBadge(false)
                enableVibration(false)
            },
        )
    }

    /**
     * @param title short status line, e.g. "Weg zur Arbeit"
     * @param text the key metric, e.g. "4,20 km · 23:11"
     */
    fun build(context: Context, title: String, text: String): Notification {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hecate)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        OngoingActivity.Builder(context, NOTIFICATION_ID, builder)
            .setTouchIntent(open)
            .setStaticIcon(R.drawable.ic_stat_hecate)
            .setStatus(Status.Builder().addTemplate("$title · $text").build())
            .build()
            .apply(context)

        return builder.build()
    }

    fun update(context: Context, title: String, text: String) {
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        mgr.notify(NOTIFICATION_ID, build(context, title, text))
    }
}
