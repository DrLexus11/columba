package network.columba.app.service.tak

/**
 * The last position report this node put on the air, while it still holds.
 *
 * For a member that has just announced: a phone that restarted, Columba and
 * all, has forgotten this node's position, and a node that is not moving may
 * not report again for minutes -- so it waited, off that member's map and out
 * of its contact list (bench, 2026-10-02). Sent to that member alone: one
 * 21-byte packet, not a broadcast.
 *
 * Only while the report still holds -- twice its stated cadence, as receivers
 * keep it fresh -- because a receiver draws what it is given as current. An
 * older report is not offered; the next one is due anyway.
 */
class OwnPosition {
    private class Sent(
        val frame: ByteArray,
        val fixMs: Long,
        val atMs: Long,
        val holdsMs: Long,
    )

    private var last: Sent? = null
    private val answered = LinkedHashMap<String, Long>()

    /**
     * A report that went out: its encoded [frame], the cadence it stated, and
     * when the fix was taken. Reports leave from concurrent ATAK clients and
     * the handset's own loop, and a send can finish after a later one: an
     * older fix never replaces a newer one.
     */
    @Synchronized
    fun record(
        frame: ByteArray,
        intervalMin: Int,
        fixMs: Long,
        nowMs: Long,
    ) {
        val held = last
        if (held != null && fixMs < held.fixMs) return
        val cadenceMs = if (intervalMin > 0) intervalMin * MINUTE_MS else CotPosition.DEFAULT_INTERVAL_MS
        last = Sent(frame, fixMs, nowMs, 2 * cadenceMs)
    }

    /** The last report, if it still holds now; null otherwise. */
    @Synchronized
    fun current(nowMs: Long): ByteArray? = last?.takeIf { nowMs - it.atMs <= it.holdsMs }?.frame

    /**
     * The report to send [member], who has just announced -- or null if none
     * holds, or that member was answered within [ANSWER_EVERY_MS]. Members
     * announce on their own cadence (a command post every five minutes), and
     * answering every one on LoRa would grow with the square of the team.
     * Nothing is counted until [answered]: a send that fails must not use up
     * the member's allowance.
     */
    @Synchronized
    fun offerFor(
        member: String,
        nowMs: Long,
    ): ByteArray? {
        val before = answered[member]
        if (before != null && nowMs - before < ANSWER_EVERY_MS) return null
        return current(nowMs)
    }

    /** [member] was sent the report: its allowance starts now. */
    @Synchronized
    fun answered(
        member: String,
        nowMs: Long,
    ) {
        answered.remove(member)
        answered[member] = nowMs
        while (answered.size > MAX_MEMBERS) answered.remove(answered.keys.first())
    }

    companion object {
        /** At most one answer per member this often. */
        const val ANSWER_EVERY_MS = 10L * 60 * 1000
        private const val MAX_MEMBERS = 256
        private const val MINUTE_MS = 60_000L
    }
}
