package network.columba.app.service.tak

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An event addressed to this node that fits in one frame comes by LXMF.
 *
 * It used to go as a bare packet, which names no sender: a file notice sent
 * that way reached ATAK with nobody to fetch the file from (bench,
 * 2026-09-22). Over LXMF it is proved, and drawn only from a member -- the
 * gate a fragment passes.
 */
class TakLxmfCarriageTest {
    private val team = "Cyan"
    private val secret = "a fleet secret long enough to be used".toByteArray(Charsets.UTF_8)
    private val ours = ByteArray(16) { 0x44 }
    private val deck = ByteArray(16) { 0x33 }
    private val stranger = ByteArray(16) { 0x66 }
    private val identity = Identity(ByteArray(16) { 0x01 }, ByteArray(64) { 0x02 }, null)

    private val registry =
        TakMembership.Registry(team, secret, ownHash = ours).also {
            it.remember(deck, TakMembership.memberPayload(team, secret, "DECK"), System.currentTimeMillis())
        }
    private val renderer = CotRenderer(registry, team, ours, positionStaleMs = 120_000, chatStaleMs = 600_000)

    private val event =
        "<event version=\"2.0\" uid=\"c4f2e1a0-1111-2222-3333-444455556666\" type=\"b-f-t-r\" how=\"h-e\" " +
            "time=\"2026-09-22T10:39:32.087Z\" start=\"2026-09-22T10:39:32.087Z\" stale=\"2026-09-22T10:49:32.087Z\">" +
            "<point lat=\"41.0\" lon=\"29.0\" hae=\"50\" ce=\"9999999\" le=\"9999999\"/><detail/></event>"

    private fun carriage(provedAs: ByteArray): TakLxmfCarriage {
        val rnsCore = mockk<RnsCore>()
        coEvery { rnsCore.recallIdentity(any()) } returns identity
        coEvery { rnsCore.createDestination(any(), any(), any(), any(), any()) } returns
            Result.success(
                Destination(provedAs, "", identity, Direction.OUT, DestinationType.SINGLE, "rnstransport", listOf("tak", "node")),
            )
        return TakLxmfCarriage(rnsCore)
    }

    private suspend fun deliverFrom(provedAs: ByteArray): List<String> {
        val frames = CotOutbound("urtn-" + "aa".repeat(16)).frames(event)
        assertEquals("a small event is one frame", 1, frames.size)
        val drawn = mutableListOf<String>()
        carriage(provedAs).deliver(
            TakLxmf.Inbound(ByteArray(16) { 0x55 }, frames.single()),
            CotReassembler(), renderer, Freshness(), PendingAttribution(),
        ) { drawn += String(it, Charsets.UTF_8) }
        return drawn
    }

    @Test
    fun `a one-frame event from a member is drawn`() =
        runTest {
            val drawn = deliverFrom(deck)
            assertEquals(1, drawn.size)
            assertTrue(drawn.single().contains("b-f-t-r"))
        }

    @Test
    fun `a one-frame event from outside the team is refused`() =
        runTest {
            assertTrue(deliverFrom(stranger).isEmpty())
        }
}
