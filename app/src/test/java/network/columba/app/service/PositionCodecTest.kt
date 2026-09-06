package network.columba.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compact position wire format.
 *
 * Three implementations of one format -- this, `PositionReport.h` in the RAD
 * firmware, and `tools/position_codec.py` for the CoT gateway. A disagreement
 * about a single byte does not fail loudly: it puts a marker somewhere else on
 * a map and lets somebody act on it. So the constants are asserted as literals
 * here rather than read from the object, because a test that reads the value it
 * is checking would follow a mistake rather than catch it.
 */
class PositionCodecTest {
    @Test
    fun `wire constants match the firmware`() {
        assertEquals(1, PositionCodec.WIRE_VERSION)
        assertEquals(15, PositionCodec.WIRE_BASE_LEN)
        assertEquals(20, PositionCodec.WIRE_MAX_LEN)
        assertEquals(0x01, PositionCodec.FLAG_ALT)
        assertEquals(0x02, PositionCodec.FLAG_COURSE)
        assertEquals(0x04, PositionCodec.FLAG_SPEED)
        assertEquals(0x08, PositionCodec.FLAG_SATS)
    }

    @Test
    fun `a bare fix is exactly the base length`() {
        val encoded = PositionCodec.encode(PositionCodec.Fix(latE7 = 411234567, lonE7 = 291234567))
        assertEquals(PositionCodec.WIRE_BASE_LEN, encoded.size)
    }

    @Test
    fun `a full fix stays inside the airtime budget`() {
        // TAKCapability.md §2: about 25 bytes is 44 ms on air and 820 reports
        // an hour channel-wide. Raw CoT XML is 538 ms and 67. That difference
        // is the reason this format exists.
        val encoded =
            PositionCodec.encode(
                PositionCodec.Fix(
                    latE7 = 411234567, lonE7 = 291234567, fixUnixSeconds = 1788681206,
                    accuracyM = 12, altKnown = true, altM = 847, courseKnown = true,
                    courseDdeg = 1800, speedCms = 500, sats = 9,
                ),
            )
        assertEquals(PositionCodec.WIRE_MAX_LEN, encoded.size)
        assertTrue(encoded.size <= 25)
    }

    @Test
    fun `a full fix survives the round trip`() {
        val fix =
            PositionCodec.Fix(
                latE7 = 411234567, lonE7 = 291234567, fixUnixSeconds = 1788681206,
                accuracyM = 12, altKnown = true, altM = 847, courseKnown = true,
                courseDdeg = 1800, speedCms = 500, sats = 9,
            )
        val back = PositionCodec.decode(PositionCodec.encode(fix))
        assertNotNull(back)
        assertEquals(fix.latE7, back!!.latE7)
        assertEquals(fix.lonE7, back.lonE7)
        assertEquals(fix.fixUnixSeconds, back.fixUnixSeconds)
        assertEquals(fix.accuracyM, back.accuracyM)
        assertTrue(back.altKnown)
        assertEquals(fix.altM, back.altM)
        assertTrue(back.courseKnown)
        assertEquals(fix.courseDdeg, back.courseDdeg)
        assertEquals(fix.speedCms, back.speedCms)
        assertEquals(fix.sats, back.sats)
    }

    @Test
    fun `southern and western coordinates survive`() {
        // Signed, not unsigned. Half the planet depends on it, and the bug puts
        // Sydney in Siberia rather than failing.
        val fix = PositionCodec.Fix(latE7 = -337654321, lonE7 = -583654321)
        val back = PositionCodec.decode(PositionCodec.encode(fix))!!
        assertEquals(-337654321, back.latE7)
        assertEquals(-583654321, back.lonE7)
    }

    @Test
    fun `a negative altitude survives`() {
        // Below the ellipsoid is ordinary, and much of the Netherlands is below
        // it by more than a rounding error.
        val fix = PositionCodec.Fix(latE7 = 1, lonE7 = 1, altKnown = true, altM = -320)
        val back = PositionCodec.decode(PositionCodec.encode(fix))!!
        assertTrue(back.altKnown)
        assertEquals(-320, back.altM)
    }

