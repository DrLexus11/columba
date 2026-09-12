package network.columba.app.service.tak

import network.columba.app.service.PositionCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Point markers, against events ATAK actually produced in this lab. */
class CotMarkerTest {
    private val vectors: JSONObject =
        JSONObject(
            checkNotNull(javaClass.classLoader.getResourceAsStream("tak_native_v1.json"))
                .bufferedReader().use { it.readText() },
        )
    private val marker = vectors.getJSONObject("marker")
    private val sender = 0x11223344
    private val ours = "urtn-" + "ab".repeat(16)
    private val kinds = listOf("a-h-G", "b-m-p-s-m", "b-m-p-c-cp", "b-m-p-s-p-i")

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun `the frames match the python side`() {
        for (kind in kinds) {
            assertEquals(
                kind,
                marker.getString(kind + "_frame"),
                CotMarker.markerFromCot(marker.getString(kind), sender)!!.hex(),
            )
        }
    }

    @Test
    fun `the version and the spi type match the python side`() {
        assertEquals(marker.getInt("version"), CotMarker.VERSION)
        assertEquals(marker.getString("spi_type"), CotMarker.SPI_TYPE)
    }

    @Test
    fun `every captured marker type is recognised`() {
        for (kind in kinds) assertTrue(kind, CotMarker.isMarker(marker.getString(kind)))
    }

    @Test
    fun `a self report is not a marker`() {
        // It describes the reporter and belongs to the position codec, which
        // has its own cadence. Sending it both ways would double the commonest
        // event there is.
        assertFalse(CotMarker.isMarker(vectors.getJSONObject("tier2").getString("cot")))
    }

    @Test
    fun `a drawing is left for pr c`() {
        assertFalse(
            CotMarker.isMarker(
                """<event uid="d1" type="u-d-f" how="h-e"><point lat="1" lon="2"/>""" +
                    """<detail><shape/></detail></event>""",
            ),
        )
    }

    @Test
    fun `every captured marker shrinks by an order of magnitude`() {
        for (kind in kinds) {
            val frame = CotMarker.markerFromCot(marker.getString(kind), sender)!!
            assertTrue("$kind was ${frame.size}", frame.size < marker.getString(kind).length / 8)
        }
    }

    @Test
    fun `a hostile marker keeps what an operator sees`() {
        val decoded = CotMarker.decode(CotMarker.markerFromCot(marker.getString("a-h-G"), sender))!!
        assertEquals("a-h-G", decoded.type)
        assertEquals("R.11.095131", decoded.callsign)
        assertEquals(410235622, decoded.latE7)
        assertEquals(300, decoded.staleSeconds)
    }

    @Test
    fun `a marker keeps its own uid`() {
        val decoded =
            CotMarker.decode(CotMarker.markerFromCot(marker.getString("b-m-p-s-m"), sender))!!
        assertEquals("cc5434e0-5d5e-48b4-b32a-29c7907dbfad", decoded.uid)
        assertNull(decoded.uidSuffix)
    }

    // ---- SPI: the volume case, and the one that leaks ----

    @Test
    fun `the senders device id does not travel`() {
        val frame = CotMarker.markerFromCot(marker.getString("b-m-p-s-p-i"), sender)!!
        assertFalse(frame.toString(Charsets.ISO_8859_1).contains("ANDROID"))
    }

