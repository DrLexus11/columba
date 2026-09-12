package network.columba.app.service.tak

import org.w3c.dom.Element
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * GeoChat and its receipts, as tens of bytes rather than hundreds.
 *
 * The Kotlin half of `tools/cot_chat.py`. The last of the 853 observed events
 * without a typed codec, and the measurement behind it is stronger than
 * expected: a real GeoChat line from this lab compresses to 402 bytes against a
 * 383-byte MDU, so on tier 2 chat was not merely expensive -- it was
 * **undeliverable**.
 *
 *     message   1095 B raw   tier 2 refused   chat  95 B, 107 ms
 *     receipt    875 B raw   tier 2 343 B     chat  85 B, 100 ms
 *
 * Most of each frame is now the recipient: the captured message addresses a
 * WinTAK user whose uid is a 44-character Windows SID. Carrying it is what
 * stops a private line reaching the whole team, and it was measured against
 * compacting a urtn- recipient to sixteen raw bytes -- twenty-one bytes on an
 * event an operator types by hand did not justify a second encoding and a
 * second way to get it wrong.
 *
 * Almost none of a GeoChat event needs to travel. The uid is three identifiers
 * concatenated, every one of which appears again inside `detail`; the sender
 * appears four times; the point duplicates a position report that already has
 * its own cadence; `__serverdestination` is an artefact of having had a server.
 * Of roughly a thousand bytes, the message is ten of them.
 */
object CotChat {
    const val VERSION = TakPayload.CHAT_V1

    const val KIND_MESSAGE = 0
    const val KIND_DELIVERED = 1
    const val KIND_READ = 2

    /** The CoT type each kind rebuilds as. */
    val COT_TYPES: Map<Int, String> =
        mapOf(KIND_MESSAGE to "b-t-f", KIND_DELIVERED to "b-t-f-d", KIND_READ to "b-t-f-r")

    /**
     * ATAK's message ids are UUIDs, so sixteen bytes carries one exactly rather
     * than the thirty-six the text form costs. A receipt is mostly this field.
     */
    const val MESSAGE_ID_BYTES = 16
    const val MAX_ROOM = 64
    const val MAX_RECIPIENT = 64
    const val MAX_TEXT = 900
    // version, kind, sender, sent_unix, message id, room len, recipient len, text len
    const val HEADER_BYTES = 1 + 1 + 4 + 4 + MESSAGE_ID_BYTES + 1 + 1 + 2

    /**
     * What ATAK calls the everyone-room. A line addressed to one of these is a
     * broadcast and has no single recipient.
     */
    private val BROADCAST_IDS = setOf("All Chat Rooms", "All Streaming", "RootContactGroup", "")

    /** A decoded chat frame. Claims about who sent it are the registry's job. */
    data class Message(
        val kind: Int,
        val senderId: Int,
        val messageId: String,
        val room: String,
        val text: String,
        /**
         * The uid this line is for, empty for a room.
         *
         * What stops a private message reaching the whole team: ATAK puts the
         * recipient's *callsign* in the room field for a direct message, so
         * the room alone cannot tell "everyone" from "one person".
         */
        val recipient: String = "",
        /**
         * When the author sent it, not when it was relayed; zero if the event
         * did not say. A backlog replayed to somebody who was away is worth
         * nothing if every line is stamped with the moment it was replayed.
         */
        val sentUnix: Long = 0,
    )

    /** Pack a chat message or a receipt. */
    fun encode(
        kind: Int,
        senderId: Int,
        messageId: String,
        room: String,
        text: String = "",
        recipient: String = "",
        sentUnix: Long = 0,
    ): ByteArray {
        require(kind in COT_TYPES) { "unknown chat kind $kind" }
        require(kind == KIND_MESSAGE || text.isEmpty()) { "a receipt carries no text" }
        val roomBytes = room.toByteArray(Charsets.UTF_8)
        val recipientBytes = recipient.toByteArray(Charsets.UTF_8)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        require(roomBytes.isNotEmpty() && roomBytes.size <= MAX_ROOM) {
            "chatroom must be 1..$MAX_ROOM UTF-8 bytes"
        }
        require(recipientBytes.size <= MAX_RECIPIENT) {
            "recipient is longer than $MAX_RECIPIENT bytes"
        }
        // Longer than this is not a chat line, and tier 2 already carries
        // anything this codec will not.
        require(textBytes.size <= MAX_TEXT) { "chat text is longer than $MAX_TEXT bytes" }
        require(kind != KIND_MESSAGE || textBytes.isNotEmpty()) {
            "a chat message with no text is not a message"
        }
        return ByteBuffer.allocate(
            HEADER_BYTES + roomBytes.size + recipientBytes.size + textBytes.size,
        )
            .order(ByteOrder.BIG_ENDIAN)
            .put(VERSION.toByte())
            .put(kind.toByte())
            .putInt(senderId)
            .putInt((sentUnix and 0xFFFFFFFFL).toInt())
            .put(uuidBytes(messageId))
            .put(roomBytes.size.toByte())
            .put(recipientBytes.size.toByte())
            .putShort(textBytes.size.toShort())
            .put(roomBytes)
            .put(recipientBytes)
            .put(textBytes)
            .array()
    }

