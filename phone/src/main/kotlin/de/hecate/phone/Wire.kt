package de.hecate.phone

/**
 * The watch↔phone contract, mirrored from `de.hecate.wear.Wire`.
 *
 * Duplicated rather than shared through `:core` on purpose: `:core` is pure
 * algorithm and must not grow knowledge of a transport. The two copies are ten
 * constants and are checked against each other by the one integration test that
 * matters — an actual session arriving.
 */
object Wire {
    const val PATH_SESSION_PREFIX = "/hecate/session/"
    const val PATH_BUNDLE = "/hecate/bundle"
    const val PATH_ACK = "/hecate/ack"
    const val PATH_RESEND = "/hecate/resend"

    const val KEY_SESSION_ASSET = "session"
    const val KEY_SESSION_ID = "sessionId"
    const val KEY_STARTED_AT = "startedAt"
    const val KEY_BUNDLE_ASSET = "bundle"
    const val KEY_BUNDLE_VERSION = "version"
    const val KEY_ATTEMPT = "attempt"

    const val PATH_WATCH_VERSION = "/hecate/watch-version"
    const val KEY_VERSION_CODE = "versionCode"
    const val KEY_VERSION_NAME = "versionName"
    const val PATH_APK = "/hecate/apk"

    /** Capability a watch build advertises once it can receive [PATH_APK]
     *  (wear `res/values/wear.xml`). A watch without it is too old: nothing
     *  there reads the channel, and the phone's write would only hang. */
    const val CAPABILITY_APK = "hecate_apk"

    /** The watch closes the APK channel with one of these codes, so the phone
     *  learns whether the build was kept. */
    const val APK_ACCEPTED = 0
    const val APK_REJECTED = 1
}
