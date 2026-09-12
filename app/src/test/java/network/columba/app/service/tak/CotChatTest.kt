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
        // This one addresses a WinTAK user, whose uid is a 44-character
        // Windows SID, so most of the frame is the recipient. Carrying it is
        // what stops a private line reaching the whole team, and it was
        // measured against compacting a urtn- recipient to sixteen raw bytes:
        // twenty-one bytes on an event an operator types by hand did not
        // justify a second encoding and a second way to get it wrong.
        val frame = CotChat.chatFromCot(chat.getString("message"), sender)!!
        assertTrue("frame was ${frame.size}", frame.size < 100)
        assertTrue(frame.size < chat.getString("message").length / 10)
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

    // ---- who a line is for ----
    //
    // ATAK puts the recipient's *callsign* in the chatroom field for a direct
    // message, so the room alone cannot tell "everyone" from "one person".
    // Until the recipient was carried separately, every private line was
    // delivered to the whole team -- not a cost problem, a confidentiality one.

    /**
     * The captured message, readdressed.
     *
     * Derived from the fixture rather than from a literal: the identifiers in
     * it have been re-sanitised once already, and a hard-coded copy silently
     * stopped matching.
     */
    private fun addressedTo(target: String) =
        chat.getString("message").replace(chat.getString("message_recipient"), target)

    @Test
    fun `a direct message carries who it is for`() {
        val decoded = CotChat.decode(CotChat.chatFromCot(chat.getString("message"), sender))!!
        assertEquals(chat.getString("message_recipient"), decoded.recipient)
        assertTrue(decoded.recipient != decoded.room)
    }

    @Test
    fun `a broadcast carries no recipient`() {
        // A line to everyone has no single addressee, and inventing one would
        // narrow a broadcast to one person -- the same bug in reverse.
        for (room in listOf("All Chat Rooms", "All Streaming")) {
            val event = addressedTo(room).replace("chatroom=\"Inquisitor\"", "chatroom=\"$room\"")
            assertEquals(room, "", CotChat.decode(CotChat.chatFromCot(event, sender))!!.recipient)
        }
    }

    @Test
    fun `a room with three people is not a direct message`() {
        // chatgrp enumerates participants. Reading uid1 as a recipient in a
        // room would narrow the conversation to whoever is listed second,
        // which is the confidentiality bug pointing at the wrong person
        // instead of at everybody.
        val target = chat.getString("message_recipient")
        val event = chat.getString("message").replace(
            """uid1="$target"""",
            """uid1="$target" uid2="ANDROID-1111111111111111"""",
        )
        assertEquals("", CotChat.decode(CotChat.chatFromCot(event, sender))!!.recipient)
    }

    @Test
    fun `a team room is not a direct message`() {
        // The room's own name in the id field is a room, not a person. ATAK
        // writes it that way for a team chat.
        val target = chat.getString("message_recipient")
        val event = chat.getString("message")
            .replace("""id="$target"""", """id="Inquisitor"""")
            .replace("""uid1="$target"""", """uid1="Inquisitor"""")
        assertEquals("", CotChat.decode(CotChat.chatFromCot(event, sender))!!.recipient)
    }

    @Test
    fun `a uid addressed line resolves to one peer`() {
        // Peers are announced under their Reticulum-rooted UID, so ATAK
        // addresses them by it and destinationFor() reverses it. Pivot 1
        // paying for itself.
        val peer = "urtn-" + "cd".repeat(16)
        val decoded = CotChat.decode(CotChat.chatFromCot(addressedTo(peer), sender))!!
        assertEquals(peer, decoded.recipient)
        assertNotNull(TakIdentity.destinationFor(decoded.recipient))
    }

    @Test
    fun `threading uses a uid not a callsign`() {
        // chatgrp uid1 named the room, which is a callsign for a direct
        // message. ATAK threads on the uid.
        val decoded = CotChat.decode(CotChat.chatFromCot(chat.getString("message"), sender))!!
        val rebuilt =
            CotChat.buildChatCot(decoded, "urtn-x", "PEER", "2026-09-12T09:00:00.000Z")
        assertTrue(rebuilt, rebuilt.contains("uid1=\"${decoded.recipient}\""))
    }

    // ---- when a line was sent ----
    //
    // A backlog replayed to somebody who was away is worth nothing if every
    // line is stamped with the moment it was replayed.

    @Test
    fun `the authors time is carried`() {
        val decoded = CotChat.decode(CotChat.chatFromCot(chat.getString("message"), sender))!!
        assertEquals(chat.getLong("message_sent_unix"), decoded.sentUnix)
    }

    @Test
    fun `a replayed line keeps its own time`() {
        val decoded = CotChat.decode(CotChat.chatFromCot(chat.getString("message"), sender))!!
        val rebuilt =
            CotChat.buildChatCot(decoded, "urtn-x", "PEER", "2026-09-12T09:00:00.000Z")
        assertTrue(rebuilt, rebuilt.contains("time=\"${chat.getString("message_sent_iso")}\""))
        assertEquals(
            decoded.sentUnix,
            CotChat.decode(CotChat.chatFromCot(rebuilt, sender))!!.sentUnix,
        )
    }

    @Test
    fun `an event with no time says so rather than guessing`() {
        // Zero rather than now: a receiver can tell "not stated" from a time,
        // and stamping the relay moment is what makes a backlog look
        // simultaneous.
        val event = chat.getString("message").replace(Regex("""\s*time="[^"]*""""), "")
        assertEquals(0L, CotChat.decode(CotChat.chatFromCot(event, sender))!!.sentUnix)
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
