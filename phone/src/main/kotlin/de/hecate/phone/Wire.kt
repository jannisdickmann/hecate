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
}
