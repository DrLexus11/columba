package network.columba.app.service.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class CotHeartbeatTest {
    private val ping = "<event uid=\"ANDROID-test\" type=\"t-x-c-t\"><detail/></event>"

    @Test
    fun `pong preserves escaped uid and has fresh validity times`() {
        val now = Instant.parse("2026-09-12T00:00:00Z")
        val reply = CotEvent.parse(CotEvent.pingReply(ping.replace("ANDROID-test", "a&amp;&quot;b"), now)!!)
        assertEquals("t-x-c-t-r", reply.getAttribute("type"))
        assertEquals("a&\"b", reply.getAttribute("uid"))
        assertEquals(now.toString(), reply.getAttribute("time"))
        assertEquals(now.toString(), reply.getAttribute("start"))
        assertEquals(now.plusSeconds(60).toString(), reply.getAttribute("stale"))
        assertEquals(1, reply.getElementsByTagName("point").length)
        assertEquals(1, reply.getElementsByTagName("detail").length)
    }

    @Test
    fun `ordinary events and pongs do not receive a reply`() {
        for (xml in listOf(
            ping.replace("t-x-c-t", "a-h-G"),
            ping.replace("t-x-c-t", "t-x-c-t-r"),
            "<event uid=\"t-x-c-t\"><detail type=\"t-x-c-t\"/></event>",
            "<event type=\"t-x-c-t\"><broken></event>",
            "<!DOCTYPE event><event type=\"t-x-c-t\"/>",
        )) assertNull(CotEvent.pingReply(xml))
    }

    @Test
    fun `self closing fragmented ping does not swallow following events`() {
        val short = "<event uid=\"a>b\" type=\"t-x-c-t\"/>"
        val wire = (short + ping).toByteArray()
        for (cut in 0..wire.size) {
            val stream = CotStream()
            val events = stream.feed(wire.copyOfRange(0, cut)) + stream.feed(wire.copyOfRange(cut, wire.size))
            assertEquals("split at $cut", listOf(short, ping), events)
            assertNotNull(CotEvent.pingReply(events.first()))
        }
    }
}
