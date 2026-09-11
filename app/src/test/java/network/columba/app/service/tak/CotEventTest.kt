package network.columba.app.service.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The echo guard and the UID rewrite, mirroring tests/test_cot_endpoint.py. */
class CotEventTest {
    private val atak = "ANDROID-7819dadfcf858641"
    private val ours = "urtn-" + "ab".repeat(16)

    private val pli =
        """<event uid="$atak" type="a-f-G-U-C" how="m-g" version="2.0">""" +
            """<point lat="40.95" lon="29.09" hae="33.5" ce="44.0" le="9999999.0"/>""" +
            """<detail><takv device="SAMSUNG SM-A546E" os="36" platform="ATAK-CIV" version="5.6"/>""" +
            """<contact callsign="LEXUS" endpoint="*:-1:stcp"/><uid Droid="LEXUS"/>""" +
            """<__group name="Cyan" role="Team Member"/></detail></event>"""

    private val marker =
        """<event uid="66d6de40-bd62-4a5e-92ef-d4ee14b0194a" type="a-h-G" how="h-g-i-g-o" """ +
            """version="2.0"><point lat="40.954" lon="29.094" hae="48.0" ce="9999999.0" """ +
            """le="9999999.0"/><detail><contact callsign="R.10.144053"/>""" +
            """<creator callsign="LEXUS" uid="$atak"/></detail></event>"""

    @Test
    fun `our own event is recognised`() {
        val own = """<event uid="${"urtn-" + "00".repeat(16)}" type="a-f-G-U-C"><detail/></event>"""
        assertTrue(CotEvent.isSelfAddressed(own, "urtn-" + "00".repeat(16)))
        assertFalse(CotEvent.isSelfAddressed(pli, "urtn-" + "00".repeat(16)))
    }

    @Test
    fun `a uid appearing only in the detail is not us`() {
        // A marker's <creator uid="..."> names whoever dropped it. Treating
        // that as self-addressed would silently stop forwarding their markers.
        val theirs =
            """<event uid="marker-1" type="a-h-G"><detail>""" +
                """<creator uid="${"urtn-" + "00".repeat(16)}"/></detail></event>"""
        assertFalse(CotEvent.isSelfAddressed(theirs, "urtn-" + "00".repeat(16)))
    }

    @Test
    fun `no uid configured means nothing is self addressed`() {
        assertFalse(CotEvent.isSelfAddressed(pli, ""))
        assertFalse(CotEvent.isSelfAddressed(pli, null))
    }

    @Test
    fun `an angle bracket inside an earlier attribute does not hide the uid`() {
        // A closing angle bracket is legal unescaped in an XML attribute
        // value. A scan that stopped at the first one would miss the uid and
        // forward our own event straight back into the mesh.
        val awkward = """<event how="a>b" uid="$ours" type="a-f-G-U-C"><detail/></event>"""
        assertTrue(CotEvent.isSelfAddressed(awkward, ours))
    }

    @Test
    fun `an attribute whose name ends in uid is not the uid`() {
        val nested = """<event parent_uid="$ours" uid="other" type="a-f-G"><detail/></event>"""
        assertFalse(CotEvent.isSelfAddressed(nested, ours))
    }

    @Test
    fun `our self report gets a reticulum rooted uid`() {
        // Otherwise the derived identity exists only in the codebase and never
        // reaches a track anybody looks at.
        val out = CotEvent.rewriteSelfUid(pli, atak, ours)
        assertEquals(ours, CotEvent.parse(out).getAttribute("uid"))
        // The rest of the event is untouched, byte for byte.
        assertEquals(pli.replace(atak, ours), out)
    }

    @Test
    fun `a marker keeps its own uid`() {
        // A marker is a distinct object that happens to have been created
        // here. Rewriting them would collapse every marker this node ever
        // dropped into a single track.
        val out = CotEvent.rewriteSelfUid(marker, atak, ours)
        assertEquals("66d6de40-bd62-4a5e-92ef-d4ee14b0194a", CotEvent.parse(out).getAttribute("uid"))
        // The creator uid in the detail names our device and must survive too:
        // matching on it would rewrite every marker we drop.
        assertEquals(marker, out)
    }

    @Test
    fun `nothing is rewritten before the atak uid is known`() {
        for ((atakUid, ourUid) in listOf(
            "" to ours,
            null to ours,
            "ANDROID-x" to "",
            "ANDROID-x" to null,
        )) {
            assertEquals(pli, CotEvent.rewriteSelfUid(pli, atakUid, ourUid))
        }
    }

    @Test
    fun `the atak uid is learned from a self report`() {
        // Nothing configures it: ATAK announces it in every position report,
        // and a setting an operator must type is a setting that can be wrong.
        assertEquals(atak, CotEvent.learnAtakUid(pli))
    }

    @Test
    fun `a marker never teaches us a device uid`() {
        assertNull(CotEvent.learnAtakUid(marker))
    }

    @Test
    fun `malformed input is not an error`() {
        for (bad in listOf(
            "",
            "<event",
            "not xml at all",
            "<other/>",
            """<!DOCTYPE event [<!ENTITY x "boom">]><event uid="a"/>""",
        )) {
            assertNull(bad, CotEvent.learnAtakUid(bad))
        }
    }

    @Test
    fun `a declaration is refused rather than parsed`() {
        assertTrue(
            runCatching {
                CotEvent.parse("""<!DOCTYPE event [<!ENTITY x "boom">]><event uid="a"/>""")
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }
}
