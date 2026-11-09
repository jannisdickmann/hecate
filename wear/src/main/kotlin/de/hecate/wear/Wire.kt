package de.hecate.wear

/**
 * The watch↔phone contract, as the watch speaks it.
 *
 * Not the only copy: `de.hecate.phone.Wire` mirrors it, on purpose, because
 * `:core` is pure algorithm and must not grow knowledge of a transport. Nothing
 * checks the two against each other but a session actually arriving, so a change
 * to any value here is a change to both files.
 *
 * Everything travels over the Wearable Data Layer: `DataClient` items for
 * payloads that must survive being out of range (§4 — the at-least-once
 * behaviour US-2 needs comes free), `MessageClient` for acknowledgements, which
 * are cheap to lose because the next upload attempt simply repeats.
 */
object Wire {
    /** Data item path prefix for an uploaded session: `/hecate/session/<uuid>`. */
    const val PATH_SESSION_PREFIX = "/hecate/session/"

    /** Data item carrying the route bundle, phone → watch. */
    const val PATH_BUNDLE = "/hecate/bundle"

    /** Message path for "this session is safely in the database", phone → watch.
     *  The payload is the session UUID in UTF-8. */
    const val PATH_ACK = "/hecate/ack"

    /** Message path for "send me everything you still hold", phone → watch. */
    const val PATH_RESEND = "/hecate/resend"

    const val KEY_SESSION_ASSET = "session"
    const val KEY_SESSION_ID = "sessionId"
    const val KEY_STARTED_AT = "startedAt"
    const val KEY_BUNDLE_ASSET = "bundle"
    const val KEY_BUNDLE_VERSION = "version"
    /** Bumped on every upload attempt so the Data Layer sees a changed item even
     *  when the payload is byte-identical. */
    const val KEY_ATTEMPT = "attempt"

    /** Data item carrying the watch's installed version, watch → phone (A.34),
     *  so the phone can say when a newer watch build has been released. */
    const val PATH_WATCH_VERSION = "/hecate/watch-version"
    const val KEY_VERSION_CODE = "versionCode"
    const val KEY_VERSION_NAME = "versionName"

    /** Channel path for a newer watch APK, phone → watch (A.35). */
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
