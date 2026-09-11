package network.columba.app.service.tak

import network.columba.app.service.PositionCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** GeoChat and receipts, against events ATAK actually produced in this lab. */
class CotChatTest {
    private val vectors: JSONObject =
        JSONObject(
            checkNotNull(javaClass.classLoader.getResourceAsStream("tak_native_v1.json"))
                .bufferedReader().use { it.readText() },
        )
    private val chat = vectors.getJSONObject("chat")
    private val sender = 0x11223344

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private val messageId = "3e80bd07-fdd8-4c2d-aaf4-8119b1f23a56"

    // ---- agreement with the Python side ----

    @Test
    fun `the frames match the python side`() {
        assertEquals(
            chat.getString("message_frame"),
            CotChat.chatFromCot(chat.getString("message"), sender)!!.hex(),
        )
        assertEquals(
            chat.getString("receipt_frame"),
            CotChat.chatFromCot(chat.getString("receipt_delivered"), sender)!!.hex(),
        )
    }

    @Test
    fun `the version and kinds match the python side`() {
        assertEquals(chat.getInt("version"), CotChat.VERSION)
        assertEquals(chat.getInt("kind_message"), CotChat.KIND_MESSAGE)
        assertEquals(chat.getInt("kind_delivered"), CotChat.KIND_DELIVERED)
        assertEquals(chat.getInt("kind_read"), CotChat.KIND_READ)
    }

    // ---- the reason this codec exists ----

    @Test
    fun `a real message does not fit tier 2 at all`() {
        // The justification in one assertion: a GeoChat line compresses to more
        // than one Reticulum packet, so before this codec chat was not merely
        // expensive, it was undeliverable.
        val failure = runCatching { CotTier2.encode(chat.getString("message")) }.exceptionOrNull()
        assertTrue("expected a refusal, got $failure", failure is IllegalArgumentException)
        assertTrue(failure!!.message!!.contains("tier 3"))
    }

    @Test
    fun `a real message becomes tens of bytes`() {
        val frame = CotChat.chatFromCot(chat.getString("message"), sender)!!
        assertTrue("frame was ${frame.size}", frame.size < 60)
        assertTrue(frame.size < chat.getString("message").length / 20)
    }

    @Test
    fun `a real message keeps what matters`() {
        val decoded = CotChat.decode(CotChat.chatFromCot(chat.getString("message"), sender))!!
        assertEquals(CotChat.KIND_MESSAGE, decoded.kind)
        assertEquals("Inquisitor", decoded.room)
        assertEquals("where u at", decoded.text)
        assertEquals(sender, decoded.senderId)
    }

    @Test
    fun `a real receipt carries no words`() {
        val decoded =
            CotChat.decode(CotChat.chatFromCot(chat.getString("receipt_delivered"), sender))!!
        assertEquals(CotChat.KIND_DELIVERED, decoded.kind)
        assertEquals("", decoded.text)
    }

    @Test
    fun `a rebuilt event survives another round trip`() {
        // What the far end hands ATAK has to be something this codec would
        // recognise again, or a message relayed twice would decay.
        val decoded = CotChat.decode(CotChat.chatFromCot(chat.getString("message"), sender))!!
        val rebuilt = CotChat.buildChatCot(
            decoded, "urtn-" + "ab".repeat(16), "LEXUS", "2026-09-11T20:00:00.000Z")
        assertEquals(decoded, CotChat.decode(CotChat.chatFromCot(rebuilt, sender)))
    }

    @Test
    fun `a position report is not chat`() {
        assertNull(CotChat.chatFromCot(vectors.getJSONObject("tier2").getString("cot"), sender))
    }

    // ---- the codec itself ----

    @Test
    fun `a message id is sixteen bytes, not thirty six`() {
        // A receipt is mostly this field, so the text form would more than
        // double one.
        val frame = CotChat.encode(CotChat.KIND_DELIVERED, sender, messageId, "Cyan")
        assertTrue(!frame.toString(Charsets.ISO_8859_1).contains(messageId))
        assertEquals(messageId, CotChat.decode(frame)!!.messageId)
    }

