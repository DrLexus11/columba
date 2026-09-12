package network.columba.app.service.tak

import network.columba.app.service.PositionCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Position on the typed path, which is 94% of everything ATAK emits. */
class CotPositionTest {
    private val pli =
        """<event uid="ANDROID-7819dadfcf858641" type="a-f-G-U-C" how="m-g" version="2.0">""" +
            """<point lat="40.9549" lon="29.0934" hae="33.5" ce="44.0" le="9999999.0"/>""" +
            """<detail><takv device="SAMSUNG SM-A546E" os="36" platform="ATAK-CIV" version="5.6"/>""" +
            """<contact callsign="LEXUS" endpoint="*:-1:stcp"/>""" +
            """<__group name="Cyan" role="Team Member"/></detail></event>"""

    private val marker =
        """<event uid="m1" type="a-h-G" how="h-g-i-g-o" version="2.0">""" +
            """<point lat="40.954" lon="29.094" hae="48.0" ce="9999999.0" le="9999999.0"/>""" +
            """<detail><contact callsign="R.1"/></detail></event>"""

    @Test
    fun `a unit report is position and a marker is not`() {
        // A marker is an object on the map, not a unit reporting itself. It
        // goes through tier 2, which carries what the codec has no room for.
        assertTrue(CotPosition.isPosition(pli))
        assertTrue(CotPosition.isPosition(pli.replace("a-f-G-U-C", "a-h-G-U-C")))
        assertFalse(CotPosition.isPosition(marker))
    }

    @Test
    fun `rubbish is not position`() {
        for (bad in listOf("", "<event", "not xml", "<other/>")) {
            assertFalse(bad, CotPosition.isPosition(bad))
        }
    }

    @Test
    fun `a fix carries the fields the wire has room for`() {
        val fix = CotPosition.fixFromCot(pli, senderId = 0x11223344)!!
        assertEquals(409549000, fix.latE7)
        assertEquals(290934000, fix.lonE7)
        assertEquals(44, fix.accuracyM)
        assertTrue(fix.altKnown)
        assertEquals(34, fix.altM)
        assertEquals(0x11223344, fix.senderId)
    }

