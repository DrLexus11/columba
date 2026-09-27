package network.columba.app.service.tak

import java.nio.ByteBuffer

/**
 * A file fetched a part at a time, each part timed.
 *
 * The Kotlin half of the parts in `tools/tak_files.py`, byte-identical and
 * asserted against `tak_native_v1.json`. The receiver times each part and goes
 * on only while the rest would arrive within [FETCH_BUDGET_MS]. A paused or
 * broken transfer resumes from what it has.
 *
 * Nothing but a timed part says how fast a path is. Round-trip time was tried
 * first as a cheap filter for LoRa, and does not separate the carriers: one
 * LoRa hop at SF7/250 kHz is ~0.19 s on air for a link round trip, while a
 * Nexus 6P one BLE hop away measured 0.67 s (bench, 2026-09-26). Declared
 * bitrates are guesses. So every attempt starts with two small parts: the
 * first carries the link and transfer setup and is not judged; the second is.
 * A route measured slow is not sampled again until it changes, or
 * [RESAMPLE_MS] on -- see [PathRates] -- so a file waiting behind LoRa costs
 * 16 KB on air per route, not per retry.
 *
 *     FILE_PART_REQUEST_V1   kind(1) sha256(32) offset(4) length(4)
 *     FILE_PART_V1           kind(1) sha256(32) offset(4) total(4) data
 */
object TakFileParts {
    const val FETCH_BUDGET_MS = 120_000L
    const val SETUP_PART_BYTES = 4 * 1024
    const val SAMPLE_PART_BYTES = 12 * 1024
    const val PART_BYTES = 512 * 1024
    const val RESAMPLE_MS = 60L * 60 * 1000
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
        val offset = buffer.int.toLong() and 0xFFFFFFFFL
        // Signed on the wire, so a negative length decodes as one -- and the
        // sender then computes an end before the offset and copyOfRange throws
        // inside the LXMF collector. No request asks for nothing or for more
        // than a part.
        val length = buffer.int
        return Request(hashOf(frame), offset, length).takeIf { length in 1..PART_BYTES }
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
        // Bounded before the data is copied: a total up to 4 GiB, or a part
        // larger than any request asks for, describes nothing this store would
        // keep.
        if (total !in 1..TakFiles.MAX_FILE_BYTES.toLong() || frame.size - HEAD_BYTES > PART_BYTES) return null
        val data = frame.copyOfRange(HEAD_BYTES, frame.size)
        return Part(hashOf(frame), offset, total, data).takeIf { offset + data.size <= total }
    }

    /** The setup part, then the sample, then full parts once a part is judged. */
    fun partLength(offset: Long, total: Long, partsThisAttempt: Int, judged: Boolean): Int {
        val size =
            when {
                judged -> PART_BYTES
                partsThisAttempt == 0 -> SETUP_PART_BYTES
                else -> SAMPLE_PART_BYTES
            }
        return minOf(size.toLong(), total - offset).toInt()
    }

    /** How long the rest would take, in ms, at the rate the last part arrived. */
    fun msLeft(remaining: Long, partBytes: Int, partMs: Long): Long =
        if (partBytes <= 0) Long.MAX_VALUE else remaining * maxOf(partMs, 1L) / partBytes

    /** Where the path table says a sender is now: hops, and the next-hop interface. */
    data class Route(val hops: Int, val via: String)

    /**
     * The rate last measured to each sender, and over which route. Trusted for
     * the route it was measured on, for [RESAMPLE_MS].
     */
    class PathRates {
        private class Measured(val route: Route?, val bytesPerSecond: Double, val atMs: Long)

        private val rates = java.util.concurrent.ConcurrentHashMap<String, Measured>()

        fun record(sender: String, route: Route?, bytesPerSecond: Double, nowMs: Long) {
            rates[sender] = Measured(route, bytesPerSecond, nowMs)
        }

        /** Bytes per second measured on this route lately, or null. */
        fun known(sender: String, route: Route, nowMs: Long): Double? =
            rates[sender]?.takeIf { it.route == route && nowMs - it.atMs <= RESAMPLE_MS }?.bytesPerSecond
    }

    private fun hashOf(frame: ByteArray) =
        frame.copyOfRange(1, 1 + TakFiles.HASH_BYTES).joinToString("") { "%02x".format(it) }

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
