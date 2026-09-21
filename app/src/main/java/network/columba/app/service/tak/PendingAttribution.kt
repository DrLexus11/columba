package network.columba.app.service.tak

/**
 * Frames from a sender this node cannot name yet, held until it can.
 *
 * The Kotlin half of `tools/cot_pending.py`.
 *
 * Membership turns a frame's four-byte sender id into a teammate, and it is
 * learned from announces -- so a node that has just joined spends a while unable
 * to name anyone, and a chat line or marker arriving then used to be dropped.
 * Delivered, proved at the transport, and silently gone. Measured on the bench
 * 2026-09-21: a Nexus 6P's first exchange with the deck lost the deck's reply,
 * because the Nexus had not yet heard the deck announce.
 *
 * The team check is not weakened: a held frame is only ever drawn once its
 * sender has announced into the team, and one nobody claims within the hold is
 * dropped for good.
 */
class PendingAttribution(
    private val holdMs: Long = HOLD_MS,
    private val maxHeld: Int = MAX_HELD,
) {
    companion object {
        const val HOLD_MS = 10L * 60 * 1000
        const val MAX_HELD = 32

        /** The sender id a chat or marker frame claims, or null for anything else. */
        fun senderIdOf(raw: ByteArray): Int? =
            when (TakPayload.kindOf(raw)) {
                TakPayload.CHAT_V1 -> CotChat.decode(raw)?.senderId
                TakPayload.MARKER_V1 -> CotMarker.decode(raw)?.senderId
                else -> null
            }
    }

    private class Held(val at: Long, val raw: ByteArray)

    private val held = ArrayDeque<Held>()

    @Synchronized
    fun hold(raw: ByteArray, now: Long) {
        held.addLast(Held(now, raw.copyOf()))
        while (held.size > maxHeld) held.removeFirst()
    }

    /**
     * The held frames whose sender [registry] can now name, oldest first; they
     * are forgotten. Frames past the hold are dropped for good.
     */
    @Synchronized
    fun ready(registry: TakMembership.Registry, now: Long): List<ByteArray> {
        held.removeAll { now - it.at > holdMs }
        val released =
            held.filter { entry ->
                senderIdOf(entry.raw)?.let { registry.resolveSenderId(it, now) } != null
            }
        held.removeAll { it in released }
        return released.map { it.raw }
    }

    @Synchronized
    fun size(): Int = held.size
}
