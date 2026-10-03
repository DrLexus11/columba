package network.columba.app.service.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LastPositionsTest {
    @Test
    fun `each peer is replayed once, at its latest`() {
        val positions = LastPositions()
        positions.remember("a", "<a1/>", staleAtMs = 1_000)
        positions.remember("b", "<b1/>", staleAtMs = 1_000)
        positions.remember("a", "<a2/>", staleAtMs = 2_000)
        assertEquals(listOf("<b1/>", "<a2/>"), positions.forReplay(nowMs = 500))
    }

    @Test
    fun `a stale position is still replayed, as drawn, until the hold runs out`() {
        val positions = LastPositions(holdMs = 10_000)
        positions.remember("a", "<a/>", staleAtMs = 1_000)
        assertEquals(listOf("<a/>"), positions.forReplay(nowMs = 5_000)) // stale, greyed in ATAK
        assertTrue(positions.forReplay(nowMs = 11_001).isEmpty())
    }

    @Test
    fun `the oldest peer gives way when the table is full`() {
        val positions = LastPositions(maxPeers = 2)
        positions.remember("a", "<a/>", 1_000)
        positions.remember("b", "<b/>", 1_000)
        positions.remember("c", "<c/>", 1_000)
        assertEquals(listOf("<b/>", "<c/>"), positions.forReplay(0))
    }
}
