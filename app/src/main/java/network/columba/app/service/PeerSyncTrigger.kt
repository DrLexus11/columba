package network.columba.app.service

/**
 * Whether hearing a peer is a good enough reason to ask the propagation node.
 *
 * A peer being heard is good evidence that somebody is there. It is **not**
 * evidence that a Link will establish, and on a slow path those are very
 * different things.
 *
 * Measured on LoRa 2026-09-13, three hops: links established 5 times in 15,
 * store syncs were timing out at the full 300 s watchdog, and this trigger was
 * firing about once a minute into a channel already at 7% occupancy -- where a
 * link handshake is most of a second of airtime. So a fix written that morning
 * for chat latency had become a generator of the congestion that makes chat
 * slow.
 *
 * The trigger is right. Retrying it at a fixed rate through a path that keeps
 * refusing is not. After consecutive failures it stands down; any success
 * clears that immediately, because a path that has started working should not
 * be punished for having been broken.
 */
class PeerSyncTrigger(
    private val floorMs: Long = FLOOR_MS,
    private val backoffMs: Long = BACKOFF_MS,
    private val failuresBeforeBackoff: Int = FAILURES_BEFORE_BACKOFF,
) {
    companion object {
        /**
         * Ten nodes powering up together must not mean ten syncs: a sync is a
         * Link and a transfer, not a packet. Same floor the bridge uses for
         * the same trigger, so the two halves behave alike.
         */
        const val FLOOR_MS = 60_000L

        /** How long to stop asking once the path has proved it cannot carry a sync. */
        const val BACKOFF_MS = 10 * 60_000L

        /** Failures in a row before standing down. */
        const val FAILURES_BEFORE_BACKOFF = 2
    }

    private var lastAsk = 0L
    private var failures = 0

    /** How long must pass between asks, given what the path has been doing. */
    fun waitMs(): Long = if (failures >= failuresBeforeBackoff) backoffMs else floorMs

    /** True if enough time has passed to ask again; records the ask if so. */
    fun shouldAsk(now: Long): Boolean {
        if (lastAsk != 0L && now - lastAsk < waitMs()) return false
        lastAsk = now
        return true
    }

    /** The path carried a sync, so stop holding its past against it. */
    fun succeeded() {
        failures = 0
    }

    /**
     * The path did not carry a sync.
     *
     * @return true when this is the failure that triggers standing down, so
     *   the caller can say so once rather than on every attempt.
     */
    fun failed(): Boolean {
        failures += 1
        return failures == failuresBeforeBackoff
    }

    /** For logging and for tests. */
    fun consecutiveFailures(): Int = failures
}