    @Test
    fun `accuracy saturates rather than wrapping`() {
        // Four hundred metres of error arriving as 144 is a marker an operator
        // trusts far more than it deserves.
        val fix = PositionCodec.Fix(latE7 = 1, lonE7 = 1, accuracyM = 400)
        val back = PositionCodec.decode(PositionCodec.encode(fix))!!
        assertEquals(255, back.accuracyM)
    }

    @Test
    fun `an unreported accuracy stays unreported`() {
        // Zero means "not reported". A report claiming zero error would be a
        // stronger claim than any receiver can make.
        val fix = PositionCodec.Fix(latE7 = 1, lonE7 = 1, accuracyM = 0)
        val back = PositionCodec.decode(PositionCodec.encode(fix))!!
        assertEquals(0, back.accuracyM)
    }

    @Test
    fun `unknown altitude and course are absent rather than zero`() {
        // Zero metres is sea level and zero degrees is due north. Both are real
        // values, so neither may stand in for "unknown".
        val fix = PositionCodec.Fix(latE7 = 1, lonE7 = 1)
        val back = PositionCodec.decode(PositionCodec.encode(fix))!!
        assertFalse(back.altKnown)
        assertFalse(back.courseKnown)
    }

    @Test
    fun `a course of due north is preserved and not confused with absence`() {
        val fix = PositionCodec.Fix(latE7 = 1, lonE7 = 1, courseKnown = true, courseDdeg = 0)
        val back = PositionCodec.decode(PositionCodec.encode(fix))!!
        assertTrue(back.courseKnown)
        assertEquals(0, back.courseDdeg)
    }

    @Test
    fun `a full turn does not wrap into a different heading`() {
        // A bearing of 360 degrees is due north, and would otherwise scale to
        // 180 and decode as due south.
        val fix = PositionCodec.Fix(latE7 = 1, lonE7 = 1, courseKnown = true, courseDdeg = 3600)
        val back = PositionCodec.decode(PositionCodec.encode(fix))!!
        assertEquals(0, back.courseDdeg)
    }

    @Test
    fun `a truncated report decodes to nothing`() {
        // These bytes come off a radio. A short frame is ordinary.
        val fix = PositionCodec.Fix(latE7 = 1, lonE7 = 1, altKnown = true, altM = 100)
        val encoded = PositionCodec.encode(fix)
        assertNull(PositionCodec.decode(encoded.copyOf(encoded.size - 1)))
        assertNull(PositionCodec.decode(ByteArray(0)))
        assertNull(PositionCodec.decode(null))
    }

    @Test
    fun `a future version decodes to nothing`() {
        val encoded = PositionCodec.encode(PositionCodec.Fix(latE7 = 1, lonE7 = 1))
        encoded[0] = 99
        assertNull(PositionCodec.decode(encoded))
    }

    @Test
    fun `a timestamp past the signed int boundary survives`() {
        // fix_unix_s is unsigned on the wire. Read back as a signed Int this
        // goes negative in 2038, and a marker stamped in 1901 is stale for
        // ever.
        val fix = PositionCodec.Fix(latE7 = 1, lonE7 = 1, fixUnixSeconds = 3_000_000_000L)
        val back = PositionCodec.decode(PositionCodec.encode(fix))!!
        assertEquals(3_000_000_000L, back.fixUnixSeconds)
    }

    @Test
    fun `the payload is a fraction of a CoT message`() {
        // The whole argument for the format, asserted so it cannot quietly
        // drift: a CoT PLI is roughly 700 bytes of XML.
        val encoded = PositionCodec.encode(PositionCodec.Fix(latE7 = 411234567, lonE7 = 291234567))
        assertTrue("compact form should be under a twentieth of CoT XML", encoded.size * 20 < 700)
    }
}
