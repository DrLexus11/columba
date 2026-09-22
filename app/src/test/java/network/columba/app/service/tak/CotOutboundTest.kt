package network.columba.app.service.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * A marker whose remarks will not compress into one packet.
     *
     * Deterministic, so the test does not depend on luck: a fixed-seed
     * sequence of letters defeats the tier-2 dictionary the way a real
     * drawing's coordinates do.
     */
    private fun oversizedMarker(): String {
        val random = java.util.Random(42)
        val remarks = (1..900).map { ('a' + random.nextInt(26)) }.joinToString("")
        return """<event uid="0b3c1a2d-4e5f-4a6b-8c7d-9e0f1a2b3c4d" type="a-h-G" """ +
            """how="h-g-i-g-o" version="2.0"><point lat="40.954" lon="29.094" """ +
            """hae="48.0" ce="9999999.0" le="9999999.0"/><detail>""" +
            """<contact callsign="R.10.144053"/><remarks>$remarks</remarks>""" +
            """</detail></event>"""
    }

    /**
     * After one oversized event, our own echo is still refused -- not cut up
     * and put on the air in pieces.
     *
     * The size refusal lived in a field. The echo guard returns early without
     * resetting it, so the next event after an oversized one read a stale
     * "too large" and was fragmented: our own report, sent back out. The
     * answer now belongs to the call that produced it.
     */
    @Test
    fun `an echo after an oversized event is refused, not fragmented`() {
        val outbound = CotOutbound(ours)
        outbound.frame(pli(atak))

        val pieces = outbound.frames(oversizedMarker())
        assertTrue("the oversized event should have been cut up", pieces.size > 1)

        assertTrue(
            "our own echo went out in pieces",
            outbound.frames(pli(ours)).isEmpty(),
        )
    }

    /** Malformed input after an oversized event is refused the same way. */
    @Test
    fun `malformed input after an oversized event is not fragmented`() {
        val outbound = CotOutbound(ours)
        outbound.frames(oversizedMarker())

        assertTrue(outbound.frames("<not cot").isEmpty())
    }

    /** An ordinary event is still one frame, unaffected by what came before. */
    @Test
    fun `an ordinary event after an oversized one is one frame`() {
        val outbound = CotOutbound(ours)
        outbound.frames(oversizedMarker())

        assertEquals(1, outbound.frames(pli(atak)).size)
    }
}
