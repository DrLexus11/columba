package network.columba.app.service.tak

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling "wait" apart from "act", because the two look identical from the
 * errno and want opposite responses.
 *
 * Seen on hardware 2026-09-13: the settings screen said "bind failed:
 * EADDRINUSE" for an hour while ATAK held the endpoint's port, and waiting was
 * never going to fix it. The port has since moved off ATAK's default; the two
 * causes still need telling apart.
 */
class CotPortConflictTest {
    private fun conflict(accepts: Boolean) =
        CotPortConflict(probe = { _, _ -> accepts })

    @Test
    fun `something answering on the port is another server`() = runTest {
        assertEquals(
            CotPortConflict.Cause.ANOTHER_SERVER,
            conflict(accepts = true).diagnose("127.0.0.1", CotEndpointManager.PORT),
        )
    }

    @Test
    fun `nothing answering is a socket on its way out`() = runTest {
        assertEquals(
            CotPortConflict.Cause.NOT_RELEASED_YET,
            conflict(accepts = false).diagnose("127.0.0.1", CotEndpointManager.PORT),
        )
    }

    @Test
    fun `the actionable case names ATAK and says which way round the contract goes`() {
        // An operator reading this on the settings screen has to be able to
        // fix it without being told anything else, so the message carries the
        // culprit, the direction of the contract, and the exact remedy.
        val text = CotPortConflict().explain(
            CotPortConflict.Cause.ANOTHER_SERVER,
            "127.0.0.1",
            CotEndpointManager.PORT,
        )
        assertTrue(text, text.contains("ATAK"))
        assertTrue(text, text.contains("outgoing"))
        assertTrue(text, text.contains("not clear it"))
        assertTrue(text, text.contains("127.0.0.1:${CotEndpointManager.PORT}"))
    }

    @Test
    fun `the transient case does not send anyone to change ATAK settings`() {
        // Waiting really is the fix here, and sending an operator to delete an
        // ATAK input that is not the problem costs them the connection that
        // was about to come back on its own.
        val text = CotPortConflict().explain(
            CotPortConflict.Cause.NOT_RELEASED_YET,
            "127.0.0.1",
            CotEndpointManager.PORT,
        )
        assertTrue(text, !text.contains("ATAK"))
        assertTrue(text, text.contains("Retrying"))
    }
}
