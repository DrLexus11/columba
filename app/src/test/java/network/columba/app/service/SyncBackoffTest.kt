package network.columba.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncBackoffTest {
    private val hour = 3_600_000L

    @Test
    fun `a successful attempt waits the ordinary interval`() {
        assertEquals(hour, SyncBackoff.delayMs(consecutiveFailures = 0, intervalMs = hour))
    }

    @Test
    fun `the first failure retries in a minute, not in an hour`() {
        // The case this exists for. On hardware the one scheduled attempt fell
        // inside the outage, failed, and the node then sat silent while the
        // command post held its traffic.
        assertEquals(60_000L, SyncBackoff.delayMs(consecutiveFailures = 1, intervalMs = hour))
    }

    @Test
    fun `it doubles, so a node that is genuinely alone stops hammering`() {
        assertEquals(60_000L, SyncBackoff.delayMs(1, hour))
        assertEquals(120_000L, SyncBackoff.delayMs(2, hour))
        assertEquals(240_000L, SyncBackoff.delayMs(3, hour))
        assertEquals(480_000L, SyncBackoff.delayMs(4, hour))
    }

    @Test
    fun `backoff never waits longer than the interval would have`() {
        // Backoff may only make the node ask sooner. A failure must never
        // become a reason to check less often than an idle node does.
        for (failures in 1..50) {
            assertTrue(
                "failures=$failures",
                SyncBackoff.delayMs(failures, hour) <= hour,
            )
        }
    }

    @Test
    fun `a short configured interval is not lengthened by a failure`() {
        // An operator who asked for every 30 seconds gets every 30 seconds,
        // failing or not.
        val thirtySeconds = 30_000L
        assertEquals(thirtySeconds, SyncBackoff.delayMs(1, thirtySeconds))
        assertEquals(thirtySeconds, SyncBackoff.delayMs(9, thirtySeconds))
    }

    @Test
    fun `the doubling is bounded`() {
        val huge = Long.MAX_VALUE / 2
        val capped = SyncBackoff.delayMs(99, huge)
        assertEquals(SyncBackoff.BASE_MS shl SyncBackoff.MAX_DOUBLINGS, capped)
    }
}
