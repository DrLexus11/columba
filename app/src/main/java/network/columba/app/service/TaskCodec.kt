package network.columba.app.service

import network.reticulum.identity.Identity
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Canonical signed envelope shared with firmware tools/task_codec.py. */
object TaskCodec {
    const val GOTO = 1
    const val STATUS = 2
    const val RECEIVED = 1
    const val ACCEPTED = 2
    const val DECLINED = 3
    const val MAX_PACKET = 195
    const val APP = "rnstransport"
    val ASPECTS = listOf("tak", "task")
    val DOMAIN = "urtn-tak-task-v1\u0000".toByteArray(Charsets.US_ASCII)
    private const val HEADER = 58

    data class Message(
        val kind: Int,
        val issuer: String,
        val recipient: String,
        val taskId: String,
        val issued: Long,
        val expires: Long,
        val latE7: Int = 0,
        val lonE7: Int = 0,
        val instruction: String = "",
        val status: Int = 0,
    )

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    fun unhex(value: String): ByteArray {
        require(value.length % 2 == 0 && value.all { it in "0123456789abcdefABCDEF" })
        return value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun body(message: Message): ByteArray {
        val m = message
        val hashes = listOf(m.issuer, m.recipient, m.taskId).map(::unhex)
        require(hashes.all { it.size == 16 })
        require(m.issued > 0 && m.expires > m.issued && m.expires <= 0xffffffffL && m.expires - m.issued <= 3600)
        val data =
            when (m.kind) {
                GOTO -> {
                    val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                    val encoded = encoder.encode(java.nio.CharBuffer.wrap(m.instruction))
                    val text = ByteArray(encoded.remaining()).also(encoded::get)
                    require(text.size in 1..64 && m.instruction.none { it.code < 32 || it.code == 127 })
                    require(m.latE7 in -900000000..900000000 && m.lonE7 in -1800000000..1800000000)
                    ByteBuffer
                        .allocate(9 + text.size)
                        .putInt(m.latE7)
                        .putInt(m.lonE7)
                        .put(text.size.toByte())
                        .put(text)
                        .array()
                }
                STATUS -> {
                    require(m.status in RECEIVED..DECLINED)
                    byteArrayOf(m.status.toByte())
                }
                else -> error("Unknown task kind")
            }
        return ByteBuffer
            .allocate(HEADER + data.size)
            .apply {
                put(1.toByte()).put(m.kind.toByte())
                hashes.forEach { put(it) }
                putInt(m.issued.toInt()).putInt(m.expires.toInt()).put(data)
            }.array()
    }

    fun verify(
        wire: ByteArray,
        trustedPublicKey: ByteArray,
        recipient: String,
        now: Long,
    ): Message {
        require(wire.size in (HEADER + 65)..MAX_PACKET && trustedPublicKey.size == 64)
        val payload = wire.copyOfRange(0, wire.size - 64)
        val reader = ByteBuffer.wrap(payload)
        require(reader.get().toInt() == 1)
        val kind = reader.get().toInt()

        fun hash(): String = hex(ByteArray(16).also { reader.get(it) })
        val issuer = hash()
        val target = hash()
        val id = hash()
        val issued = reader.int.toLong() and 0xffffffffL
        val expires = reader.int.toLong() and 0xffffffffL
        val identity = Identity.fromPublicKey(trustedPublicKey)
        require(issuer == hex(identity.hash) && target == recipient)
        require(identity.validate(wire.copyOfRange(wire.size - 64, wire.size), DOMAIN + payload))
        require(issued <= now + 30 && expires > now)
        val message =
            when (kind) {
                GOTO -> {
                    require(reader.remaining() >= 9)
                    val lat = reader.int
                    val lon = reader.int
                    val length = reader.get().toInt() and 255
                    require(reader.remaining() == length)
                    val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    val text = decoder.decode(reader.slice()).toString()
                    Message(kind, issuer, target, id, issued, expires, lat, lon, text)
                }
                STATUS -> {
                    require(reader.remaining() == 1)
                    Message(kind, issuer, target, id, issued, expires, status = reader.get().toInt())
                }
                else -> error("Unknown task kind")
            }
        require(body(message).contentEquals(payload))
        return message
    }
}
