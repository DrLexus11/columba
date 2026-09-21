package network.columba.app.service.tak

/**
 * One oversized event, cut into packets that each stand on their own.
 *
 * The Kotlin half of `tools/cot_fragment.py`, and the two must stay
 * byte-identical -- `tak_native_v1.json` carries the vectors both sides assert
 * against.
 *
 * Tier 2 refuses above 383 B, which is why a drawing or a nine-line MEDEVAC
 * does not cross at all. **Each fragment is a whole LXMF message**, which is
 * the point: LXMF already proves and retries every message, so cutting a
 * payload up inherits selective retransmission rather than reimplementing it.
 * The far end only reassembles.
 *
 * The alternative was one `Resource` over one `Link`, which LXMF reaches for by
 * itself above roughly six fragments. Below that the handshake costs more than
 * the per-fragment proofs, and everything on the LoRa requirement list is below
 * it: a compressed drawing is two fragments, a MEDEVAC is one. The airtime gap
 * is about four seconds for a team of seven; the failure mode matters more,
 * because a `Resource` is all-or-nothing on a link that may not establish while
 * independent proved fragments retry one at a time.
 *
 * ## The wire
 *
 * ```
 * byte 0      kind (TakPayload.FRAGMENT_V1)
 * bytes 1-4   transfer id, big-endian
 * byte 5      index, zero-based
 * byte 6      count
 * bytes 7+    this slice of the tier-2 frame
 * ```
 */
object CotFragment {
    const val HEADER_BYTES = 7

    /**
     * The envelope LXMF puts around one frame, measured on the bench.
     *
     * A fragment does not travel as a bare packet -- it travels as an LXMF
     * message, because that is the only thing that gives it a proof and a
     * retry. Measured 2026-09-21 against LXMF 1.1.1, the envelope is a
     * constant 107 bytes on the air.
     */
    const val LXMF_ENVELOPE_BYTES = 107

    /** Headroom for fields a message may yet carry: a ticket, a stamp. */
    const val LXMF_HEADROOM_BYTES = 20

    /**
     * What one fragment frame may be, so it fits ONE LXMF message in ONE
     * packet.
     *
     * **Not 383.** That is the bare-packet MDU and it is not this frame's
     * budget. A 383 B fragment packs to 490 B on the air, and at 309 B LXMF
     * stops using a packet at all and builds a Resource over a Link -- one
     * link handshake per fragment, on a path where establishment was measured
     * at 5 of 15 when the channel was busy. Strictly worse than the single
     * Resource this scheme exists to avoid, and it would have looked like tier
     * 3 working.
     */
    const val MAX_FRAGMENT_FRAME_BYTES =
        CotTier2.MAX_FRAME_BYTES - LXMF_ENVELOPE_BYTES - LXMF_HEADROOM_BYTES

    /** What one fragment may carry. */
    const val MAX_FRAGMENT_BYTES = MAX_FRAGMENT_FRAME_BYTES - HEADER_BYTES

    /**
     * A transfer is at most this many fragments.
     *
     * Far above the crossover where this scheme stops being the right one, and
     * small enough that a corrupt count cannot ask a receiver for megabytes.
     */
    const val MAX_FRAGMENTS = 255

    /**
     * How long a partial transfer is worth holding.
     *
     * Long enough for LXMF to exhaust its retries on a slow path, short enough
     * that a sender who gave up does not leave a buffer resident. A partial
     * drawing is worth nothing, so holding one longer buys nothing either.
     */
    const val REASSEMBLY_TIMEOUT_MS = 5L * 60 * 1000

    data class Part(val transferId: Long, val index: Int, val count: Int, val slice: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Part && transferId == other.transferId && index == other.index &&
                    count == other.count && slice.contentEquals(other.slice))

        override fun hashCode(): Int = (transferId.hashCode() * 31 + index) * 31 + count
    }

    /**
     * Cut a payload into fragments.
     *
     * A payload that already fits is still returned as one fragment rather
     * than special-cased: a receiver that only understands fragments is
     * simpler than one that must also recognise an unfragmented form, and
     * seven bytes buys that.
     */
    fun fragments(payload: ByteArray, transferId: Long = randomTransferId()): List<ByteArray> {
        require(payload.isNotEmpty()) { "nothing to fragment" }
        val count = (payload.size + MAX_FRAGMENT_BYTES - 1) / MAX_FRAGMENT_BYTES
        require(count <= MAX_FRAGMENTS) {
            "${payload.size} bytes needs $count fragments, over the " +
                "$MAX_FRAGMENTS-fragment bound; this belongs in a Resource, not here"
        }
        return (0 until count).map { index ->
            val from = index * MAX_FRAGMENT_BYTES
            val to = minOf(from + MAX_FRAGMENT_BYTES, payload.size)
            val slice = payload.copyOfRange(from, to)
            ByteArray(HEADER_BYTES + slice.size).also { frame ->
                frame[0] = TakPayload.FRAGMENT_V1.toByte()
                frame[1] = ((transferId shr 24) and 0xFF).toByte()
                frame[2] = ((transferId shr 16) and 0xFF).toByte()
                frame[3] = ((transferId shr 8) and 0xFF).toByte()
                frame[4] = (transferId and 0xFF).toByte()
                frame[5] = index.toByte()
                frame[6] = count.toByte()
                slice.copyInto(frame, HEADER_BYTES)
            }
        }
    }

    /**
     * A fragment's parts, or null if this frame is not one.
     *
     * Null is ordinary: every other codec shares this destination, and a frame
     * that is not ours is not an error.
     */
    fun decode(frame: ByteArray): Part? {
        if (frame.size <= HEADER_BYTES ||
            (frame[0].toInt() and 0xFF) != TakPayload.FRAGMENT_V1
        ) {
            return null
        }
        val transferId =
            ((frame[1].toLong() and 0xFF) shl 24) or
                ((frame[2].toLong() and 0xFF) shl 16) or
                ((frame[3].toLong() and 0xFF) shl 8) or
                (frame[4].toLong() and 0xFF)
        val index = frame[5].toInt() and 0xFF
        val count = frame[6].toInt() and 0xFF
        // A count of zero cannot be satisfied and an index outside it never
        // will be. Both mean a frame that is not what it claims, and holding a
        // buffer for one is how a malformed peer costs us memory.
        if (count < 1 || index >= count) return null
        return Part(transferId, index, count, frame.copyOfRange(HEADER_BYTES, frame.size))
    }

    private fun randomTransferId(): Long =
        // Random rather than sequential: two nodes fragmenting at the same
        // moment must not collide, and a counter would have to survive a
        // restart to promise that.
        (0 until 4).fold(0L) { acc, _ -> (acc shl 8) or (0..255).random().toLong() }
}
