package network.columba.app.service.tak

import java.security.MessageDigest

/**
 * An oversized event ATAK has already sent us, sent again.
 *
 * The Kotlin half of `_repeat_of_a_large_event` in `tools/cot_bridge.py`.
 *
 * ATAK re-emits a shared drawing on a timer. As bare packets each repeat cost
 * one packet per fragment per member and was merely wasteful; over LXMF it
 * costs a message with a retry budget, so an unchanged drawing re-sent on a
 * share timer would load the channel for nothing. The far end already has it,
 * and LXMF is now what makes sure of that.
 *
 * Keyed on the event uid **and** the frame bytes, so an edited drawing still
 * goes: different geometry is different bytes, and an operator who moves a
 * line means it. Only a byte-identical repeat is dropped, and only for a
 * window, so a member who joins late still gets the next one.
 */
class LargeEventGate(
    private val windowMs: Long = WINDOW_MS,
    private val remembered: Int = MAX_REMEMBERED,
) {
    companion object {
        /**
         * Long enough to cover a share timer, short enough that a member who
         * joins late still gets the next repeat.
         */
        const val WINDOW_MS = 120_000L

        /** An operator drawing all morning must not grow this without bound. */
        const val MAX_REMEMBERED = 64
    }

    private class Seen(val digest: String, val at: Long)

    private val seen = LinkedHashMap<String, Seen>()

    @Synchronized
    fun isRepeat(cotXml: String, frames: List<ByteArray>, now: Long): Boolean {
        val uid = uidOf(cotXml) ?: return false
        val digest = digestOf(frames)
        val last = seen[uid]
        if (last != null && last.digest == digest && now - last.at < windowMs) return true
        seen[uid] = Seen(digest, now)
        if (seen.size > remembered) seen.keys.firstOrNull()?.let { seen.remove(it) }
        return false
    }

    /**
     * The event's uid, read off the start tag.
     *
     * Deliberately not a parse: this runs on every oversized event and only
     * needs one attribute. A malformed event has no uid here and is simply not
     * gated -- tier 2 has its own opinion about it.
     */
    private fun uidOf(cotXml: String): String? {
        val at = cotXml.indexOf("uid=\"")
        if (at < 0) return null
        val from = at + 5
        val to = cotXml.indexOf('"', from)
        return if (to > from) cotXml.substring(from, to) else null
    }

    private fun digestOf(frames: List<ByteArray>): String {
        val sha = MessageDigest.getInstance("SHA-256")
        frames.forEach { sha.update(it) }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}
