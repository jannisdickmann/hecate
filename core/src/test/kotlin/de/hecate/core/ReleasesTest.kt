package de.hecate.core

import de.hecate.core.update.ReleaseClient
import de.hecate.core.update.Releases
import de.hecate.core.update.UpdateTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReleasesTest {

    private val releases = Releases.parse(
        requireNotNull(javaClass.getResource("/update/releases.json")).readText(),
    )

    @Test
    fun `the newest phone release wins by version code, not by list order`() {
        val latest = assertNotNull(Releases.latest(releases, UpdateTarget.PHONE))
        assertEquals(14, latest.versionCode)
        assertEquals("1.0.14", latest.versionName)
        assertEquals("https://github.com/jannisdickmann/hecate/releases/tag/phone-v1.0.14", latest.pageUrl)
    }

    @Test
    fun `a tap goes to the APK itself, or to the page when a release has none`() {
        val phone = assertNotNull(Releases.latest(releases, UpdateTarget.PHONE))
        assertEquals(
            "https://github.com/jannisdickmann/hecate/releases/download/phone-v1.0.14/hecate-phone-1.0.14.apk",
            phone.downloadUrl,
        )
        val watch = assertNotNull(Releases.latest(releases, UpdateTarget.WATCH))
        assertEquals(watch.pageUrl, watch.downloadUrl)
    }

    @Test
    fun `drafts and prereleases are never offered`() {
        // phone-v1.0.20 is a draft, wear-v1.0.21 a prerelease.
        assertEquals(14, Releases.latest(releases, UpdateTarget.PHONE)?.versionCode)
        assertEquals(7, Releases.latest(releases, UpdateTarget.WATCH)?.versionCode)
    }

    @Test
    fun `phone and watch are counted apart`() {
        // The watch's 7 is older than the phone's 14, and must not hide it or
        // be hidden by it: each target looks only at its own tags.
        assertEquals(UpdateTarget.WATCH, Releases.newer(releases, UpdateTarget.WATCH, 6)?.target)
        assertNull(Releases.newer(releases, UpdateTarget.WATCH, 7))
        assertNull(Releases.newer(releases, UpdateTarget.PHONE, 14))
        assertEquals(14, Releases.newer(releases, UpdateTarget.PHONE, 13)?.versionCode)
    }

    @Test
    fun `a local build at version code 1 is offered the release`() {
        assertEquals(14, Releases.newer(releases, UpdateTarget.PHONE, 1)?.versionCode)
    }

    @Test
    fun `an installed build newer than every release is offered nothing`() {
        assertNull(Releases.newer(releases, UpdateTarget.PHONE, 99))
    }

    @Test
    fun `version codes come from the last part of the tag`() {
        assertEquals(42, Releases.versionCodeOf("wear-v1.0.42", UpdateTarget.WATCH))
        assertNull(Releases.versionCodeOf("phone-v1.0.42", UpdateTarget.WATCH))
        assertNull(Releases.versionCodeOf("wear-v1.0.beta", UpdateTarget.WATCH))
    }

    @Test
    fun `no releases at all is no update`() {
        assertNull(Releases.newer(Releases.parse("[]"), UpdateTarget.PHONE, 1))
    }

    @Test
    fun `the client is asked for the repository's release list`() {
        var asked: String? = null
        val client = ReleaseClient { url -> asked = url; "[]" }
        Releases.parse(client.fetch(Releases.URL))
        assertEquals("https://api.github.com/repos/jannisdickmann/hecate/releases?per_page=30", asked)
    }
}
