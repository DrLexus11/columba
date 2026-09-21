package network.columba.app.service.tak

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ATAK re-emits a shared drawing on a timer.
 *
 * As bare packets a repeat was merely wasteful. Over LXMF it costs a message
 * with a retry budget per fragment per member, so an unchanged drawing on a
 * share timer would load the channel for nothing.
 */
class LargeEventGateTest {
    private val gate = LargeEventGate()

    private fun drawing(uid: String = "DRAW-1") =
        """<event version="2.0" uid="$uid" type="u-d-f"><detail/></event>"""

    private fun frames(vararg bytes: Int) = bytes.map { byteArrayOf(it.toByte()) }

    @Test
    fun `the first time is never a repeat`() {
        assertFalse(gate.isRepeat(drawing(), frames(1, 2), 0L))
    }

    @Test
    fun `the same drawing again is suppressed`() {
        gate.isRepeat(drawing(), frames(1, 2), 0L)

        assertTrue(gate.isRepeat(drawing(), frames(1, 2), 1_000L))
    }

    @Test
    fun `an edited drawing still goes`() {
        // Different geometry is different bytes, and an operator who moves a
        // line means it.
        gate.isRepeat(drawing(), frames(1, 2), 0L)

        assertFalse(gate.isRepeat(drawing(), frames(1, 3), 1_000L))
    }

    @Test
    fun `a different drawing is not confused with this one`() {
        gate.isRepeat(drawing("DRAW-1"), frames(1, 2), 0L)

        assertFalse(gate.isRepeat(drawing("DRAW-2"), frames(1, 2), 1_000L))
    }

    @Test
    fun `the window expires so a late member gets the next repeat`() {
        gate.isRepeat(drawing(), frames(1, 2), 0L)

        assertFalse(gate.isRepeat(drawing(), frames(1, 2), LargeEventGate.WINDOW_MS + 1))
    }

    @Test
    fun `an event with no uid is not gated`() {
        assertFalse(gate.isRepeat("<event/>", frames(1), 0L))
    }

    @Test
    fun `the memory does not grow without bound`() {
        repeat(500) { gate.isRepeat(drawing("DRAW-$it"), frames(it and 0xFF), it.toLong()) }

        // Nothing to assert but that it still answers, and does so correctly:
        // the oldest went, the newest is the one still arriving.
        assertFalse(gate.isRepeat(drawing("DRAW-0"), frames(0), 1_000L))
    }
}
