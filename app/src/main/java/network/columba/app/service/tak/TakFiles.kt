package network.columba.app.service.tak

import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Files over the mesh: ATAK's data packages and QuickPics.
 *
 * The Kotlin half of `tools/tak_files.py`. ATAK sends a file by uploading it to
 * its streaming host's port 8443 and then sending each recipient a `b-f-t-r`
 * notice naming the file, its size, its SHA-256 and a URL. The notice crosses
 * the mesh as ATAK wrote it; the file crosses only when the receiver asks, and
 * only over a path measured to be fast. See *PR D2* in the firmware repo's
 * `docs/TAKDeliveryPlan.md`.
 *
 *     FILE_REQUEST_V1   kind(1) sha256(32)
 *     FILE_V1           kind(1) sha256(32) name_len(1) name(utf-8) data
 */
object TakFiles {
    const val HASH_BYTES = 32

    /**
     * The largest file stored or sent. A QuickPic measured 3,008,206 bytes on
     * the bench; the LXMF router accepts this much in one transfer.
     */
    const val MAX_FILE_BYTES = 16_000_000
    private const val MAX_NAME_BYTES = 200
    const val FILESHARE_TYPE = "b-f-t-r"

    /**
     * How long a rewritten notice stays current in ATAK. ATAK stamps its own at
     * ten seconds, which is gone before a mesh fetch has finished.
     */
    const val NOTICE_STALE_MS = 10L * 60 * 1000

    private val HEX64 = Regex("^[0-9a-f]{64}$")

    fun sha256Hex(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()

    fun isHash(text: String?): Boolean = text != null && HEX64.matches(text)

    /** Ask a notice's sender for its file. */
    fun encodeRequest(fileHash: String): ByteArray {
        require(isHash(fileHash)) { "a file hash is 64 hex characters" }
        return byteArrayOf(TakPayload.FILE_REQUEST_V1.toByte()) + fileHash.hexToBytes()
    }

    /** The hex hash asked for, or null. */
    fun decodeRequest(frame: ByteArray?): String? =
        frame?.takeIf { it.size == 1 + HASH_BYTES && it[0].toInt() == TakPayload.FILE_REQUEST_V1 }
            ?.copyOfRange(1, 1 + HASH_BYTES)?.toHex()

    /** One whole file, named, under the hash its notice gave. */
    fun encodeFile(fileHash: String, name: String, data: ByteArray): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8).let { it.copyOf(minOf(it.size, MAX_NAME_BYTES)) }
        return byteArrayOf(TakPayload.FILE_V1.toByte()) + fileHash.hexToBytes() +
            byteArrayOf(nameBytes.size.toByte()) + nameBytes + data
    }

    /** One file as it arrived. */
    class File(val hash: String, val name: String, val data: ByteArray)

    /**
     * The file in a frame, or null -- including when the data is not what its
     * hash names, which is a file that is not the one asked for.
     */
    fun decodeFile(frame: ByteArray?): File? {
        if (frame == null || frame.size < 2 + HASH_BYTES || frame[0].toInt() != TakPayload.FILE_V1) return null
        val hash = frame.copyOfRange(1, 1 + HASH_BYTES).toHex()
        val nameLength = frame[1 + HASH_BYTES].toInt() and 0xFF
        val start = 2 + HASH_BYTES + nameLength
        return frame.takeIf { start <= it.size }
            ?.copyOfRange(start, frame.size)
            ?.takeIf { sha256Hex(it) == hash }
            ?.let { File(hash, String(frame, 2 + HASH_BYTES, nameLength, Charsets.UTF_8), it) }
    }

    /** What a `b-f-t-r` offers. */
    data class Notice(val hash: String, val filename: String, val size: Long)

    /** The file a notice offers, or null if this is not one. */
    fun parseNotice(cotXml: String): Notice? {
        val event =
            try {
                CotEvent.parse(cotXml)
            } catch (_: IllegalArgumentException) {
                null
            }
        val share =
            event?.takeIf { it.getAttribute("type") == FILESHARE_TYPE }
                ?.getElementsByTagName("fileshare")?.item(0) as? org.w3c.dom.Element
        val hash = share?.getAttribute("sha256")?.lowercase()
        return if (share != null && isHash(hash)) {
            Notice(hash!!, share.getAttribute("filename"), share.getAttribute("sizeInBytes").toLongOrNull() ?: 0L)
        } else {
            null
        }
    }

    /** Where a receiving ATAK fetches the file from this handset. */
    fun contentUrl(base: String, fileHash: String): String = "${base.trimEnd('/')}/Marti/sync/content?hash=$fileHash"

    /**
     * The notice with its URL pointing here and a stale that outlives a fetch.
     *
     * Substitution on the attributes rather than a re-serialisation, so ATAK's
     * own event passes through otherwise unchanged.
     */
    fun rewriteNotice(cotXml: String, url: String, nowMs: Long = System.currentTimeMillis()): String {
        val stamp = { ms: Long ->
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(ms))
        }
        return cotXml
            .replaceFirst(Regex("senderUrl=\"[^\"]*\""), "senderUrl=\"$url\"")
            .replaceFirst(Regex("\\btime=\"[^\"]*\""), "time=\"${stamp(nowMs)}\"")
            .replaceFirst(Regex("\\bstart=\"[^\"]*\""), "start=\"${stamp(nowMs)}\"")
            .replaceFirst(Regex("\\bstale=\"[^\"]*\""), "stale=\"${stamp(nowMs + NOTICE_STALE_MS)}\"")
    }

    /** Who a status line comes from in ATAK's chat: this handset, not a teammate. */
    const val STATUS_UID = "COLUMBA-FILES"
    const val STATUS_CALLSIGN = "Columba files"
    private const val STATUS_STALE_MS = 24L * 60 * 60 * 1000

    /**
     * A chat line to this handset's own ATAK about a file, from Columba.
     *
     * Local only -- nothing goes on the air. It exists because ATAK cannot show
     * "queued, waiting for a fast path": a receiver sees nothing, and a sender
     * sees its send time out as a failure, while the file is simply waiting.
     * From its own contact rather than the teammate's, so nothing is put in a
     * teammate's mouth.
     */
    fun statusLine(ourUid: String, text: String, nowMs: Long): ByteArray {
        val stamp = { ms: Long ->
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(ms))
        }
        val message =
            CotChat.Message(
                kind = CotChat.KIND_MESSAGE,
                senderId = 0,
                messageId = java.util.UUID.randomUUID().toString(),
                room = STATUS_CALLSIGN,
                text = text,
                recipient = ourUid,
                sentUnix = nowMs / 1000,
            )
        return CotChat.buildChatCot(message, STATUS_UID, STATUS_CALLSIGN, stamp(nowMs), stamp(nowMs + STATUS_STALE_MS))
            .toByteArray(Charsets.UTF_8)
    }

    /** A size an operator reads at a glance. */
    fun sizeText(bytes: Long): String =
        when {
            bytes >= 1_000_000 -> "%.1f MB".format(Locale.US, bytes / 1_000_000.0)
            bytes >= 1_000 -> "${bytes / 1_000} KB"
            else -> "$bytes B"
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray = ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
