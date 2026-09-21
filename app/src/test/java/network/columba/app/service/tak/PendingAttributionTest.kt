package network.columba.app.service.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A frame from a sender not yet known is held, not dropped.
 *
 * Measured on the bench 2026-09-21: a Nexus 6P's first exchange with the deck
 * lost the deck's reply -- delivered with a proof, dropped because the Nexus had
 * not yet heard the deck announce. The Kotlin half of `tests/test_cot_pending.py`.
 */
class PendingAttributionTest {
    private val team = "Cyan"
    private val secret = "a fleet secret long enough to be used".toByteArray(Charsets.UTF_8)
    private val ours = ByteArray(16) { 0x44 }
    private val deck = ByteArray(16) { 0x33 }

    private val registry = TakMembership.Registry(team, secret, ownHash = ours)
    private val renderer = CotRenderer(registry, team, ours, positionStaleMs = 60_000, chatStaleMs = 600_000)

    private fun chatFromDeck() =
        CotChat.encode(
            CotChat.KIND_MESSAGE,
            TakMembership.senderIdFor(deck),
            "5d0a1b2c-3d4e-4f60-8a9b-0c1d2e3f4a5b",
            "DECK",
            text = "SALT",
            recipient = TakIdentity.uidFor(ours),
        )

    private fun deckAnnounces(now: Long) {
        registry.remember(deck, TakMembership.memberPayload(team, secret, "DECK"), now)
    }

    @Test
    fun `a line from a sender not yet known is unattributed, not dropped`() {
        assertEquals(CotRenderer.Rendered.Unattributed, renderer.render(chatFromDeck(), 1_000))
    }

    @Test
    fun `a held line is drawn once its sender announces`() {
        val pending = PendingAttribution()
        pending.hold(chatFromDeck(), 1_000)
        assertTrue("nothing is ready before the announce", pending.ready(registry, 2_000).isEmpty())

        deckAnnounces(3_000)
        val released = pending.ready(registry, 3_000)

        assertEquals(1, released.size)
        assertTrue(renderer.render(released.single(), 3_000) is CotRenderer.Rendered.Cot)
        assertEquals(0, pending.size())
    }

    @Test
    fun `a line nobody claims is dropped after the hold`() {
        val pending = PendingAttribution(holdMs = 600_000)
        pending.hold(chatFromDeck(), 0)
        deckAnnounces(700_000)

        assertTrue(pending.ready(registry, 700_000).isEmpty())
        assertEquals(0, pending.size())
    }

    @Test
    fun `the hold is bounded`() {
        val pending = PendingAttribution(maxHeld = 3)
        repeat(10) { pending.hold(chatFromDeck(), it.toLong()) }
        assertEquals(3, pending.size())
    }

    @Test
    fun `a line addressed to somebody else is still simply not shown`() {
        // Not yet named waits; not for us does not.
        deckAnnounces(1_000)
        val forSomeoneElse =
            CotChat.encode(
                CotChat.KIND_MESSAGE, TakMembership.senderIdFor(deck),
                "5d0a1b2c-3d4e-4f60-8a9b-0c1d2e3f4a5b", "DECK",
                text = "private", recipient = TakIdentity.uidFor(ByteArray(16) { 0x55 }),
            )
        assertEquals(CotRenderer.Rendered.Handled, renderer.render(forSomeoneElse, 2_000))
    }
}
