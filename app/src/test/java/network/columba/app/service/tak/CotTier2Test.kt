package network.columba.app.service.tak

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/** Interoperability with tools/cot_tier2.py, which is the point of the codec. */
class CotTier2Test {
    private val vectors: JSONObject =
        JSONObject(
            checkNotNull(javaClass.classLoader.getResourceAsStream("tak_native_v1.json"))
                .bufferedReader().use { it.readText() },
        )
    private val tier2 = vectors.getJSONObject("tier2")

    private fun unhex(value: String) =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `the dictionary is byte identical to the python side`() {
        // If these diverge, nothing either side sends decompresses on the
        // other, so this is the assertion that matters most in the file.
        val digest = MessageDigest.getInstance("SHA-256").digest(CotTier2.DICTIONARY)
        assertEquals(tier2.getInt("dictionary_length"), CotTier2.DICTIONARY.size)
        assertEquals(
            tier2.getString("dictionary_sha256"),
            digest.joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `a frame from python decodes here`() {
        assertEquals(tier2.getString("cot"), CotTier2.decode(unhex(tier2.getString("frame"))))
    }

    @Test
    fun `our own frames round trip`() {
        val cot = tier2.getString("cot")
        assertEquals(cot, CotTier2.decode(CotTier2.encode(cot)))
    }

    @Test
    fun `the dictionary buys real compression, and the result fits one packet`() {
        // 383 bytes is the encrypted MDU, and the measured mean CoT is 582, so
        // the dictionary is what keeps most events to a single packet. This
        // particular sample is 340 raw, which already fits -- so the threshold
        // is asserted, and the compression is asserted as a ratio rather than
        // by pretending this event needed rescuing.
        val cot = tier2.getString("cot")
        val raw = cot.toByteArray().size
        val frame = CotTier2.encode(cot).size
        assertTrue("raw $raw -> frame $frame", frame <= 383)
        assertTrue("raw $raw -> frame $frame is not worth a dictionary", frame * 2 < raw)
    }

    @Test
    fun `a frame never costs more air than the event itself`() {
        val incompressible =
            "<event uid=\"" + (1..400).joinToString("") { "%02x".format(it % 251) } + "\"/>"
        for (cot in listOf(tier2.getString("cot"), "<event type=\"a\" uid=\"b\"/>", incompressible)) {
            val frame = CotTier2.encode(cot)
            assertTrue(
                "frame ${frame.size} vs raw ${cot.toByteArray().size}",
                frame.size <= cot.toByteArray().size + 2,
            )
            assertEquals(cot, CotTier2.decode(frame))
        }
    }

    @Test
    fun `the frame bound matches the python side`() {
        assertEquals(tier2.getInt("max_frame_bytes"), CotTier2.MAX_FRAME_BYTES)
    }

    @Test
    fun `an event that cannot fit one packet is refused`() {
        // A 5 KB ATAK drawing compresses to about 700 bytes -- inside
        // MAX_DECOMPRESSED and nearly twice the MDU. Nothing checked it, so
        // the frame reached Reticulum, which refuses it at the packet layer.
        val drawing = buildString {
            append("<event uid=\"drawing-1\" type=\"u-d-f\" how=\"h-e\" version=\"2.0\">")
            append("<point lat=\"40.95\" lon=\"29.09\" hae=\"0\" ce=\"9\" le=\"9\"/>")
            append("<detail><shape><polyline closed=\"true\">")
            for (i in 0 until 120) {
                append("<vertex lat=\"40.95%04d\" lon=\"29.09%04d\"/>".format(i, i * 7 % 9999))
            }
            append("</polyline></shape></detail></event>")
        }
        assertTrue(drawing.length > 4000)
        val failure = runCatching { CotTier2.encode(drawing) }.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("tier 3"))
    }

    @Test
    fun `everything encode returns fits one packet`() {
        for (cot in listOf(tier2.getString("cot"), "<event type=\"a\" uid=\"b\"/>")) {
            assertTrue(CotTier2.encode(cot).size <= CotTier2.MAX_FRAME_BYTES)
        }
    }

    @Test
    fun `trailing data after the stream is refused`() {
        val frame = CotTier2.encode(tier2.getString("cot")) + "leftover".toByteArray()
        assertTrue(runCatching { CotTier2.decode(frame) }.isFailure)
    }

    @Test
    fun `a payload that is not valid utf8 is refused, not substituted`() {
        // toString(UTF_8) substitutes U+FFFD, so a frame carrying malformed
        // bytes would reach the map as text nobody sent.
        val frame = byteArrayOf(CotTier2.VERSION, CotTier2.ENCODING_RAW) +
            "<event uid=\"a\"/>".toByteArray().let {
                it.copyOfRange(0, 8) + byteArrayOf(-1, -2) + it.copyOfRange(8, it.size)
            }
        assertTrue(runCatching { CotTier2.decode(frame) }.isFailure)
    }

    @Test
    fun `unknown version and short frames are refused`() {
        for (frame in listOf(
            byteArrayOf(99, CotTier2.ENCODING_RAW) + "<event/>".toByteArray(),
            byteArrayOf(CotTier2.VERSION, 77) + "<event/>".toByteArray(),
            byteArrayOf(CotTier2.VERSION),
            ByteArray(0),
        )) {
            assertTrue(
                "accepted a frame it should have refused",
                runCatching { CotTier2.decode(frame) }.isFailure,
            )
        }
    }

    @Test
    fun `a corrupt payload is refused rather than crashing`() {
        val frame = CotTier2.encode(tier2.getString("cot")).copyOf()
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0xFF).toByte()
        frame[5] = (frame[5].toInt() xor 0xFF).toByte()
        assertTrue(runCatching { CotTier2.decode(frame) }.isFailure)
    }

    /**
     * MAX_DECOMPRESSED was enforced inside inflate(), so it only ever guarded
     * the deflate path. A raw frame walked straight past it -- and a raw frame
     * is the easy one to forge, needing no compressor at all. encode() refusing
     * to produce one says nothing about what a peer may send.
     */
    @Test
    fun `an oversized raw frame is refused`() {
        val oversized =
            byteArrayOf(CotTier2.VERSION, CotTier2.ENCODING_RAW) +
                ByteArray(CotTier2.MAX_DECOMPRESSED + 1) { 'x'.code.toByte() }

        val error = runCatching { CotTier2.decode(oversized) }.exceptionOrNull()

        assertTrue("an oversized raw frame must be refused, got $error", error is IllegalArgumentException)
    }

    /** A raw frame exactly at the bound is legitimate. */
    @Test
    fun `a raw frame at the bound is accepted`() {
        val atBound =
            byteArrayOf(CotTier2.VERSION, CotTier2.ENCODING_RAW) +
                ByteArray(CotTier2.MAX_DECOMPRESSED) { 'x'.code.toByte() }

        val decoded = CotTier2.decode(atBound)

        assertEquals(CotTier2.MAX_DECOMPRESSED, decoded.length)
    }
}
