package de.hecate.core.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

/**
 * Which of the two APKs a release carries. Each has its own CI workflow, its
 * own run number and therefore its own version code, and its own tag prefix —
 * the watch is updated less often than the phone and must not be offered a
 * phone build, or the other way round.
 */
enum class UpdateTarget(val tagPrefix: String) {
    PHONE("phone-v"),
    WATCH("wear-v"),
}

/** A release newer than what is installed: [apkUrl] downloads the APK
 *  directly, [pageUrl] is the release page, for a release without one. */
data class AvailableUpdate(
    val target: UpdateTarget,
    val versionName: String,
    val versionCode: Long,
    val pageUrl: String,
    val apkUrl: String? = null,
) {
    /** Where a tap goes: the APK itself, as in Nexus. */
    val downloadUrl: String get() = apkUrl ?: pageUrl
}

/**
 * Hecate is sideloaded from GitHub Releases (A.34), so nothing tells an
 * installed app that a newer one exists. This reads the repository's release
 * list and picks, per target, the newest release by version code — which CI
 * writes as the last part of the tag, `<prefix><major>.<minor>.<versionCode>`.
 *
 * Pure: the network is [ReleaseClient]'s, so the decision is tested on a
 * recorded response without a socket.
 */
object Releases {
    const val REPOSITORY = "jannisdickmann/hecate"
    const val URL = "https://api.github.com/repos/$REPOSITORY/releases?per_page=30"

    /** Where to send someone when there is no specific release to point at. */
    const val PAGE = "https://github.com/$REPOSITORY/releases"

    @Serializable
    data class Release(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    data class Asset(
        val name: String,
        @SerialName("browser_download_url") val downloadUrl: String,
    )

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): List<Release> = json.decodeFromString(body)

    /** The newest published release for [target], or null when there is none. */
    fun latest(releases: List<Release>, target: UpdateTarget): AvailableUpdate? =
        releases.asSequence()
            .filter { !it.draft && !it.prerelease && it.tagName.startsWith(target.tagPrefix) }
            .mapNotNull { r ->
                versionCodeOf(r.tagName, target)?.let {
                    AvailableUpdate(
                        target,
                        r.tagName.removePrefix(target.tagPrefix),
                        it,
                        r.htmlUrl,
                        r.assets.firstOrNull { a -> a.name.endsWith(".apk") }?.downloadUrl,
                    )
                }
            }
            .maxByOrNull { it.versionCode }

    /** [latest], but only when it is newer than [installedVersionCode]. */
    fun newer(releases: List<Release>, target: UpdateTarget, installedVersionCode: Long): AvailableUpdate? =
        latest(releases, target)?.takeIf { it.versionCode > installedVersionCode }

    fun versionCodeOf(tag: String, target: UpdateTarget): Long? =
        tag.takeIf { it.startsWith(target.tagPrefix) }
            ?.removePrefix(target.tagPrefix)
            ?.substringAfterLast('.')
            ?.toLongOrNull()
}

/** Where the release list comes from. One method, so that no test opens a
 *  socket — the same reasoning as [de.hecate.core.weather.WeatherClient]. */
fun interface ReleaseClient {
    fun fetch(url: String): String
}

/** The real client over `HttpURLConnection`. Sends the URL and a User-Agent
 *  that says what this is, and nothing that says who. */
class UrlReleaseClient(
    private val open: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection },
) : ReleaseClient {
    override fun fetch(url: String): String {
        val c = open(url)
        try {
            c.connectTimeout = 20_000
            c.readTimeout = 30_000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = c.responseCode
            if (code !in 200..299) throw IOException("GitHub: HTTP $code")
            return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    companion object {
        const val USER_AGENT: String = "hecate-update/1"
    }
}
