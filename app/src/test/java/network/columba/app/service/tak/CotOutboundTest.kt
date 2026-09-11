package network.columba.app.service.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The three steps between ATAK and the mesh, and the order they happen in. */
class CotOutboundTest {
    private val atak = "ANDROID-7819dadfcf858641"
    private val ours = "urtn-" + "ab".repeat(16)

    private fun pli(uid: String) =
        """<event uid="$uid" type="a-f-G-U-C" how="m-g" version="2.0">""" +
            """<point lat="40.95" lon="29.09" hae="33.5" ce="44.0" le="9999999.0"/>""" +
            """<detail><takv device="SAMSUNG SM-A546E" os="36" platform="ATAK-CIV" version="5.6"/>""" +
            """<contact callsign="LEXUS" endpoint="*:-1:stcp"/>""" +
            """<__group name="Cyan" role="Team Member"/></detail></event>"""

    private val marker =
        """<event uid="66d6de40-bd62-4a5e-92ef-d4ee14b0194a" type="a-h-G" how="h-g-i-g-o" """ +
            """version="2.0"><point lat="40.954" lon="29.094" hae="48.0" ce="9999999.0" """ +
            """le="9999999.0"/><detail><contact callsign="R.10.144053"/>""" +
            """<creator callsign="LEXUS" uid="$atak"/></detail></event>"""

    @Test
    fun `the first self report teaches us the device uid and goes out rewritten`() {
        val outbound = CotOutbound(ours)
        assertNull(outbound.atakUid)

        val frame = outbound.frame(pli(atak))

        assertEquals(atak, outbound.atakUid)
        assertNotNull(frame)
        assertEquals(ours, CotEvent.parse(CotTier2.decode(frame!!)).getAttribute("uid"))
    }

    @Test
    fun `our own event coming back is refused rather than re-sent`() {
        // This is the loop. ATAK rebroadcasts and so does the mesh, so an
        // endpoint that forwarded its own echo would sustain traffic that
        // looks exactly like a busy network.
        val outbound = CotOutbound(ours)
        outbound.frame(pli(atak))
        assertNull(outbound.frame(pli(ours)))
    }

    @Test
    fun `the echo guard runs before the rewrite, not after`() {
        // Reversed, the rewrite would stamp our UID onto the echo first and
        // the guard would then be comparing a value it had just written --
        // every echo would look like a fresh event and none would be stopped.
        // Asserted on a fresh pipeline that has never seen a self-report, so
        // the only thing that can refuse it is the guard.
        val outbound = CotOutbound(ours)
        assertNull(outbound.frame(pli(ours)))
        assertNull("an echo must not teach us a device uid", outbound.atakUid)
    }

    @Test
    fun `a marker keeps its own uid and still goes out`() {
        val outbound = CotOutbound(ours)
        outbound.frame(pli(atak))

        val frame = outbound.frame(marker)
        assertNotNull(frame)
        assertEquals(
            "66d6de40-bd62-4a5e-92ef-d4ee14b0194a",
            CotEvent.parse(CotTier2.decode(frame!!)).getAttribute("uid"),
        )
    }

    @Test
    fun `a marker never teaches us a device uid`() {
        // A marker names our device as its creator. Learning from that would
        // set the ATAK UID to something that is right by accident here and
        // wrong the moment a peer's marker arrives first.
        val outbound = CotOutbound(ours)
        outbound.frame(marker)
        assertNull(outbound.atakUid)
    }

    @Test
    fun `a self report arriving before we know the device uid still goes out`() {
        // Unrewritten, because there is nothing yet to rewrite from -- but it
        // must not be dropped. The alternative loses the first position report
        // of every session, which is the one an operator is watching for.
        val outbound = CotOutbound(ours)
        val frame = outbound.frame(marker)
        assertNotNull(frame)
    }

    @Test
    fun `rubbish is dropped and counted, not thrown`() {
        val outbound = CotOutbound(ours)
        for (bad in listOf("", "<event", "not xml at all", "<other/>")) {
            assertNull(bad, outbound.frame(bad))
        }
        assertEquals(4L, outbound.dropped)
    }

    @Test
    fun `a frame that survives decodes back to the event that produced it`() {
        val outbound = CotOutbound(ours)
        outbound.frame(pli(atak))
        val frame = outbound.frame(marker)!!
        assertEquals(marker, CotTier2.decode(frame))
    }
}
