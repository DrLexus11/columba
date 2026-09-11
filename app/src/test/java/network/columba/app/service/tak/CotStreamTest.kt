package network.columba.app.service.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Framing CoT out of a TCP stream, which is where CoT is usually lost. */
class CotStreamTest {
    private val a = """<event uid="a" type="a-f-G"><point lat="1" lon="2"/><detail/></event>"""
    private val b = """<event uid="b" type="a-h-G"><point lat="3" lon="4"/><detail/></event>"""

    private fun CotStream.feed(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    @Test
    fun `one event in one segment`() {
        assertEquals(listOf(a), CotStream().feed(a))
    }

    @Test
    fun `several events in one segment`() {
        assertEquals(listOf(a, b), CotStream().feed(a + b))
    }

    @Test
    fun `an event split across every possible boundary`() {
        // A read() is not a message. Splitting mid-attribute is the case that
        // breaks naive readers, and TCP will produce it under load.
        val bytes = a.toByteArray(Charsets.UTF_8)
        for (cut in 1 until bytes.size) {
            val stream = CotStream()
            val first = stream.feed(bytes.copyOfRange(0, cut))
            val second = stream.feed(bytes.copyOfRange(cut, bytes.size))
            assertEquals("split at $cut", listOf(a), first + second)
        }
    }

    @Test
    fun `a byte at a time`() {
        val stream = CotStream()
        val bytes = (a + b).toByteArray(Charsets.UTF_8)
        val got = mutableListOf<String>()
        for (index in bytes.indices) {
            got += stream.feed(byteArrayOf(bytes[index]))
        }
        assertEquals(listOf(a, b), got)
    }

    @Test
    fun `an event split mid character survives`() {
        // The framer scans bytes but hands back text. Decoding each fragment
        // as UTF-8 would substitute U+FFFD across the boundary and quietly
        // corrupt any callsign that is not ASCII -- which here it always is:
        // the operators are Turkish.
        val event = """<event uid="a"><detail><contact callsign="Gokturk-CAGRI"/></detail></event>"""
            .replace("Gokturk", "Göktürk")
        val bytes = event.toByteArray(Charsets.UTF_8)
        for (cut in 1 until bytes.size) {
            val stream = CotStream()
            val got = stream.feed(bytes.copyOfRange(0, cut)) +
                stream.feed(bytes.copyOfRange(cut, bytes.size))
            assertEquals("split at $cut", listOf(event), got)
        }
    }

    @Test
    fun `whitespace and declarations between documents are ignored`() {
        val noisy = "<?xml version=\"1.0\"?>\n" + a + "\r\n  " + b
        assertEquals(listOf(a, b), CotStream().feed(noisy))
    }

    @Test
    fun `nothing is emitted until an event closes`() {
        val stream = CotStream()
        assertEquals(emptyList<String>(), stream.feed(a.dropLast(1)))
        assertEquals(listOf(a), stream.feed(a.takeLast(1)))
    }

    @Test
    fun `noise without an event does not accumulate`() {
        val stream = CotStream()
        repeat(500) { stream.feed("garbage that is not cot at all") }
        assertTrue("held ${stream.pending}", stream.pending <= CotStream.EVENT_OPEN.length)
    }

    @Test
    fun `a runaway event is abandoned, not the connection`() {
        // One peer sending a document that never ends must not cost us the
        // events that follow it.
        val stream = CotStream(maxEventBytes = 1024)
        stream.feed("""<event uid="huge" """ + "x".repeat(4096))
        assertEquals(0, stream.pending)
        assertEquals(listOf(b), stream.feed(b))
    }

    @Test
    fun `an empty read yields nothing and holds nothing`() {
        val stream = CotStream()
        assertEquals(emptyList<String>(), stream.feed(ByteArray(0)))
        assertEquals(0, stream.pending)
    }

    /**
     * The cap used to be checked only while waiting for a closing tag, so an
     * oversized event that arrived complete was forwarded anyway -- a peer
     * bypassed the bound simply by closing the document.
     */
    @Test
    fun `a complete event past the cap is dropped`() {
        val stream = CotStream(maxEventBytes = 512)
        val huge = "<event uid=\"big\">" + "x".repeat(2000) + "</event>"

        val events = stream.feed(huge.toByteArray(Charsets.UTF_8))

        assertEquals(emptyList<String>(), events)
    }

    /** Dropping one oversized event must not cost the events behind it. */
    @Test
    fun `an event after an oversized one still arrives`() {
        val stream = CotStream(maxEventBytes = 512)
        val huge = "<event uid=\"big\">" + "x".repeat(2000) + "</event>"
        val small = "<event uid=\"small\"/></event>"

        val events = stream.feed((huge + small).toByteArray(Charsets.UTF_8))

        assertEquals(listOf(small), events)
    }

    /**
     * The case a span-drop misses: the runaway never closes, so the close tag
     * found belongs to the *next* event and the span covers both. Dropping the
     * span discards the good event with the runaway.
     */
    @Test
    fun `an unterminated runaway sharing a read keeps the good event`() {
        val stream = CotStream(maxEventBytes = 512)
        val runaway = "<event uid=\"runaway\" " + "y".repeat(2000)
        val good = "<event uid=\"good\" type=\"a-h-G\"><detail/></event>"

        val events = stream.feed((runaway + good).toByteArray(Charsets.UTF_8))

        assertEquals(listOf(good), events)
    }

    @Test
    fun `a runaway alone does not cost what follows it`() {
        val stream = CotStream(maxEventBytes = 512)
        assertEquals(
            emptyList<String>(),
            stream.feed(("<event uid=\"r\" " + "y".repeat(2000)).toByteArray(Charsets.UTF_8)),
        )
        assertEquals(listOf(b), stream.feed(b))
    }

    @Test
    fun `resynchronising drains an oversized read inside one call`() {
        // Resynchronising once per feed() left the rest of a large read
        // sitting in the buffer, draining a few bytes per call.
        val stream = CotStream(maxEventBytes = 64)
        assertEquals(emptyList<String>(), stream.feed("<event ".repeat(200)))
        assertTrue("held ${stream.pending}", stream.pending <= 64 + CotStream.EVENT_OPEN.length)
    }

    @Test
    fun `an event carrying invalid utf8 is dropped and counted, not repaired`() {
        // String(bytes, UTF_8) substitutes U+FFFD, so a bad byte inside an
        // otherwise valid event became a replacement character that passed
        // every later check and reached the mesh as content nobody sent.
        val stream = CotStream()
        val good = "<event uid=\"a\" type=\"a-f-G\"><detail/></event>"
        val bytes = good.toByteArray(Charsets.UTF_8)
        val broken = bytes.copyOfRange(0, 20) + byteArrayOf(-1, -2) + bytes.copyOfRange(20, bytes.size)

        val events = stream.feed(broken)

        assertEquals(emptyList<String>(), events)
        assertEquals(1L, stream.malformed)
        // And the connection carries on.
        assertEquals(listOf(good), stream.feed(good))
    }

    /** An event exactly at the cap is legitimate and must still be delivered. */
    @Test
    fun `an event at the cap is kept`() {
        val prefix = "<event uid=\"fits\">"
        val suffix = "</event>"
        val padding = 512 - prefix.length - suffix.length
        val exact = prefix + "x".repeat(padding) + suffix
        val stream = CotStream(maxEventBytes = 512)

        val events = stream.feed(exact.toByteArray(Charsets.UTF_8))

        assertEquals(listOf(exact), events)
    }
}
