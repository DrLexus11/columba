package network.columba.app.service.tak

/**
 * The last position report this node put on the air, while it still holds.
 *
 * For a member that has just announced: a phone that restarted, Columba and
 * all, has forgotten this node's position, and a node that is not moving may
 * not report again for minutes -- so it waited, off that member's map and out
 * of its contact list (bench, 2026-10-02). Sent to that member alone, once per
 * greeting: one 21-byte packet, not a broadcast.
 *
 * Only while the report still holds -- twice its stated cadence, as receivers
 * keep it fresh -- because a receiver draws what it is given as current. An
 * older report is not offered; the next one is due anyway.
 */
class OwnPosition {
    private class Sent(
        val frame: ByteArray,
        val atMs: Long,
        val holdsMs: Long,
    )

    @Volatile private var last: Sent? = null

    /** A report that went out: its encoded [frame] and the cadence it stated. */
    fun record(
        frame: ByteArray,
        intervalMin: Int,
        nowMs: Long,
    ) {
        val cadenceMs = if (intervalMin > 0) intervalMin * MINUTE_MS else CotPosition.DEFAULT_INTERVAL_MS
        last = Sent(frame, nowMs, 2 * cadenceMs)
    }

    /** The last report, if it still holds now; null otherwise. */
    fun current(nowMs: Long): ByteArray? = last?.takeIf { nowMs - it.atMs <= it.holdsMs }?.frame

    private val answered = LinkedHashMap<String, Long>()

    /**
     * The report to send [member], who has just announced -- or null if it does
     * not hold, or that member was answered within [ANSWER_EVERY_MS]. Members
     * announce on their own cadence (a command post every five minutes), and
     * answering every one on LoRa would grow with the square of the team.
     */
    @Synchronized
    fun answerTo(
        member: String,
        nowMs: Long,
    ): ByteArray? {
        val frame = current(nowMs) ?: return null
        val before = answered[member]
        if (before != null && nowMs - before < ANSWER_EVERY_MS) return null
        answered.remove(member)
        answered[member] = nowMs
        while (answered.size > MAX_MEMBERS) answered.remove(answered.keys.first())
        return frame
    }

    companion object {
        /** At most one answer per member this often. */
        const val ANSWER_EVERY_MS = 10L * 60 * 1000
        private const val MAX_MEMBERS = 256
        private const val MINUTE_MS = 60_000L
    }
}
