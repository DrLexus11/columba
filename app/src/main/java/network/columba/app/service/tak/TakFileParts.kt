package network.columba.app.service.tak

import java.nio.ByteBuffer

/**
 * A file fetched a part at a time, each part timed.
 *
 * The Kotlin half of the parts in `tools/tak_files.py`, byte-identical and
 * asserted against `tak_native_v1.json`. Round-trip time says whether LoRa is
 * in the path; it does not say whether a file will arrive in reasonable time.
 * BLE is short and slow -- a fast round trip and tens of kbit/s -- so the
 * receiver times each part and goes on only while the rest would arrive within
 * [FETCH_BUDGET_MS]. A paused or broken transfer resumes from what it has.
 *
 *     FILE_PART_REQUEST_V1   kind(1) sha256(32) offset(4) length(4)
 *     FILE_PART_V1           kind(1) sha256(32) offset(4) total(4) data
 */
object TakFileParts {
    const val FETCH_BUDGET_MS = 120_000L
    const val FIRST_PART_BYTES = 64 * 1024
    const val PART_BYTES = 512 * 1024
    private const val HEAD_BYTES = 1 + TakFiles.HASH_BYTES + 4 + 4

    data class Request(val hash: String, val offset: Long, val length: Int)

    class Part(val hash: String, val offset: Long, val total: Long, val data: ByteArray)

    fun encodeRequest(hash: String, offset: Long, length: Int): ByteArray =
        ByteBuffer.allocate(HEAD_BYTES)
            .put(TakPayload.FILE_PART_REQUEST_V1.toByte()).put(hex(hash)).putInt(offset.toInt()).putInt(length)
            .array()

    fun decodeRequest(frame: ByteArray?): Request? {
        if (frame == null || frame.size != HEAD_BYTES || frame[0].toInt() != TakPayload.FILE_PART_REQUEST_V1) return null
        val buffer = ByteBuffer.wrap(frame, 1 + TakFiles.HASH_BYTES, 8)
        return Request(hashOf(frame), buffer.int.toLong() and 0xFFFFFFFFL, buffer.int)
    }

    fun encodePart(hash: String, offset: Long, total: Long, data: ByteArray): ByteArray =
        ByteBuffer.allocate(HEAD_BYTES + data.size)
            .put(TakPayload.FILE_PART_V1.toByte()).put(hex(hash)).putInt(offset.toInt()).putInt(total.toInt()).put(data)
            .array()

    fun decodePart(frame: ByteArray?): Part? {
        if (frame == null || frame.size < HEAD_BYTES || frame[0].toInt() != TakPayload.FILE_PART_V1) return null
        val buffer = ByteBuffer.wrap(frame, 1 + TakFiles.HASH_BYTES, 8)
        val offset = buffer.int.toLong() and 0xFFFFFFFFL
        val total = buffer.int.toLong() and 0xFFFFFFFFL
        val data = frame.copyOfRange(HEAD_BYTES, frame.size)
        return Part(hashOf(frame), offset, total, data).takeIf { offset + data.size <= total }
    }

    /** The first part is small, a sample; the rest are larger. */
    fun nextPartLength(offset: Long, total: Long): Int =
        minOf(if (offset == 0L) FIRST_PART_BYTES.toLong() else PART_BYTES.toLong(), total - offset).toInt()

    /** How long the rest would take, in ms, at the rate the last part arrived. */
    fun msLeft(remaining: Long, partBytes: Int, partMs: Long): Long =
        if (partBytes <= 0) Long.MAX_VALUE else remaining * maxOf(partMs, 1L) / partBytes

    private fun hashOf(frame: ByteArray) =
        frame.copyOfRange(1, 1 + TakFiles.HASH_BYTES).joinToString("") { "%02x".format(it) }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
