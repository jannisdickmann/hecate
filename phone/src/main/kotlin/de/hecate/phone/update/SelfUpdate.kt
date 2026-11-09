package de.hecate.phone.update

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import de.hecate.core.update.UpdateTarget
import de.hecate.phone.R
import java.io.File

/**
 * The phone installs its own update (A.36): the APK is downloaded by
 * [de.hecate.phone.work.SelfUpdateWorker], checked here, and handed to the
 * system installer in a session. The installer's answer arrives at
 * [SelfUpdateReceiver].
 *
 * The first self-update needs the user twice: once to allow Hecate to install
 * apps, once to confirm — the browser or file manager that installed the
 * current build is its installer of record. After that Hecate is its own, and
 * [PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED] may let Android
 * install without asking.
 */
object SelfUpdate {

    /** Why an APK is not installed. */
    enum class Verdict { OK, NOT_NEWER, INVALID }

    /** Only this app, newer than what is installed, signed with the same key. */
    fun verify(context: Context, file: File): Pair<Verdict, PackageInfo?> {
        if (!file.isFile || file.length() == 0L) return Verdict.INVALID to null
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val archive = pm.getPackageArchiveInfo(file.path, flags) ?: return Verdict.INVALID to null
        if (archive.packageName != context.packageName) return Verdict.INVALID to null
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.longVersionCode <= installed.longVersionCode) return Verdict.NOT_NEWER to archive
        val mine = installed.signingInfo?.apkContentsSigners?.toSet() ?: return Verdict.INVALID to null
        val theirs = archive.signingInfo?.apkContentsSigners?.toSet() ?: return Verdict.INVALID to null
        return if (mine == theirs) Verdict.OK to archive else Verdict.INVALID to null
    }

    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** The system screen where the user allows Hecate to install apps. */
    fun allowIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Writes [apk] into an installer session and commits it. The answer goes
     *  to [SelfUpdateReceiver]; on success this process is replaced. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                session.openWrite("hecate.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                val answer = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, SelfUpdateReceiver::class.java).setAction(SelfUpdateReceiver.ACTION_RESULT),
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(answer.intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
    }

    enum class State(val title: String) {
        CONFIRM("Update installieren"),
        NOT_ALLOWED("Installation nicht erlaubt"),
        FAILED("Installation fehlgeschlagen"),
    }

    /** The one notification about the phone's own update, replaced as it
     *  goes. [tap] opens the confirmation or the permission screen. */
    fun notify(context: Context, state: State, tap: Intent? = null) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification(context, state, tap)) }
    }

    fun notification(context: Context, state: State, tap: Intent?): Notification {
        UpdateNotifier.ensureChannel(context)
        val builder = NotificationCompat.Builder(context, UpdateNotifier.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_hecate)
            .setContentTitle(state.title)
            .setAutoCancel(true)
        if (tap != null) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    context,
                    NOTIFICATION_ID,
                    tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
        }
        return builder.build()
    }

    fun dismiss(context: Context) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(NOTIFICATION_ID)
        manager.cancel(UpdateNotifier.notificationId(UpdateTarget.PHONE))
    }

    const val NOTIFICATION_ID = 7_198
}
