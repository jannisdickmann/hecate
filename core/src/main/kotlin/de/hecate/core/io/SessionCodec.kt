package de.hecate.core.io

import de.hecate.core.model.HeartRate
import de.hecate.core.model.MatchLogEntry
import de.hecate.core.model.Reading
import de.hecate.core.model.RecordedSession
import de.hecate.core.model.Sample
import de.hecate.core.model.SessionEvent
import de.hecate.core.model.SessionFooter
import de.hecate.core.model.SessionHeader
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One line of a session file. A sealed hierarchy with a one-character class
 * discriminator: the watch appends one of these per event and never rewrites
 * anything, which is what makes the format crash-safe (NFR-1). A truncated final
 * line is simply dropped on read.
 */
@Serializable
sealed interface SessionRecord {
    @Serializable
    @SerialName("h")
    data class Header(val v: SessionHeader) : SessionRecord

    @Serializable
    @SerialName("s")
    data class SampleLine(val v: Sample) : SessionRecord

    @Serializable
    @SerialName("e")
    data class EventLine(val v: SessionEvent) : SessionRecord

    @Serializable
    @SerialName("m")
    data class MatchLine(val v: MatchLogEntry) : SessionRecord

    @Serializable
    @SerialName("f")
    data class Footer(val v: SessionFooter) : SessionRecord

    /**
     * One heart-rate reading at its own time.
     *
     * Separate from [SampleLine] because the sensors are separate: a rate that
     * arrives between two positions, or in a delivery that carried none, used to
     * have nowhere to go and was dropped. See [HeartRate].
     */
    @Serializable
    @SerialName("r")
    data class RateLine(val v: HeartRate) : SessionRecord

    /**
     * One barometric altitude at its own time.
     *
     * Separate from [SampleLine] for the reason [RateLine] is, and more sharply:
     * altitude arrives once every 20 to 60 seconds against a position a second,
     * and it keeps arriving through a GNSS dropout, when there is no position to
     * hang it on at all. See [Reading].
     */
    @Serializable
    @SerialName("b")
    data class AltitudeLine(val v: Reading) : SessionRecord

    /**
     * One fused speed reading [m/s] at its own time.
     *
     * Health Services measures speed rather than inferring it from successive
     * positions, which is strictly better than differencing two fixes with a
     * few metres of error each. It was being requested — and paid for in
     * battery — for a year while only the last value of each delivery was read,
     * for one live cell, and none of it was kept.
     */
    @Serializable
    @SerialName("v")
    data class SpeedLine(val v: Reading) : SessionRecord
}

/**
 * Reads and writes the append-only JSONL session format.
 *
 * Deliberately not a database: a schemaless line-per-record file has no
 * migrations, no write-ahead log of its own, and no way to be left in a
 * half-consistent state by a process death mid-workout (§4).
 */
object SessionCodec {

    val json: Json = Json {
        classDiscriminator = "k"
        encodeDefaults = false
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun encodeLine(record: SessionRecord): String = json.encodeToString(record)

    /** Decodes a single line, or null if it is blank or unparseable (a torn
     *  trailing write). */
    fun decodeLineOrNull(line: String): SessionRecord? {
        val t = line.trim()
        if (t.isEmpty()) return null
        return try {
            json.decodeFromString<SessionRecord>(t)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Assemble a session from its lines. A file without a header yields null; a
     * file without a footer yields a session all the same — an interrupted
     * recording is still a recording.
     */
    fun decodeSession(lines: Sequence<String>): RecordedSession? {
        var header: SessionHeader? = null
        var footer: SessionFooter? = null
        val samples = ArrayList<Sample>()
        val events = ArrayList<SessionEvent>()
        val matches = ArrayList<MatchLogEntry>()
        val rates = ArrayList<HeartRate>()
        val altitudes = ArrayList<Reading>()
        val speeds = ArrayList<Reading>()
        for (line in lines) {
            when (val r = decodeLineOrNull(line)) {
                is SessionRecord.Header -> if (header == null) header = r.v
                is SessionRecord.SampleLine -> samples.add(r.v)
                is SessionRecord.EventLine -> events.add(r.v)
                is SessionRecord.MatchLine -> matches.add(r.v)
                is SessionRecord.Footer -> footer = r.v
                is SessionRecord.RateLine -> rates.add(r.v)
                is SessionRecord.AltitudeLine -> altitudes.add(r.v)
                is SessionRecord.SpeedLine -> speeds.add(r.v)
                null -> Unit
            }
        }
        val h = header ?: return null
        samples.sortBy { it.tOffsetMs }
        rates.sortBy { it.tOffsetMs }
        altitudes.sortBy { it.tOffsetMs }
        speeds.sortBy { it.tOffsetMs }
        return RecordedSession(h, samples, events, footer, matches, rates, altitudes, speeds)
    }

    fun encodeSession(session: RecordedSession): String = buildString {
        appendLine(encodeLine(SessionRecord.Header(session.header)))
        for (s in session.samples) appendLine(encodeLine(SessionRecord.SampleLine(s)))
        for (e in session.events) appendLine(encodeLine(SessionRecord.EventLine(e)))
        for (m in session.matchLog) appendLine(encodeLine(SessionRecord.MatchLine(m)))
        for (r in session.heartRates) appendLine(encodeLine(SessionRecord.RateLine(r)))
        for (a in session.altitudes) appendLine(encodeLine(SessionRecord.AltitudeLine(a)))
        for (v in session.speeds) appendLine(encodeLine(SessionRecord.SpeedLine(v)))
        session.footer?.let { appendLine(encodeLine(SessionRecord.Footer(it))) }
    }
}
