package network.columba.app.service.tak

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnPositionTest {
    @Test
    fun `nothing sent, nothing to offer`() {
        assertNull(OwnPosition().current(0))
    }

    @Test
    fun `a report is offered for twice its stated cadence`() {
        val own = OwnPosition()
        val frame = byteArrayOf(1, 2, 3)
        own.record(frame, intervalMin = 3, fixMs = 0, nowMs = 0)
        assertArrayEquals(frame, own.current(6 * 60_000L))
        assertNull(own.current(6 * 60_000L + 1))
    }

    @Test
    fun `an unstated cadence holds for twice the default`() {
        val own = OwnPosition()
        own.record(byteArrayOf(9), intervalMin = 0, fixMs = 0, nowMs = 0)
        assertArrayEquals(byteArrayOf(9), own.current(2 * CotPosition.DEFAULT_INTERVAL_MS))
        assertNull(own.current(2 * CotPosition.DEFAULT_INTERVAL_MS + 1))
    }

    @Test
    fun `an older fix finishing its send later does not replace a newer one`() {
        val own = OwnPosition()
        own.record(byteArrayOf(2), intervalMin = 1, fixMs = 2_000, nowMs = 2_000)
        own.record(byteArrayOf(1), intervalMin = 1, fixMs = 1_000, nowMs = 2_100)
        assertArrayEquals(byteArrayOf(2), own.current(2_200))
    }

    @Test
    fun `each member is answered at most once per window, counted only once sent`() {
        val own = OwnPosition()
        own.record(byteArrayOf(1), intervalMin = 10, fixMs = 0, nowMs = 0)
        assertArrayEquals(byteArrayOf(1), own.offerFor("a", 0))
        // The send failed: nothing was counted, so the next announce is answered.
        assertArrayEquals(byteArrayOf(1), own.offerFor("a", 1))
        own.answered("a", 1)
        assertNull(own.offerFor("a", OwnPosition.ANSWER_EVERY_MS))
        assertArrayEquals(byteArrayOf(1), own.offerFor("b", 2))
        assertArrayEquals(byteArrayOf(1), own.offerFor("a", 1 + OwnPosition.ANSWER_EVERY_MS))
    }

    @Test
    fun `nothing that no longer holds is offered`() {
        val own = OwnPosition()
        own.record(byteArrayOf(1), intervalMin = 1, fixMs = 0, nowMs = 0)
        assertNull(own.offerFor("a", 3 * 60_000L))
    }
}
