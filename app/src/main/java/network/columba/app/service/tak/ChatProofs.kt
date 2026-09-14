package network.columba.app.service.tak

/**
 * What a sent chat needs remembered so its delivery tick can be drawn locally.
 *
 * The tick used to come from the far end: its ATAK wrote a delivered-receipt,
 * which crossed the mesh as a second LXMF message with its own retry budget.
 * LXMF has already proved the peer's node holds the message, so that is paying
 * twice for one answer -- and the second payment is the one that fails, leaving
 * no tick on a line that did arrive.
 *
 * Measured on the LoRa path 2026-09-14: receipts were 11 of 24 outbound
 * messages, every message averaged 1.9 packet attempts, and three messages to
 * one destination were seen retrying against each other at once.
 *
 * Bounded, because a proof may never arrive. A node whose peer never answers
 * must not accumulate one entry per message it ever sent; the oldest is dropped
 * because the newest is the one an operator is still looking at.
 */
class ChatProofs(private val maxPending: Int = MAX_PENDING) {
    companion object {
        /**
         * Comfortably more than a conversation, far less than a session. LXMF
         * gives up on a message long before this many follow it.
         */
        const val MAX_PENDING = 64
    }

    /** What the receipt is rendered from, once the proof arrives. */
    data class Pending(
        val peer: ByteArray,
        val messageId: String,
        val room: String,
    ) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (other is Pending && messageId == other.messageId &&
                    room == other.room && peer.contentEquals(other.peer))

        override fun hashCode(): Int = messageId.hashCode()
    }

    private val pending = LinkedHashMap<String, Pending>()

    /** Remember a sent message, keyed by the hash the backend reports for it. */
    @Synchronized
    fun awaiting(messageHash: String, entry: Pending) {
        while (pending.size >= maxPending) {
            val oldest = pending.keys.firstOrNull() ?: break
            pending.remove(oldest)
        }
        pending[messageHash] = entry
    }

    /**
     * Take what a proof refers to, or null if this is not ours.
     *
     * Taken rather than read: a proof arrives once, and a second delivery
     * notification for the same message must not draw a second tick.
     */
    @Synchronized
    fun claim(messageHash: String): Pending? = pending.remove(messageHash)

    /** A send that failed outright is not waiting for anything. */
    @Synchronized
    fun forget(messageHash: String) {
        pending.remove(messageHash)
    }

    @Synchronized
    fun size(): Int = pending.size
}
