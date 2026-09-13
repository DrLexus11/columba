package network.columba.app.service

/**
 * How soon to ask the propagation node again after an attempt that failed.
 *
 * Retrieval is a timer, and the interval defaults to an hour. That is a
 * reasonable idle cadence and a bad failure response, because **failures
 * correlate exactly with the outages that make a sync worth doing**: the
 * attempt most likely to fail is the one made while the node is cut off, and
 * forfeiting the interval for it means the node is silent for the hour
 * *following* the partition -- precisely when the command post is holding its
 * traffic.
 *
 * Measured on hardware 2026-09-13:
 *
 * ```
 * 09:28:21  sync begins -- during the partition
 * 09:28:25  link_establishing -> failed (241, connection failed)
 * 09:31:37  node back on the mesh
 *           nothing. Next automatic attempt would have been ~10:28.
 * ```
 *
 * The message arrived only because an operator pressed sync by hand. A 60 s
 * first retry doubling from there would have reached it on the third attempt,
 * about two minutes after it came back.
 *
 * Doubling rather than a fixed short retry: a node that is genuinely alone
 * should not spend the afternoon opening links to a relay that is not there.
 * Capped at the configured interval, so backoff can only ever make the node
 * ask *sooner* than the timer would have, never later.
 */
object SyncBackoff {
    /** First retry after a failure. */
    const val BASE_MS = 60_000L

    /** Ceiling on the doubling, before the interval cap applies. */
    const val MAX_DOUBLINGS = 5

    /**
     * @param consecutiveFailures how many attempts in a row have failed; 0 means
     *   the last attempt succeeded and the ordinary interval applies.
     * @param intervalMs the configured retrieval interval.
     */
    fun delayMs(consecutiveFailures: Int, intervalMs: Long): Long {
        if (consecutiveFailures <= 0) return intervalMs
        val doublings = (consecutiveFailures - 1).coerceAtMost(MAX_DOUBLINGS)
        val backoff = BASE_MS shl doublings
        return minOf(backoff, intervalMs)
    }
}