    @Test
    fun `ataks unknown sentinel is not taken as a measurement`() {
        // As metres of accuracy 9999999 is a claim about the whole planet; as
        // altitude it is a track nine thousand kilometres up.
        val unknown = pli.replace("""ce="44.0"""", """ce="9999999.0"""")
            .replace("""hae="33.5"""", """hae="9999999.0"""")
        val fix = CotPosition.fixFromCot(unknown, senderId = 1)!!
        assertEquals(0, fix.accuracyM)
        assertFalse(fix.altKnown)
    }

    @Test
    fun `an event without a point carries no fix`() {
        assertNull(
            CotPosition.fixFromCot(
                """<event uid="a" type="a-f-G-U-C"><detail/></event>""", senderId = 1))
    }

    @Test
    fun `a malformed event is not an exception`() {
        for (bad in listOf("", "<event", "<other/>")) {
            assertNull(bad, CotPosition.fixFromCot(bad, senderId = 1))
        }
    }

    @Test
    fun `a fix round trips through the wire format`() {
        // The same nineteen-to-twenty-four bytes the firmware and the deck
        // speak. A second dialect of this is how two implementations start
        // disagreeing about where somebody is.
        val fix = CotPosition.fixFromCot(pli, senderId = 0x11223344)!!
        val wire = PositionCodec.encode(fix)
        assertTrue(wire.size <= PositionCodec.WIRE_MAX_LEN)
        val back = PositionCodec.decode(wire)!!
        assertEquals(fix.latE7, back.latE7)
        assertEquals(fix.lonE7, back.lonE7)
        assertEquals(fix.senderId, back.senderId)
    }

    @Test
    fun `the two codecs do not share a first byte`() {
        // The endpoint tells a position report from a tier 2 frame by byte
        // zero. That is cheap and only safe while the versions differ, so the
        // day they collide should be the day this fails rather than the day a
        // track lands in the wrong place.
        assertTrue(CotTier2.VERSION.toInt() != PositionCodec.WIRE_VERSION)
    }

    @Test
    fun `a rendered report parses back as a position event`() {
        val fix = CotPosition.fixFromCot(pli, senderId = 1)!!
        val rendered = CotPosition.buildCot(fix, "urtn-" + "ab".repeat(16), "PEER", 120_000)
        assertTrue(CotPosition.isPosition(rendered))
        val event = CotEvent.parse(rendered)
        assertEquals("urtn-" + "ab".repeat(16), event.getAttribute("uid"))
        val back = CotPosition.fixFromCot(rendered, senderId = 1)!!
        assertEquals(fix.latE7, back.latE7)
        assertEquals(fix.lonE7, back.lonE7)
    }

    @Test
    fun `a rendered report carries a stale time after its start`() {
        // stale is what makes ATAK drop a track it can no longer trust. Too
        // short and every marker flickers out between reports; too long and a
        // node off the air an hour ago is still on the map looking current.
        val fix = CotPosition.fixFromCot(pli, senderId = 1)!!
        val event = CotEvent.parse(CotPosition.buildCot(fix, "urtn-x", "PEER", 120_000))
        assertTrue(event.getAttribute("stale") > event.getAttribute("time"))
    }

    // ---- headings ----
    //
    // CoT states a heading in degrees; the wire format carries tenths. Getting
    // that wrong is silent: the frame encodes, decodes and renders, just
    // pointing somewhere else.

    private fun withTrack(course: String) =
        pli.replace("<contact", """<track course="$course" speed="4.0"/><contact""")

    @Test
    fun `a heading is carried in tenths of a degree`() {
        assertEquals(1800, CotPosition.fixFromCot(withTrack("180.0"), senderId = 1)!!.courseDdeg)
    }

    /**
     * The <track> element of a rendered report, or null.
     *
     * A helper rather than four levels of loop inside the assertion: the DOM
     * walk is incidental to what the test is about, which is that a heading
     * survives the round trip.
     */
    private fun trackOf(cotXml: String): org.w3c.dom.Element? =
        childrenOf(CotEvent.parse(cotXml))
            .firstOrNull { it.tagName == "detail" }
            ?.let { detail -> childrenOf(detail).firstOrNull { it.tagName == "track" } }

    private fun childrenOf(parent: org.w3c.dom.Element): List<org.w3c.dom.Element> {
        val nodes = parent.childNodes
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? org.w3c.dom.Element }
    }

    @Test
    fun `a heading survives the whole round trip`() {
        // Through the codec and back out as CoT. This is the assertion that
        // catches a scale error: due south went out as 18 degrees, which is
        // north-north-east, and nothing anywhere reported a fault.
        for (degrees in listOf(0.0, 12.0, 90.0, 180.0, 271.0, 359.0)) {
            val fix = CotPosition.fixFromCot(withTrack("%.1f".format(degrees)), senderId = 1)!!
            val back = PositionCodec.decode(PositionCodec.encode(fix))!!
            val rendered = CotPosition.buildCot(back, "urtn-x", "PEER", 120_000)
            val track = trackOf(rendered)
            assertNotNull("no track for $degrees", track)
            val got = track!!.getAttribute("course").toDouble()
            // The wire carries 2-degree units, so a heading lands within one.
            val off = minOf(Math.abs(got - degrees), 360 - Math.abs(got - degrees))
            assertTrue("$degrees degrees came back as $got", off <= 2.0)
        }
    }

    @Test
    fun `a full turn is north not south`() {
        // 360 degrees is due north. The firmware normalises before scaling for
        // this reason; the bridge has to hand it a value already in range.
        assertEquals(0, CotPosition.fixFromCot(withTrack("360.0"), senderId = 1)!!.courseDdeg)
    }

    @Test
    fun `speed is centimetres per second`() {
        // Checked beside course because it is the neighbouring field with its
        // own scale, and a unit error there would look identical.
        assertEquals(400, CotPosition.fixFromCot(withTrack("90.0"), senderId = 1)!!.speedCms)
    }

    @Test
    fun `a rendered peer is addressable`() {
        // A peer with no endpoint appears on the map and is absent from the
        // contact list, which is the list an operator picks from to start a
        // chat, send a marker, or dispatch a CASEVAC.
        val fix = CotPosition.fixFromCot(pli, senderId = 1)!!
        val rendered = CotPosition.buildCot(fix, "urtn-x", "PEER", 120_000)
        assertTrue(rendered, rendered.contains("""endpoint="*:-1:stcp""""))
    }

    @Test
    fun `the team is the one this node is on`() {
        // Hard-coding Cyan put every peer in the wrong group on any other
        // team, and group colour is how an operator tells their own people
        // apart at a glance.
        val fix = CotPosition.fixFromCot(pli, senderId = 1)!!
        val rendered = CotPosition.buildCot(fix, "urtn-x", "PEER", 120_000, team = "Magenta")
        assertTrue(rendered, rendered.contains("""name="Magenta""""))
    }

    @Test
    fun `a callsign with markup cannot escape the event`() {
        val fix = CotPosition.fixFromCot(pli, senderId = 1)!!
        val rendered = CotPosition.buildCot(fix, "urtn-x", """PEER"/><script>""", 1000)
        // Parses as one event rather than becoming several, which is what
        // matters: a peer chooses its own callsign and this renders it.
        assertEquals("a-f-G-U-C", CotEvent.parse(rendered).getAttribute("type"))
    }

