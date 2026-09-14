package network.columba.app.service.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a sent chat keeps so its delivery tick can be drawn from LXMF's proof
 * instead of waited for across the mesh.
 */
class ChatProofsTest {
    private fun pending(id: String) =
        ChatProofs.Pending(peer = ByteArray(16) { 1 }, messageId = id, room = "PEER")

    @Test
    fun `a proof finds what it was waiting on`() {
        val proofs = ChatProofs()
        proofs.awaiting("hash-1", pending("msg-1"))
        assertEquals("msg-1", proofs.claim("hash-1")?.messageId)
    }

    @Test
    fun `a proof is claimed once`() {
        // A second delivery notification for the same message must not draw a
        // second tick.
        val proofs = ChatProofs()
        proofs.awaiting("hash-1", pending("msg-1"))
        proofs.claim("hash-1")
        assertNull(proofs.claim("hash-1"))
    }

    @Test
    fun `somebody else's message is not ours to acknowledge`() {
        assertNull(ChatProofs().claim("hash-nobody-sent"))
    }

    @Test
    fun `it is bounded, because a proof may never arrive`() {
        // A node whose peer never answers must not accumulate one entry per
        // message it has ever sent.
        val proofs = ChatProofs(maxPending = 4)
        repeat(20) { proofs.awaiting("hash-$it", pending("msg-$it")) }
        assertEquals(4, proofs.size())
    }

    @Test
    fun `the newest survive, because they are what an operator is still watching`() {
        val proofs = ChatProofs(maxPending = 2)
        repeat(4) { proofs.awaiting("hash-$it", pending("msg-$it")) }
        assertNull(proofs.claim("hash-0"))
        assertEquals("msg-3", proofs.claim("hash-3")?.messageId)
    }

    @Test
    fun `a send that failed outright is not left waiting`() {
        val proofs = ChatProofs()
        proofs.awaiting("hash-1", pending("msg-1"))
        proofs.forget("hash-1")
        assertEquals(0, proofs.size())
    }
}
