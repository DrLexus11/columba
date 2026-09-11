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
}
