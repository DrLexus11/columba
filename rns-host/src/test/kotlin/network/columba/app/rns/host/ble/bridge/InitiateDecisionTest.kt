package network.columba.app.rns.host.ble.bridge

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Of two tagged phones, only the lower identity connects out, and never to a
 * phone it is already linked to. Measured 2026-09-26: without this each phone
 * connected to the other, the second attempt went to the same phone under a
 * rotated address, and closing it tore down the working link every minute.
 */
class InitiateDecisionTest {
    private val low = "11".repeat(16)
    private val high = "99".repeat(16)
    private val lowTag = low.take(16)
    private val highTag = high.take(16)

    @Test
    fun `the lower identity connects out`() {
        assertEquals(InitiateDecision.CONNECT, initiateDecision(low, highTag, emptyList(), 0, 0))
    }

    @Test
    fun `the higher identity waits for the peer to connect`() {
        assertEquals(InitiateDecision.PEER_INITIATES, initiateDecision(high, lowTag, emptyList(), 0, 1_000))
    }

    @Test
    fun `but not for ever -- the radio path may work one way only`() {
        assertEquals(InitiateDecision.CONNECT, initiateDecision(high, lowTag, emptyList(), 0, TAG_DEFER_MS))
    }

    @Test
    fun `a phone already linked under another address is not connected again`() {
        assertEquals(InitiateDecision.ALREADY_LINKED, initiateDecision(low, highTag, listOf(high), 0, 0))
    }

    @Test
    fun `an untagged advertiser is connected as before`() {
        assertEquals(InitiateDecision.CONNECT, initiateDecision(high, null, listOf(low), 0, 0))
    }

    @Test
    fun `before our own identity is known, nothing changes`() {
        assertEquals(InitiateDecision.CONNECT, initiateDecision(null, lowTag, emptyList(), 0, 0))
    }
}
