package de.hecate.core

import de.hecate.core.cluster.BundleBuilder
import de.hecate.core.geo.Track
import de.hecate.core.io.BundleCodec
import de.hecate.core.io.SessionCodec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodecTest {

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
