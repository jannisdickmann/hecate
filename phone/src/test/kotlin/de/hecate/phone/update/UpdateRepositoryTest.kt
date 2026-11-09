package de.hecate.phone.update

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import de.hecate.core.update.ReleaseClient
import de.hecate.core.update.UpdateTarget
import de.hecate.phone.Fixtures
import de.hecate.phone.db.HecateDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class UpdateRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private lateinit var db: HecateDatabase

    @Before
    fun setUp() {
        db = Fixtures.database(context)
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() = db.close()

    private fun releases(vararg tags: String) = tags.joinToString(",", "[", "]") { tag ->
        """{"tag_name":"$tag","html_url":"https://github.com/jannisdickmann/hecate/releases/tag/$tag","draft":false,"prerelease":false,""" +
            """"assets":[{"name":"hecate-$tag.apk","browser_download_url":"https://github.com/jannisdickmann/hecate/releases/download/$tag/hecate-$tag.apk"}]}"""
    }

    private fun repo(
        body: () -> String,
        phone: Long = 5,
        watch: InstalledVersion? = InstalledVersion(3, "1.0.3"),
    ) = UpdateRepository(
        context, db,
        client = ReleaseClient { body() },
        installedPhone = { InstalledVersion(phone, "1.0.$phone") },
        installedWatch = { watch },
        now = { 1_000L },
    )

    private fun posted() = shadowOf(notifications).allNotifications

    @Test
    fun `a newer phone and a newer watch release are each announced, and their APKs installed from the app`(): Unit = runBlocking {
        val status = repo({ releases("phone-v1.0.6", "wear-v1.0.4", "phone-v1.0.5") }).check()

        assertEquals(6, status.phoneUpdate?.versionCode)
        assertEquals(4, status.watchUpdate?.versionCode)
        assertEquals(1_000L, status.checkedAtUtcMs)
        assertEquals(2, posted().size)

        val phone = shadowOf(notifications).getNotification(UpdateNotifier.notificationId(UpdateTarget.PHONE))
        assertEquals("Update für Telefon: 1.0.6", phone.extras.getString("android.title"))
        // The phone's tap downloads and installs in the app (A.36).
        assertTrue(phone.contentIntent.isBroadcast)
        val tap = shadowOf(phone.contentIntent).savedIntent
        assertEquals(SelfUpdateReceiver::class.java.name, tap.component?.className)
        assertEquals(SelfUpdateReceiver.ACTION_START, tap.action)
        assertEquals(
            "https://github.com/jannisdickmann/hecate/releases/download/phone-v1.0.6/hecate-phone-v1.0.6.apk",
            tap.getStringExtra(SelfUpdateReceiver.EXTRA_URL),
        )

        val watch = shadowOf(notifications).getNotification(UpdateNotifier.notificationId(UpdateTarget.WATCH))
        assertEquals("Update für Uhr: 1.0.4", watch.extras.getString("android.title"))
        assertEquals("Installiert: 1.0.3", watch.extras.getString("android.text"))
        // The watch's tap sends the APK to the watch rather than opening it here.
        val send = shadowOf(watch.contentIntent)
        assertTrue(watch.contentIntent.isBroadcast)
        assertEquals(WatchInstallReceiver::class.java.name, send.savedIntent.component?.className)
        assertEquals(
            "https://github.com/jannisdickmann/hecate/releases/download/wear-v1.0.4/hecate-wear-v1.0.4.apk",
            send.savedIntent.getStringExtra(WatchInstallReceiver.EXTRA_URL),
        )
    }

    @Test
    fun `tapping the watch's notification queues the transfer to the watch`() {
        Fixtures.workManager(context)
        WatchInstallReceiver().onReceive(
            context,
            android.content.Intent().putExtra(WatchInstallReceiver.EXTRA_URL, "https://example.invalid/w.apk"),
        )
        val work = androidx.work.WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(de.hecate.phone.work.Jobs.WATCH_INSTALL).get()
        assertEquals(1, work.size)
    }

    @Test
    fun `tapping the phone's notification queues its own update`() {
        Fixtures.workManager(context)
        SelfUpdateReceiver().onReceive(
            context,
            Intent(SelfUpdateReceiver.ACTION_START).putExtra(SelfUpdateReceiver.EXTRA_URL, "https://example.invalid/p.apk"),
        )
        val work = androidx.work.WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(de.hecate.phone.work.Jobs.SELF_UPDATE).get()
        assertEquals(1, work.size)
    }

    @Test
    fun `up to date is no notification`(): Unit = runBlocking {
        val status = repo({ releases("phone-v1.0.5", "wear-v1.0.3") }).check()
        assertNull(status.phoneUpdate)
        assertNull(status.watchUpdate)
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `a release is announced once, and the next one again`(): Unit = runBlocking {
        var body = releases("phone-v1.0.6")
        val repo = repo({ body })
        repo.check()
        notifications.cancelAll()

        repo.check()
        assertTrue(posted().isEmpty(), "the same release twice")

        body = releases("phone-v1.0.7", "phone-v1.0.6")
        repo.check()
        assertEquals(1, posted().size)
    }

    @Test
    fun `without a version from the watch it is not nagged about, but the card can point at the release`(): Unit = runBlocking {
        val status = repo({ releases("wear-v1.0.9") }, watch = null).check()
        assertNull(status.watchUpdate)
        assertEquals(9, status.latestWatch?.versionCode)
        assertTrue(posted().isEmpty())
    }

    @Test
    fun `without the notification permission nothing is posted, and it is tried again later`(): Unit = runBlocking {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val repo = repo({ releases("phone-v1.0.6") })
        assertNotNull(repo.check().phoneUpdate)
        assertTrue(posted().isEmpty())

        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        repo.check()
        assertEquals(1, posted().size)
    }

    @Test
    fun `an unreachable GitHub is a status, not a crash`(): Unit = runBlocking {
        val status = repo({ throw IOException("offline") }).check()
        assertTrue(status.failed)
        assertEquals(5, status.phone?.code)
        assertTrue(posted().isEmpty())
    }
}