    @Test
    fun `the first report goes and a chatty client cannot spend the channel`() {
        val gate = CotPosition.PositionGate(intervalMs = 60_000)
        val fix = CotPosition.fixFromCot(pli, senderId = 1)!!
        assertTrue(gate.allows(fix, 0))
        for (second in 1 until 60) {
            assertFalse("at ${second}s", gate.allows(fix, second * 1000L))
        }
        assertEquals(59L, gate.suppressed)
        assertTrue(gate.allows(fix, 60_000))
    }

    @Test
    fun `movement does not wait out the interval`() {
        // A node that has actually moved should not sit behind the floor,
        // because that is exactly when its position matters.
        val gate = CotPosition.PositionGate(intervalMs = 60_000)
        assertTrue(gate.allows(CotPosition.fixFromCot(pli, 1)!!, 0))
        val moved = CotPosition.fixFromCot(pli.replace("""lat="40.9549"""", """lat="40.9600""""), 1)!!
        assertTrue(gate.allows(moved, 1000))
    }

    @Test
    fun `a step below the threshold still waits`() {
        val gate = CotPosition.PositionGate(intervalMs = 60_000)
        assertTrue(gate.allows(CotPosition.fixFromCot(pli, 1)!!, 0))
        val nudged =
            CotPosition.fixFromCot(pli.replace("""lat="40.9549"""", """lat="40.95491""""), 1)!!
        assertFalse(gate.allows(nudged, 1000))
    }

    @Test
    fun `the movement threshold matches the firmware and the deck`() {
        // A gate that disagreed would make a RAD, a phone and the deck report
        // at different rates for no reason anyone could see.
        assertEquals(25, CotPosition.MOVE_THRESHOLD_M)
        assertEquals(2245, CotPosition.MOVE_THRESHOLD_E7)
    }

    /**
     * The wire timestamp was hard-coded to zero, so buildCot's preference for
     * the author's own time never once applied and a replayed or delayed fix
     * was stamped as newly taken.
     */
    @Test
    fun `the event time reaches the wire`() {
        val cot =
            "<event version=\"2.0\" uid=\"ANDROID-1\" type=\"a-f-G-U-C\" how=\"m-g\"" +
                " time=\"2026-09-12T09:00:00.000Z\" start=\"2026-09-12T09:00:00.000Z\"" +
                " stale=\"2026-09-12T09:10:00.000Z\">" +
                "<point lat=\"41.0\" lon=\"29.0\" hae=\"100.0\" ce=\"10.0\" le=\"9999999.0\"/>" +
                "<detail><contact callsign=\"LEXUS\"/></detail></event>"

        val fix = CotPosition.fixFromCot(cot, senderId = 7)!!

        // 2026-09-12T09:00:00Z
        assertEquals(CotPosition.epochSeconds("2026-09-12T09:00:00.000Z"), fix.fixUnixSeconds)
        assertTrue("the time must not be discarded", fix.fixUnixSeconds > 0)
    }

    /**
     * CoT writes UTC with a Z. ATAK emits milliseconds; the standard permits
     * them to be absent, so a peer running something else may omit them.
     */
    @Test
    fun `both CoT time spellings parse and junk does not`() {
        val withMillis = CotPosition.epochSeconds("2026-09-12T09:00:00.000Z")
        val withoutMillis = CotPosition.epochSeconds("2026-09-12T09:00:00Z")

        assertEquals(withMillis, withoutMillis)
        assertTrue(withMillis > 0)
        // 0 is the wire format's own word for unstated, which beats a guess.
        assertEquals(0L, CotPosition.epochSeconds("not a timestamp"))
        assertEquals(0L, CotPosition.epochSeconds(""))
        assertEquals(0L, CotPosition.epochSeconds(null))
    }

    /** An event with no time attribute still encodes, as unstated. */
    @Test
    fun `a missing event time is zero rather than invented`() {
        val cot =
            "<event version=\"2.0\" uid=\"ANDROID-1\" type=\"a-f-G-U-C\" how=\"m-g\">" +
                "<point lat=\"41.0\" lon=\"29.0\" hae=\"100.0\" ce=\"10.0\" le=\"9999999.0\"/>" +
                "</event>"

        val fix = CotPosition.fixFromCot(cot, senderId = 7)!!

        assertEquals(0L, fix.fixUnixSeconds)
    }
}
