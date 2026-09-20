package network.columba.app.service.tak

/**
 * Partial transfers, until they are whole or too old.
 *
 * The Kotlin half of `Reassembler` in `tools/cot_fragment.py`.
 *
 * **Keyed on the sender as well as the transfer id.** Two peers can pick the
 * same random id, and merging their fragments would produce a frame that
 * decodes to something neither of them sent -- which is worse than dropping
 * both. The sender comes from the packet, never from the frame: a peer that
 * could choose its own key could merge itself into somebody else's transfer.
 */
class CotReassembler(
    private val timeoutMs: Long = CotFragment.REASSEMBLY_TIMEOUT_MS,
    private val maxTransfers: Int = MAX_TRANSFERS,
) {
    companion object {
        /** A node that never completes a transfer must not grow this forever. */
        const val MAX_TRANSFERS = 16
    }

    private class Partial(val count: Int, val first: Long) {
        val slices = HashMap<Int, ByteArray>()
    }

    private val partial = LinkedHashMap<Pair<String, Long>, Partial>()

    var completed = 0
        private set
    var dropped = 0
        private set

    /** Take one fragment. Returns the whole payload, or null if not yet. */
    @Synchronized
    fun feed(sender: ByteArray?, frame: ByteArray, now: Long): ByteArray? {
        val part = CotFragment.decode(frame) ?: return null
        expire(now)
        val key = (sender?.joinToString("") { "%02x".format(it) } ?: "") to part.transferId
        val entry = entryFor(key, part, now) ?: return null
        entry.slices[part.index] = part.slice
        return if (entry.slices.size < part.count) {
            null
        } else {
            partial.remove(key)
            completed += 1
            join(entry.slices, part.count)
        }
    }

    /**
     * The transfer this fragment belongs to, or null if it cannot belong to one.
     *
     * Null means the same id has already been seen describing a different
     * length. One of the two is not what it claims, and neither is worth
     * guessing at.
     */
    private fun entryFor(key: Pair<String, Long>, part: CotFragment.Part, now: Long): Partial? {
        val existing = partial[key]
        if (existing != null) {
            if (existing.count == part.count) return existing
            partial.remove(key)
            dropped += 1
            return null
        }
        if (partial.size >= maxTransfers) {
            // The oldest goes, because the newest is the one still arriving.
            partial.keys.firstOrNull()?.let { partial.remove(it); dropped += 1 }
        }
        return Partial(part.count, now).also { partial[key] = it }
    }

    private fun join(slices: Map<Int, ByteArray>, count: Int): ByteArray {
        val whole = ByteArray((0 until count).sumOf { slices.getValue(it).size })
        var at = 0
        for (index in 0 until count) {
            val slice = slices.getValue(index)
            slice.copyInto(whole, at)
            at += slice.size
        }
        return whole
    }

    @Synchronized
    fun pending(): Int = partial.size

    /** A partial transfer is worth nothing, so it is not worth keeping. */
    private fun expire(now: Long) {
        val stale = partial.filterValues { now - it.first > timeoutMs }.keys.toList()
        stale.forEach { partial.remove(it); dropped += 1 }
    }
}
