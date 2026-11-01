package de.hecate.phone.update

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import de.hecate.core.update.AvailableUpdate
import de.hecate.core.update.ReleaseClient
import de.hecate.core.update.Releases
import de.hecate.core.update.UpdateTarget
import de.hecate.core.update.UrlReleaseClient
import de.hecate.phone.Wire
import de.hecate.phone.db.HecateDatabase
import de.hecate.phone.db.MetaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** A build that is installed somewhere: on this phone, or on the watch. */
data class InstalledVersion(val code: Long, val name: String)

/** What the settings card shows, and what the notifications are made from. */
data class UpdateStatus(
    val phone: InstalledVersion? = null,
    /** Null until a watch running a build that reports itself (A.34) has been
     *  seen — an older watch build, or no watch at all. */
    val watch: InstalledVersion? = null,
    val phoneUpdate: AvailableUpdate? = null,
    val watchUpdate: AvailableUpdate? = null,
    /** The newest watch release, whether or not the watch is known to be
     *  behind: with no version from the watch, the card can still point at it. */
    val latestWatch: AvailableUpdate? = null,
    val checking: Boolean = false,
    val checkedAtUtcMs: Long? = null,
    val failed: Boolean = false,
)

/**
 * The update check (A.34). Hecate is sideloaded from GitHub Releases, phone and
 * watch each from their own, so nothing tells either that a newer build exists.
 * The phone asks GitHub for both — the watch has no network (NFR-7) and reports
 * its installed version over the Data Layer instead — and says so with a
 * notification that opens the release page.
 *
 * Each release is announced once: the version code last notified per target is
 * kept in `meta`, so a daily check does not repeat a notification the user has
 * already swiped away. The settings card shows the state regardless.
 */
class UpdateRepository(
    private val context: Context,
    private val db: HecateDatabase,
    private val client: ReleaseClient = UrlReleaseClient(),
    private val notifier: UpdateNotifier = UpdateNotifier(context),
    private val installedPhone: () -> InstalledVersion = { installedPhone(context) },
    private val installedWatch: suspend () -> InstalledVersion? = { installedWatch(context) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _status = MutableStateFlow(UpdateStatus())
    val status: StateFlow<UpdateStatus> = _status.asStateFlow()

    /** Asks GitHub, updates [status], and notifies about whatever is new.
     *  Never throws: an unreachable GitHub is a status, not a crash. */
    suspend fun check(): UpdateStatus = withContext(Dispatchers.IO) {
        _status.update { it.copy(checking = true) }
        val phone = installedPhone()
        val watch = runCatching { installedWatch() }.getOrNull()
        val result = runCatching { Releases.parse(client.fetch(Releases.URL)) }
            .map { releases ->
                UpdateStatus(
                    phone = phone,
                    watch = watch,
                    phoneUpdate = Releases.newer(releases, UpdateTarget.PHONE, phone.code),
                    watchUpdate = watch?.let { Releases.newer(releases, UpdateTarget.WATCH, it.code) },
                    latestWatch = Releases.latest(releases, UpdateTarget.WATCH),
                    checkedAtUtcMs = now(),
                )
            }
            .getOrElse { e ->
                Log.w(TAG, "Update-Prüfung fehlgeschlagen", e)
                _status.value.copy(phone = phone, watch = watch, failed = true)
            }
        _status.value = result.copy(checking = false)
        listOfNotNull(result.phoneUpdate, result.watchUpdate).forEach { announce(it) }
        result
    }

    private suspend fun announce(update: AvailableUpdate) {
        val key = notifiedKey(update.target)
        val last = db.meta().get(key)?.toLongOrNull() ?: 0L
        if (update.versionCode <= last) return
        if (notifier.notify(update, installed = installedFor(update.target))) {
            db.meta().put(MetaEntity(key, update.versionCode.toString()))
        }
    }

    private fun installedFor(target: UpdateTarget): InstalledVersion? = when (target) {
        UpdateTarget.PHONE -> _status.value.phone
        UpdateTarget.WATCH -> _status.value.watch
    }

    companion object {
        private const val TAG = "UpdateRepository"

        /** Bookkeeping, not a preference: the version code last announced. */
        fun notifiedKey(target: UpdateTarget) = "update_notified_" + target.name.lowercase()

        fun installedPhone(context: Context): InstalledVersion {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            return InstalledVersion(info.longVersionCode, info.versionName ?: "?")
        }

        /** The version the watch last reported, from whichever node put it. */
        fun installedWatch(context: Context): InstalledVersion? {
            val buffer = Tasks.await(
                Wearable.getDataClient(context).getDataItems(("wear://*" + Wire.PATH_WATCH_VERSION).toUri()),
            )
            return try {
                buffer.map { DataMapItem.fromDataItem(it).dataMap }
                    .map { InstalledVersion(it.getLong(Wire.KEY_VERSION_CODE), it.getString(Wire.KEY_VERSION_NAME) ?: "?") }
                    .maxByOrNull { it.code }
            } finally {
                buffer.release()
            }
        }
    }
}