    /**
     * Unpack a chat frame, or null for anything that is not one.
     *
     * Null rather than an exception: this parses bytes off a radio, where a
     * frame that is not ours is an ordinary event and not a fault.
     */
    fun decode(frame: ByteArray?): Message? {
        if (frame == null || frame.size < HEADER_BYTES) return null
        val buffer = ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN)
        if ((buffer.get().toInt() and 0xFF) != VERSION) return null
        val kind = buffer.get().toInt() and 0xFF
        if (kind !in COT_TYPES) return null
        val senderId = buffer.int
        val sentUnix = buffer.int.toLong() and 0xFFFFFFFFL
        val rawId = ByteArray(MESSAGE_ID_BYTES).also { buffer.get(it) }
        val roomLength = buffer.get().toInt() and 0xFF
        val recipientLength = buffer.get().toInt() and 0xFF
        val textLength = buffer.short.toInt() and 0xFFFF
        if (roomLength < 1 || roomLength > MAX_ROOM) return null
        if (recipientLength > MAX_RECIPIENT || textLength > MAX_TEXT) return null
        // The lengths describe the whole body. Trailing bytes mean this is not
        // the frame it claims to be.
        if (buffer.remaining() != roomLength + recipientLength + textLength) return null
        // A message with no words, or a receipt carrying some. Either way it is
        // not what its own kind says it is, and a caller trusting `kind` would
        // act on the wrong thing.
        if ((kind == KIND_MESSAGE) != (textLength > 0)) return null
        val body = ByteArray(buffer.remaining()).also { buffer.get(it) }
        return try {
            Message(
                kind = kind,
                senderId = senderId,
                messageId = uuidString(rawId),
                room = decodeStrict(body, 0, roomLength),
                text = decodeStrict(body, roomLength + recipientLength, textLength),
                recipient = decodeStrict(body, roomLength, recipientLength),
                sentUnix = sentUnix,
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * A chat frame from a CoT event, or null if it is not chat.
     *
     * Everything discarded is either a copy of something else in the same event
     * or a copy of something we already send.
     */
    fun chatFromCot(cotXml: String, senderId: Int): ByteArray? {
        val event = try {
            CotEvent.parse(cotXml)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val kind = COT_TYPES.entries.firstOrNull { it.value == event.getAttribute("type") }?.key
            ?: return null
        val detail = childElement(event, "detail") ?: return null
        val chat = childElement(detail, "__chat") ?: childElement(detail, "__chatreceipt")
            ?: return null
        val messageId = chat.getAttribute("messageId").takeIf { it.isNotEmpty() } ?: return null
        val room = chat.getAttribute("chatroom").takeIf { it.isNotEmpty() } ?: return null
        // Who this is actually for. ATAK puts the recipient's callsign in the
        // chatroom field for a direct message, so the room alone cannot tell
        // "everyone" from "one person" -- and treating every line as a
        // broadcast delivers private messages to the whole team.
        var recipient = chat.getAttribute("id")
        val group = childElement(chat, "chatgrp")
        if (group != null) {
            // chatgrp enumerates participants, so a uid2 means three or more
            // of them: a room, and no one person this line is for. Reading
            // uid1 as a recipient there would narrow a room conversation to
            // whoever happened to be listed second.
            if (group.getAttribute("uid2").isNotEmpty()) {
                recipient = ""
            } else if (recipient.isEmpty()) {
                recipient = group.getAttribute("uid1")
            }
        }
        if (recipient in BROADCAST_IDS || recipient == room) recipient = ""
        val remarks = childElement(detail, "remarks")
        var text = ""
        if (kind == KIND_MESSAGE) {
            text = remarks?.textContent.orEmpty()
            if (text.isEmpty()) return null
        }
        val sentUnix = sentUnix(remarks?.getAttribute("time"), event.getAttribute("time"))
        return try {
            encode(kind, senderId, messageId, room, text, recipient, sentUnix)
        } catch (_: IllegalArgumentException) {
            // A room or a line longer than this codec carries. Tier 2 takes it
            // instead rather than this silently truncating somebody's words.
            null
        }
    }

    /**
     * Rebuild an event ATAK accepts from a decoded chat frame.
     *
     * `when` is an ISO-8601 CoT timestamp, produced by the caller so this stays
     * a pure function of its inputs and the tests need not freeze a clock.
     */
    fun buildChatCot(message: Message, senderUid: String, callsign: String, whenIso: String): String {
        val room = escape(message.room)
        val sender = escape(senderUid)
        // A message's uid is three identifiers concatenated, which is how ATAK
        // threads a conversation; a receipt's uid is the id of the message it
        // is about, which is how ATAK matches it to the line on screen.
        // The recipient as ATAK will see it: the peer's own uid for a direct
        // message, the room for a broadcast. Threading depends on this being a
        // uid and not a callsign, which is what it used to be.
        val target = escape(message.recipient).ifEmpty { room }
        val uid =
            if (message.kind == KIND_MESSAGE) {
                "GeoChat.$sender.$target.${message.messageId}"
            } else {
                message.messageId
            }
        val element = if (message.kind == KIND_MESSAGE) "__chat" else "__chatreceipt"
        val detail = StringBuilder()
        detail.append(
            "<$element chatroom=\"$room\" groupOwner=\"false\" id=\"$target\" " +
                "messageId=\"${message.messageId}\" parent=\"RootContactGroup\" " +
                "senderCallsign=\"${escape(callsign)}\">" +
                "<chatgrp id=\"$target\" uid0=\"$sender\" uid1=\"$target\"/></$element>",
        )
        detail.append("<link relation=\"p-p\" type=\"a-f-G-U-C\" uid=\"$sender\"/>")
        if (message.kind == KIND_MESSAGE) {
            // The author's time, not the relay's. This is what makes a
            // replayed conversation read in the order it happened.
            val stamp = if (message.sentUnix > 0) iso(message.sentUnix) else whenIso
            detail.append(
                "<remarks source=\"BAO.F.ATAK.$sender\" time=\"$stamp\" to=\"$target\">" +
                    "${escape(message.text)}</remarks>",
            )
        }
        detail.append("<marti><dest callsign=\"$room\"/></marti>")
        return "<event version=\"2.0\" uid=\"${escape(uid)}\" type=\"${COT_TYPES[message.kind]}\"" +
            " how=\"h-g-i-g-o\" time=\"$whenIso\" start=\"$whenIso\" stale=\"$whenIso\">" +
            "<point lat=\"0.0\" lon=\"0.0\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>" +
            "<detail>$detail</detail></event>"
    }

    /**
     * When the author sent this, or zero if the event does not say.
     *
     * Zero rather than now: a receiver can tell "not stated" from a time, and
     * stamping the relay moment is what would make a replayed backlog look as
     * though it all happened at once.
     */
    private fun sentUnix(remarksTime: String?, eventTime: String): Long {
        val stamp = remarksTime?.takeIf { it.isNotEmpty() } ?: eventTime
        if (stamp.isEmpty()) return 0
        return runCatching { java.time.Instant.parse(stamp).epochSecond }.getOrDefault(0L)
    }

    /** A CoT timestamp from unix seconds, in the form ATAK writes. */
    private fun iso(unixSeconds: Long): String =
        java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(unixSeconds * 1000))

    private fun uuidBytes(messageId: String): ByteArray {
        val uuid = try {
            UUID.fromString(messageId)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("message id must be a UUID: $messageId", error)
        }
        return ByteBuffer.allocate(MESSAGE_ID_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
    }

    private fun uuidString(raw: ByteArray): String {
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
        return UUID(buffer.long, buffer.long).toString()
    }

    private fun decodeStrict(source: ByteArray, offset: Int, length: Int): String {
        val slice = source.copyOfRange(offset, offset + length)
        val text = slice.toString(Charsets.UTF_8)
        // Kotlin substitutes U+FFFD for invalid UTF-8 rather than throwing, so
        // a malformed line would otherwise reach an operator looking like words
        // somebody meant to send.
        require(text.toByteArray(Charsets.UTF_8).contentEquals(slice)) { "not valid UTF-8" }
        return text
    }

    private fun childElement(parent: Element, name: String): Element? {
        val children = parent.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child is Element && child.tagName == name) return child
        }
        return null
    }

    private fun escape(value: String) =
        value.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;")
}
