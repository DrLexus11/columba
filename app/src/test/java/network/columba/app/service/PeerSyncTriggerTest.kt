package network.columba.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When hearing a peer is a good enough reason to dial the propagation node.
 *
 * The trigger was added on 2026-09-13 to fix chat latency and promptly became a
 * generator of the congestion that makes chat slow: it fired about once a
 * minute into a LoRa channel already at 7% occupancy, where a link handshake at
 * three hops is most of a second of airtime, and the syncs it started were
 * timing out at a 300 s watchdog.
 */
class PeerSyncTriggerTest {
    @Test
    fun `the first ask goes straight through`() {
        assertTrue(PeerSyncTrigger().shouldAsk(now = 1_000))
    }

    @Test
    fun `a second ask inside the floor is refused`() {
        // Ten nodes powering up together must not mean ten syncs.
        val trigger = PeerSyncTrigger()
        trigger.shouldAsk(1_000)
        assertFalse(trigger.shouldAsk(1_000 + PeerSyncTrigger.FLOOR_MS - 1))
    }

    @Test
    fun `once the floor has passed it asks again`() {
        val trigger = PeerSyncTrigger()
        trigger.shouldAsk(1_000)
        assertTrue(trigger.shouldAsk(1_000 + PeerSyncTrigger.FLOOR_MS))
    }

    @Test
    fun `after consecutive failures it stands down`() {
        // The whole point. A peer being heard says somebody is there; it says
        // nothing about whether a Link will establish, and on a slow path
        // those are very different things.
        val trigger = PeerSyncTrigger()
        var clock = 1_000L
        trigger.shouldAsk(clock)
        repeat(PeerSyncTrigger.FAILURES_BEFORE_BACKOFF) { trigger.failed() }
        assertEquals(PeerSyncTrigger.BACKOFF_MS, trigger.waitMs())
        clock += PeerSyncTrigger.FLOOR_MS
        assertFalse("the floor is no longer enough", trigger.shouldAsk(clock))
        clock = 1_000L + PeerSyncTrigger.BACKOFF_MS
        assertTrue(trigger.shouldAsk(clock))
    }

    @Test
    fun `one success clears the backoff immediately`() {
        // A path that has started working should not be punished for having
        // been broken.
        val trigger = PeerSyncTrigger()
        repeat(PeerSyncTrigger.FAILURES_BEFORE_BACKOFF) { trigger.failed() }
        assertEquals(PeerSyncTrigger.BACKOFF_MS, trigger.waitMs())
        trigger.succeeded()
        assertEquals(PeerSyncTrigger.FLOOR_MS, trigger.waitMs())
        assertEquals(0, trigger.consecutiveFailures())
    }

    @Test
    fun `standing down is announced once, not on every attempt`() {
        // failed() returns true only on the failure that crosses the line, so
        // a caller can log it once. A warning repeated every attempt is one
        // people learn to scroll past.
        val trigger = PeerSyncTrigger(failuresBeforeBackoff = 2)
        assertFalse(trigger.failed())
        assertTrue(trigger.failed())
        assertFalse(trigger.failed())
    }

    @Test
    fun `backing off is much longer than the floor, or it is not backing off`() {
        assertTrue(PeerSyncTrigger.BACKOFF_MS > PeerSyncTrigger.FLOOR_MS)
    }
}
