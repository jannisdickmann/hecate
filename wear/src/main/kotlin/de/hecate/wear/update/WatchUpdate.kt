package de.hecate.wear.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import de.hecate.wear.AppVersion
import de.hecate.wear.R
import java.io.File

/**
 * A newer watch build, sent by the phone over the Data Layer (A.35).
 *
 * The watch has no network (NFR-7), so the phone downloads the release and
 * streams the APK here. It is kept only if it is this app, signed with this
 * app's key, and newer than what is installed — anything else is deleted on
 * arrival — and installed only when the user taps the notification, through
 * [UpdateActivity] and the system installer.
 */
object WatchUpdate {

    private const val TAG = "WatchUpdate"
    private const val CHANNEL_ID = "hecate.update"
    private const val NOTIFICATION_ID = 1002

    fun apk(context: Context) = File(context.filesDir, "update.apk")

    /** Reads the APK off [channel], verifies it and offers it. Blocking. */
    fun receive(context: Context, channel: ChannelClient.Channel) {
        val client = Wearable.getChannelClient(context)
        val part = File(context.filesDir, "update.apk.part")
        try {
            Tasks.await(client.getInputStream(channel)).use { input ->
                part.outputStream().use { input.copyTo(it) }
            }
        } finally {
            runCatching { client.close(channel) }
        }
        val info = verify(context, part)
        if (info == null) {
            Log.w(TAG, "Empfangene APK abgelehnt")
            part.delete()
            return
        }
        val target = apk(context)
        target.delete()
        part.renameTo(target)
        Log.i(TAG, "Update ${info.versionName} empfangen (${target.length()} B)")
        offer(context, info.versionName ?: "?")
    }

    /** The archive's package info, or null unless it is this app, signed by the
     *  same key, and newer than the installed build. */
    fun verify(context: Context, file: File): PackageInfo? {
        if (!file.isFile || file.length() == 0L) return null
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return null
        if (archive.packageName != context.packageName) return null
        if (archive.longVersionCode <= AppVersion.code(context)) return null
        val installed = pm.getPackageInfo(context.packageName, flags)
        val mine = installed.signingInfo?.apkContentsSigners?.toSet() ?: return null
        val theirs = archive.signingInfo?.apkContentsSigners?.toSet() ?: return null
        return archive.takeIf { mine == theirs }
    }

    private fun offer(context: Context, versionName: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Updates", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, UpdateActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_hecate)
            .setContentTitle("Update $versionName")
            .setContentText("Installieren")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        // The recorder asks for the permission on first start; without it the
        // update waits, and the next transfer offers it again.
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Keine Benachrichtigungen erlaubt")
            return
        }
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    fun dismiss(context: Context) = NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}
