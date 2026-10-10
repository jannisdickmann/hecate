package de.hecate.phone.db

import de.hecate.core.Tuning
import de.hecate.core.geo.Track
import de.hecate.core.io.BundleCodec
import de.hecate.core.model.Altitudes
import de.hecate.core.model.HeartRates
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.Sample

/*
 * The mapping between a recording in :core's shape and the rows it is stored as,
 * written out once.
 *
 * Ingest and restore both write a recording, and four readers turn its sample
 * rows back into :core samples. Each used to carry its own copy of the mapping,
 * which is how a column gets added to three of them and goes quietly missing on
 * the fourth path.
 */

/** A stored position in :core's shape. */
fun SampleEntity.toSample() = Sample(
    tOffsetMs = tOffsetMs,
    activeMs = activeMs,
    lat = lat,
    lon = lon,
    altM = altM,
    hrBpm = hrBpm,
    accuracyM = accuracyM,
    paused = paused,
)

/** A position as the row it is stored as. */
fun Sample.toEntity(sessionId: String) = SampleEntity(
    sessionId = sessionId,
    tOffsetMs = tOffsetMs,
    activeMs = activeMs,
    lat = lat,
    lon = lon,
    altM = altM,
    hrBpm = hrBpm,
    accuracyM = accuracyM,
    paused = paused,
)

/**
 * The positions with the separately recorded heart rate and altitude joined
 * onto them: everything downstream wants one row per position carrying the
 * effort that was being made at it. A session with no separate series — an
 * import, or a recording from before the watch wrote one — comes through
 * unchanged. See `HeartRates.merge`.
 */
fun RecordedSession.mergedSamples(): List<Sample> =
    Altitudes.merge(HeartRates.merge(samples, heartRates), altitudes)

/**
 * Store what a recording carries besides its positions: the match log, the
 * lifecycle events and the sensors' own series, each only when there is any.
 *
 * Call it inside the same transaction as the samples: these are part of the
 * recording, not a commentary on it, and a session stored without them would be
 * silently incomplete in exactly the way their tables exist to prevent.
 *
 * The heart rate is stored *as well as* being merged into the samples
 * ([mergedSamples]): the merge fills a column every screen already reads, and
 * the series is what lets the join be redone when the tolerance changes.
 * Altitude is stored instead of being merged, because merging it is the thing
 * that loses it — six readings in seven land nowhere near a position.
 */
suspend fun SessionDao.insertBlobs(sessionId: String, session: RecordedSession) {
    if (session.matchLog.isNotEmpty()) {
        upsertMatchLog(MatchLogEntity(sessionId, BundleCodec.encodeMatchLog(session.matchLog).utf8()))
    }
    if (session.events.isNotEmpty()) {
        upsertEvents(SessionEventsEntity(sessionId, BundleCodec.encodeEvents(session.events).utf8()))
    }
    if (session.heartRates.isNotEmpty() || session.altitudes.isNotEmpty() || session.speeds.isNotEmpty()) {
        upsertSeries(
            SessionSeriesEntity(
                sessionId = sessionId,
                heartRates = session.heartRates.takeIf { it.isNotEmpty() }?.let { BundleCodec.encodeRates(it).utf8() },
                altitudes = session.altitudes.takeIf { it.isNotEmpty() }?.let { BundleCodec.encodeReadings(it).utf8() },
                speeds = session.speeds.takeIf { it.isNotEmpty() }?.let { BundleCodec.encodeReadings(it).utf8() },
            ),
        )
    }
}

/**
 * A stored blob read back through [decode]; empty when there is no blob or it
 * does not parse. A detail screen or a backup is better off without one
 * session's match log than failing over it.
 */
fun <T> ByteArray?.decodeOrEmpty(decode: (String) -> List<T>): List<T> =
    this?.let { runCatching { decode(it.toString(Charsets.UTF_8)) }.getOrNull() } ?: emptyList()

/**
 * A stored session that is, by its clock, the same workout as one starting at
 * [startedAtUtc] and lasting [activeDurationMs] — or null when there is none.
 *
 * §7: the one start-time test, for the watch's own double recordings at ingest
 * and for imports in a restored archive alike. See [Track.likelyDuplicate].
 */
suspend fun SessionDao.duplicateOf(startedAtUtc: Long, activeDurationMs: Long): SessionEntity? =
    near(startedAtUtc, Tuning.DUPLICATE_START_WINDOW_MS).firstOrNull {
        Track.likelyDuplicate(it.startedAtUtc, it.activeDurationMs, startedAtUtc, activeDurationMs)
    }

private fun String.utf8(): ByteArray = toByteArray(Charsets.UTF_8)
