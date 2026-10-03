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
        own.record(frame, intervalMin = 3, nowMs = 0)
        assertArrayEquals(frame, own.current(6 * 60_000L))
        assertNull(own.current(6 * 60_000L + 1))
    }

    @Test
    fun `an unstated cadence holds for twice the default`() {
        val own = OwnPosition()
        own.record(byteArrayOf(9), intervalMin = 0, nowMs = 0)
        assertArrayEquals(byteArrayOf(9), own.current(2 * CotPosition.DEFAULT_INTERVAL_MS))
        assertNull(own.current(2 * CotPosition.DEFAULT_INTERVAL_MS + 1))
    }

    @Test
    fun `each member is answered at most once per window`() {
        val own = OwnPosition()
        own.record(byteArrayOf(1), intervalMin = 10, nowMs = 0)
        assertArrayEquals(byteArrayOf(1), own.answerTo("a", 0))
        assertNull(own.answerTo("a", OwnPosition.ANSWER_EVERY_MS - 1))
        assertArrayEquals(byteArrayOf(1), own.answerTo("b", 1))
        assertArrayEquals(byteArrayOf(1), own.answerTo("a", OwnPosition.ANSWER_EVERY_MS))
    }

    @Test
    fun `nothing that no longer holds is answered`() {
        val own = OwnPosition()
        own.record(byteArrayOf(1), intervalMin = 1, nowMs = 0)
        assertNull(own.answerTo("a", 3 * 60_000L))
    }
}
