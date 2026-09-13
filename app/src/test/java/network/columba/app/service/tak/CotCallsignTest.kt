package network.columba.app.service.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The name this node announces to the team.
 *
 * A node announcing a name nobody chose is worse than it looks: peers draw it
 * on the map and address chat by it, so the whole team sees a label the
 * operator never picked and cannot correct. Seen on hardware 2026-09-13, where
 * every handset announced "COLUMBA" -- a constant in the source, not a name --
 * and the command post's map showed that word for whoever was carrying it.
 */
class CotCallsignTest {
    private fun selfReport(callsign: String) =
        """<event version="2.0" uid="ANDROID-abc123" type="a-f-G-U-C" how="m-g"
           time="2026-09-13T10:00:00.000Z" start="2026-09-13T10:00:00.000Z"
           stale="2026-09-13T10:05:00.000Z">
           <point lat="41.0" lon="29.0" hae="0" ce="9999999" le="9999999"/>
           <detail>
             <takv device="phone" platform="ATAK-CIV" os="34" version="5.0"/>
             <contact callsign="$callsign" endpoint="*:-1:stcp"/>
           </detail></event>"""

    @Test
    fun `the operator's callsign is read from ATAK's own report`() {
        assertEquals("LEXUS", CotEvent.learnAtakCallsign(selfReport("LEXUS")))
    }

    @Test
    fun `a marker's contact is not the device that made it`() {
        // The same takv gate learnAtakUid uses. A marker carries a <contact>
        // too, and adopting that name would rename this node after whatever
        // was last placed on the map.
        val marker =
            """<event version="2.0" uid="marker-1" type="a-f-G-U-C-I" how="h-g-i-g-o"
               time="2026-09-13T10:00:00.000Z" start="2026-09-13T10:00:00.000Z"
               stale="2026-09-14T10:00:00.000Z">
               <point lat="41.0" lon="29.0" hae="0" ce="9999999" le="9999999"/>
               <detail><contact callsign="N.13.103033"/></detail></event>"""
        assertNull(CotEvent.learnAtakCallsign(marker))
    }

    @Test
    fun `an empty callsign is not a callsign`() {
        assertNull(CotEvent.learnAtakCallsign(selfReport("")))
    }

    @Test
    fun `rubbish in is null out, not an exception`() {
        assertNull(CotEvent.learnAtakCallsign("not xml at all"))
        assertNull(CotEvent.learnAtakCallsign("<event/>"))
    }

    @Test
    fun `the pipeline follows a callsign that changes mid-session`() {
        // Unlike the UID, which is latched. An operator who renames themselves
        // should be renamed on every peer's map, rather than leaving the team
        // addressing a name they have abandoned.
        val pipeline = CotOutbound("urtn-ourselves")
        pipeline.observe(selfReport("LEXUS"))
        assertEquals("LEXUS", pipeline.atakCallsign)
        pipeline.observe(selfReport("RESCUE-1"))
        assertEquals("RESCUE-1", pipeline.atakCallsign)
    }

    @Test
    fun `the uid is still learned once and kept`() {
        val pipeline = CotOutbound("urtn-ourselves")
        pipeline.observe(selfReport("LEXUS"))
        assertEquals("ANDROID-abc123", pipeline.atakUid)
    }
}
