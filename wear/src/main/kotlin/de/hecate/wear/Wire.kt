package de.hecate.wear

/**
 * The watch↔phone contract. One place, so the two modules cannot drift apart.
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
}
