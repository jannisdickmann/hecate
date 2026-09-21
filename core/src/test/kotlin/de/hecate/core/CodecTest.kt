package de.hecate.core

import de.hecate.core.cluster.BundleBuilder
import de.hecate.core.geo.Track
import de.hecate.core.io.BundleCodec
import de.hecate.core.io.SessionCodec
import de.hecate.core.model.SessionEndReason
import de.hecate.core.model.SessionEvent
import de.hecate.core.model.SessionEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecTest {

    /**
     * The blob encoding the phone stores lifecycle events in.
     *
     * Every event type, because the enum is the contract: a type that fails to
     * round-trip is a type the phone would drop on read, and the whole point of
     * keeping these is the one that turns up unexpectedly.
     */
    @Test
    fun `every session event type round-trips through the blob encoding`() {
        val events = SessionEventType.entries.mapIndexed { i, type ->
            SessionEvent(tOffsetMs = i * 1000L, type = type, note = "n$i")
        }
        val back = BundleCodec.decodeEvents(BundleCodec.encodeEvents(events))
        assertEquals(events, back)
    }

    /** A session with no events encodes to nothing and reads back as nothing,
     *  rather than as one blank entry. */
    @Test
    fun `an empty event list round-trips as empty`() {
        assertEquals(emptyList(), BundleCodec.decodeEvents(BundleCodec.encodeEvents(emptyList())))
    }

    /** `note` is nullable and omitted when absent; it must not come back as "null". */
    @Test
    fun `a session event without a note round-trips as null`() {
        val e = SessionEvent(tOffsetMs = 42, type = SessionEventType.GPS_LOST)
        val back = BundleCodec.decodeEvents(BundleCodec.encodeEvents(listOf(e)))
        assertEquals(listOf(e), back)
        assertNull(back.single().note)
    }

    @Test
    fun `a session round-trips through jsonl`() {
        val s = Synthetic.record("s1", Synthetic.workRoute, Synthetic.MONDAY_0730_UTC, seed = 1)
        val text = SessionCodec.encodeSession(s)
        val back = SessionCodec.decodeSession(text.lineSequence())
        assertNotNull(back)
        assertEquals(s.header, back.header)
        assertEquals(s.footer, back.footer)
        assertEquals(s.samples.size, back.samples.size)
        assertEquals(s.samples.first(), back.samples.first())
        assertEquals(s.samples.last(), back.samples.last())
    }

    /**
     * The auto-stop's cut, which lives in the footer because the file it
     * describes may not be rewritten (A.30).
     *
     * An older recording has no such field and must read back as one that was
     * never cut — `null`, not zero, which would trim every position it has.
     */
    @Test
    fun `the footer carries the auto-stop's cut, and its absence`() {
        val s = Synthetic.record("s-cut", Synthetic.workRoute, Synthetic.MONDAY_0730_UTC, seed = 3)
        val stopped = s.copy(
            footer = s.footer!!.copy(
                reason = SessionEndReason.AUTO_STOPPED,
                trimmedAfterTOffsetMs = 120_000L,
            ),
        )
        val back = SessionCodec.decodeSession(SessionCodec.encodeSession(stopped).lineSequence())
        assertNotNull(back)
        assertEquals(120_000L, back.footer?.trimmedAfterTOffsetMs)
        assertEquals(SessionEndReason.AUTO_STOPPED, back.footer?.reason)

        val plain = SessionCodec.decodeSession(SessionCodec.encodeSession(s).lineSequence())
        assertNull(assertNotNull(plain).footer?.trimmedAfterTOffsetMs)
    }

    @Test
    fun `a torn final line is dropped, not fatal`() {
        // NFR-1: a session cut off by a power failure must still be readable.
        val s = Synthetic.record("s2", Synthetic.workRoute, Synthetic.MONDAY_0730_UTC, seed = 2)
        val text = SessionCodec.encodeSession(s)
        val truncated = text.substring(0, text.length - 40)
        val back = SessionCodec.decodeSession(truncated.lineSequence())
        assertNotNull(back)
        assertTrue(back.samples.size >= s.samples.size - 2)
    }

    @Test
    fun `a file without a header is not a session`() {
        assertNull(SessionCodec.decodeSession(sequenceOf("""{"k":"s","v":{"t":0,"a":0,"lat":1.0,"lon":2.0}}""")))
    }

    @Test
    fun `a file without a footer still yields a session`() {
        val s = Synthetic.record("s3", Synthetic.workRoute, Synthetic.MONDAY_0730_UTC, seed = 3)
        val lines = SessionCodec.encodeSession(s).lines().filterNot { it.contains("\"k\":\"f\"") }
        val back = SessionCodec.decodeSession(lines.asSequence())
        assertNotNull(back)
        assertNull(back.footer)
        assertTrue(back.activeDurationMs > 0, "duration must be recoverable without a footer")
    }

    @Test
    fun `a bundle round-trips through gzip`() {
        val sessions = (0 until 3).map {
            Synthetic.record("m$it", Synthetic.workRoute, Synthetic.MONDAY_0730_UTC + it * Synthetic.DAY_MS, seed = it + 1)
        }
        val bundle = BundleBuilder.bundleFrom(sessions.mapNotNull { Track.of(it) }, sessions.associate { it.id to it.samples })
        val out = java.io.ByteArrayOutputStream()
        BundleCodec.writeGzip(bundle, out)
        val back = BundleCodec.readGzip(java.io.ByteArrayInputStream(out.toByteArray()))
        assertEquals(bundle, back)
        assertTrue(out.size() < 200_000, "bundle unexpectedly large: ${out.size()} bytes")
    }
}
