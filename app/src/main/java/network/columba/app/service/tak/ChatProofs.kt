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

    /**
     * Proofs that arrived before the send they belong to was registered.
     *
     * The race is real and not rare: both backends install their delivery
     * callback before dispatching the send, and the delivery-status stream does
     * not replay. An opportunistic message delivered immediately -- a peer one
     * hop away, which is the common case in a room -- is therefore proved while
     * [awaiting] has not been called yet. The update was dropped and the sender
     * never got its tick for the message most certain to have arrived.
     *
     * Hashes only, and bounded: most of what lands here is a proof for the
     * operator's own ordinary messaging, which is not ours and is simply aged
     * out.
     */
    private val provedEarly = LinkedHashSet<String>()

    /**
     * Remember a sent message, keyed by the hash the backend reports for it.
     *
     * @return true if the proof had already arrived, in which case the caller
     *   should draw the tick now: nothing further is coming for this hash.
     */
    @Synchronized
    fun awaiting(messageHash: String, entry: Pending): Boolean {
        if (provedEarly.remove(messageHash)) return true
        while (pending.size >= maxPending) {
            val oldest = pending.keys.firstOrNull() ?: break
            pending.remove(oldest)
        }
        pending[messageHash] = entry
        return false
    }

    /**
     * Take what a proof refers to, or null if this is not ours.
     *
     * Taken rather than read: a proof arrives once, and a second delivery
     * notification for the same message must not draw a second tick.
     */
    @Synchronized
    fun claim(messageHash: String): Pending? {
        val entry = pending.remove(messageHash)
        // Not ours, or ours and not registered yet -- and from here the two are
        // indistinguishable. Held so [awaiting] can reconcile if the send is
        // still on its way to registering, aged out otherwise.
        if (entry == null) rememberEarlyProof(messageHash)
        return entry
    }

    private fun rememberEarlyProof(messageHash: String) {
        while (provedEarly.size >= maxPending) {
            val oldest = provedEarly.firstOrNull() ?: break
            provedEarly.remove(oldest)
        }
        provedEarly.add(messageHash)
    }

    /** A send that failed outright is not waiting for anything. */
    @Synchronized
    fun forget(messageHash: String) {
        pending.remove(messageHash)
        provedEarly.remove(messageHash)
    }

    @Synchronized
    fun size(): Int = pending.size
}