    @Test
    fun `a non ascii line survives`() {
        val decoded = CotChat.decode(
            CotChat.encode(CotChat.KIND_MESSAGE, sender, messageId, "Cyan", "yoldayız"))!!
        assertEquals("yoldayız", decoded.text)
    }

    @Test
    fun `a receipt with words and a message without are both refused`() {
        assertTrue(
            runCatching {
                CotChat.encode(CotChat.KIND_READ, sender, messageId, "Cyan", "sneaky")
            }.isFailure,
        )
        assertTrue(
            runCatching {
                CotChat.encode(CotChat.KIND_MESSAGE, sender, messageId, "Cyan", "")
            }.isFailure,
        )
    }

    @Test
    fun `a message id that is not a uuid is refused`() {
        assertTrue(
            runCatching {
                CotChat.encode(CotChat.KIND_MESSAGE, sender, "not-a-uuid", "Cyan", "hi")
            }.isFailure,
        )
    }

    @Test
    fun `an over long line is left to tier 2 rather than truncated`() {
        // Silently cutting somebody's words is worse than spending the airtime.
        assertTrue(
            runCatching {
                CotChat.encode(CotChat.KIND_MESSAGE, sender, messageId, "Cyan",
                               "x".repeat(CotChat.MAX_TEXT + 1))
            }.isFailure,
        )
    }

    @Test
    fun `truncated and padded frames are refused`() {
        val frame = CotChat.encode(CotChat.KIND_MESSAGE, sender, messageId, "Cyan", "hi")
        for (cut in 1 until frame.size) {
            assertNull("cut at $cut", CotChat.decode(frame.copyOfRange(0, cut)))
        }
        assertNull(CotChat.decode(frame + "extra".toByteArray()))
    }

    @Test
    fun `a kind that disagrees with its own body is refused`() {
        val frame = CotChat.encode(CotChat.KIND_MESSAGE, sender, messageId, "Cyan", "hi").copyOf()
        frame[1] = CotChat.KIND_READ.toByte()
        assertNull(CotChat.decode(frame))
    }

    @Test
    fun `invalid utf8 is refused, not substituted`() {
        val frame = CotChat.encode(CotChat.KIND_MESSAGE, sender, messageId, "Cyan", "hi").copyOf()
        frame[frame.size - 1] = 0xFF.toByte()
        assertNull(CotChat.decode(frame))
    }

    @Test
    fun `markup in a line cannot escape the rebuilt event`() {
        // A peer chooses its own words and this renders them into XML.
        val nasty = """</remarks><detail evil="1">"""
        val decoded =
            CotChat.decode(CotChat.encode(CotChat.KIND_MESSAGE, sender, messageId, "Cyan", nasty))!!
        val rebuilt =
            CotChat.buildChatCot(decoded, "urtn-x", "PEER", "2026-09-11T20:00:00.000Z")
        assertEquals(nasty, CotChat.decode(CotChat.chatFromCot(rebuilt, sender))!!.text)
    }

    // ---- the shared namespace ----

    @Test
    fun `every codec takes a distinct registered kind`() {
        val kinds = listOf(CotTier2.VERSION.toInt(), PositionCodec.WIRE_VERSION, CotChat.VERSION)
        assertEquals(kinds.size, kinds.toSet().size)
        for (kind in kinds) assertTrue("$kind is not registered", kind in TakPayload.KINDS)
    }

    @Test
    fun `an unregistered kind is not guessed at`() {
        assertNull(TakPayload.kindOf(byteArrayOf(99, 0, 0)))
        assertNull(TakPayload.kindOf(ByteArray(0)))
        assertNull(TakPayload.kindOf(null))
        assertEquals("unknown", TakPayload.nameOf(byteArrayOf(99)))
    }

    @Test
    fun `each codec refuses the others frames`() {
        val chatFrame = CotChat.encode(CotChat.KIND_MESSAGE, sender, messageId, "Cyan", "hello")
        assertNull(PositionCodec.decode(chatFrame))
        assertTrue(runCatching { CotTier2.decode(chatFrame) }.isFailure)
        assertNull(CotChat.decode(CotTier2.encode("<event uid=\"a\"/>")))
        assertNotNull(CotChat.decode(chatFrame))
    }
}
