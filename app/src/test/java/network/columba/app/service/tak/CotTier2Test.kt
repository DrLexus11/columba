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
}