    @Test
    fun `only the suffix travels, and the uid is rebuilt against the reticulum identity`() {
        // Pivot 1 applied to the one event type that had smuggled a device
        // identifier past it.
        val decoded =
            CotMarker.decode(CotMarker.markerFromCot(marker.getString("b-m-p-s-p-i"), sender))!!
        assertNull(decoded.uid)
        assertEquals("SPI1", decoded.uidSuffix)
        val rebuilt = CotMarker.buildMarkerCot(
            decoded, ours, "PEER", "2026-09-11T20:00:00.000Z", "2026-09-11T20:00:20.000Z")
        assertTrue(rebuilt.contains("""uid="$ours.SPI1""""))
        assertFalse(rebuilt.contains("ANDROID"))
    }

    // ---- stale ----

    @Test
    fun `a short life keeps second precision`() {
        val decoded =
            CotMarker.decode(CotMarker.markerFromCot(marker.getString("b-m-p-s-p-i"), sender))!!
        assertEquals(20, decoded.staleSeconds)
    }

    @Test
    fun `a year long marker does not become a day`() {
        // Sixteen bits of seconds is eighteen hours, and clamping turned a
        // permanent marker into one that vanished overnight.
        val decoded =
            CotMarker.decode(CotMarker.markerFromCot(marker.getString("b-m-p-s-m"), sender))!!
        assertTrue("was ${decoded.staleSeconds}", decoded.staleSeconds > 30 * 86_400)
    }

    @Test
    fun `the boundary between the two units, and nothing wraps`() {
        assertEquals(CotMarker.MAX_STALE_UNITS to false,
                     CotMarker.staleField(CotMarker.MAX_STALE_UNITS))
        val (value, minutes) = CotMarker.staleField(CotMarker.MAX_STALE_UNITS + 1)
        assertTrue(minutes)
        assertEquals((CotMarker.MAX_STALE_UNITS + 60) / 60, value)
        // Fifty years, which fits an Int. A hundred does not, and the
        // overflow made this assert the opposite of what it says.
        val (big, bigMinutes) = CotMarker.staleField(50 * 365 * 86_400)
        assertTrue(big <= CotMarker.MAX_STALE_UNITS)
        assertTrue(bigMinutes)
    }

    @Test
    fun `a nonsensical duration does not become a marker that never expires`() {
        // Kotlin's Int arithmetic wraps where Python's does not, so a duration
        // that is merely large on one side can arrive here negative -- and a
        // negative sailing through the seconds branch encodes as a huge
        // unsigned stale.
        assertEquals(1 to false, CotMarker.staleField(-1))
        assertEquals(1 to false, CotMarker.staleField(Int.MIN_VALUE))
    }

    // ---- the codec itself ----

    @Test
    fun `a marker round trips through a rebuild`() {
        for (kind in listOf("a-h-G", "b-m-p-s-m", "b-m-p-c-cp")) {
            val decoded = CotMarker.decode(CotMarker.markerFromCot(marker.getString(kind), sender))!!
            val rebuilt = CotMarker.buildMarkerCot(
                decoded, ours, "PEER", "2026-09-11T20:00:00.000Z", "2026-09-11T21:00:00.000Z")
            val again = CotMarker.decode(CotMarker.markerFromCot(rebuilt, sender))!!
            assertEquals(kind, decoded.uid, again.uid)
            assertEquals(kind, decoded.type, again.type)
            assertEquals(kind, decoded.callsign, again.callsign)
            assertEquals(kind, decoded.latE7, again.latE7)
            assertEquals(kind, decoded.argb, again.argb)
        }
    }

    @Test
    fun `truncated and padded frames are refused`() {
        val frame = CotMarker.markerFromCot(marker.getString("a-h-G"), sender)!!
        for (cut in 1 until frame.size) {
            assertNull("cut at $cut", CotMarker.decode(frame.copyOfRange(0, cut)))
        }
        assertNull(CotMarker.decode(frame + "extra".toByteArray()))
    }

    @Test
    fun `a frame claiming both a uuid and a suffix is refused`() {
        val frame = CotMarker.markerFromCot(marker.getString("a-h-G"), sender)!!.copyOf()
        // The suffix length is the last byte of the header. Aiming anywhere
        // else corrupts a coordinate and the frame still decodes.
        frame[CotMarker.HEADER_BYTES - 1] = 4
        assertNull(CotMarker.decode(frame))
    }

    @Test
    fun `invalid utf8 is refused, not substituted`() {
        val frame = CotMarker.markerFromCot(marker.getString("a-h-G"), sender)!!.copyOf()
        val callsign = "R.11.095131".toByteArray()
        val at = frame.toList().windowed(callsign.size).indexOfFirst {
            it.toByteArray().contentEquals(callsign)
        }
        assertTrue("callsign not found in the frame", at >= 0)
        frame[at + 2] = 0xFF.toByte()
        assertNull(CotMarker.decode(frame))
    }

    @Test
    fun `markup in a callsign cannot escape the rebuilt event`() {
        val decoded = CotMarker.decode(CotMarker.markerFromCot(marker.getString("a-h-G"), sender))!!
        val nasty = decoded.copy(callsign = """</contact><detail evil="1">""")
        val rebuilt = CotMarker.buildMarkerCot(
            nasty, ours, "PEER", "2026-09-11T20:00:00.000Z", "2026-09-11T21:00:00.000Z")
        assertEquals(nasty.callsign, CotMarker.decode(CotMarker.markerFromCot(rebuilt, sender))!!.callsign)
    }

    @Test
    fun `a marker frame is not mistaken for another codec`() {
        val frame = CotMarker.markerFromCot(marker.getString("a-h-G"), sender)!!
        assertNull(PositionCodec.decode(frame))
        assertNull(CotChat.decode(frame))
        assertTrue(runCatching { CotTier2.decode(frame) }.isFailure)
        assertNotNull(CotMarker.decode(frame))
        assertEquals(TakPayload.MARKER_V1, TakPayload.kindOf(frame))
    }
    // ---- a long note must not take the marker down with it ----

    private fun markerWith(remarks: String) =
        """<event uid="7c9e6679-7425-40de-944b-e07fc1f90ae7" type="a-h-G" """ +
            """how="h-g-i-g-o" version="2.0" """ +
            """start="2026-09-12T09:00:00.000Z" stale="2026-09-12T09:05:00.000Z">""" +
            """<point lat="40.9601" lon="29.1002" hae="48.0" ce="9.0" le="9.0"/>""" +
            """<detail><contact callsign="HOSTILE.7"/>""" +
            """<remarks>$remarks</remarks></detail></event>"""

    @Test
    fun `a long ascii note is cut and the marker survives`() {
        val decoded = CotMarker.decode(CotMarker.markerFromCot(markerWith("A".repeat(400)), 1))
        assertNotNull(decoded)
        assertTrue(decoded!!.remarks.startsWith("A"))
        assertTrue(decoded.remarks.endsWith("\u2026"))
    }

    @Test
    fun `a note cut inside a turkish character still decodes`() {
        // The defect: slicing encoded bytes at a fixed index splits multibyte
        // characters, the far end decodes strictly, and the marker vanishes
        // with no error anywhere. Two-byte characters at every offset walk the
        // cut across a character boundary.
        for (pad in 0 until 8) {
            val note = "x".repeat(pad) + "\u015f".repeat(200)
            val frame = CotMarker.markerFromCot(markerWith(note), 1)
            assertNotNull("no frame at pad $pad", frame)
            val decoded = CotMarker.decode(frame)
            assertNotNull("marker lost at pad $pad", decoded)
            assertTrue(note.startsWith(decoded!!.remarks.trimEnd('\u2026')))
        }
    }

    @Test
    fun `a short note is untouched`() {
        val decoded = CotMarker.decode(
            CotMarker.markerFromCot(markerWith("3 kat, enkaz alt\u0131nda"), 1))
        assertEquals("3 kat, enkaz alt\u0131nda", decoded!!.remarks)
    }
}
