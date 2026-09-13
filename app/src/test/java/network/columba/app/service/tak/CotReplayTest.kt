package network.columba.app.service.tak

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the endpoint holds for an ATAK that is not attached yet.
 *
 * Mirrors `tests/test_cot_replay.py`. Seen twice on hardware 2026-09-12: a
 * reply crossed two LoRa hops, was decoded and rebuilt correctly, and was
 * dropped at the socket because nothing was listening.
 */
class CotReplayTest {
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8)

    @Test
    fun `an event is held for whoever attaches next`() = runTest {
        val replay = CotReplay()
        replay.hold(bytes("one"), now = 1_000)
        assertEquals(1, replay.size())
        assertEquals(listOf("one"), replay.pending(1_000).map { it.toString(Charsets.UTF_8) })
    }

    @Test
    fun `oldest first, so a conversation reads in the order it happened`() = runTest {
        val replay = CotReplay()
        replay.hold(bytes("one"), now = 1_000)
        replay.hold(bytes("two"), now = 2_000)
        replay.hold(bytes("three"), now = 3_000)
        assertEquals(
            listOf("one", "two", "three"),
            replay.pending(3_000).map { it.toString(Charsets.UTF_8) },
        )
    }

    @Test
    fun `stale events are not replayed`() {
        // A marker from an hour ago is not news, and a chat line from before
        // the operator walked away is context they have already lost.
        runTest {
            val replay = CotReplay()
            replay.hold(bytes("old"), now = 0)
            replay.hold(bytes("new"), now = CotReplay.MAX_AGE_MS)
            val pending = replay.pending(CotReplay.MAX_AGE_MS + 1)
            assertEquals(listOf("new"), pending.map { it.toString(Charsets.UTF_8) })
        }
    }

    @Test
    fun `the buffer is bounded`() = runTest {
        // A bridge nobody ever attaches to must not grow without limit.
        val replay = CotReplay()
        for (index in 0 until CotReplay.MAX_EVENTS * 3) {
            replay.hold(bytes("event $index"), now = 1_000L + index)
        }
        assertTrue(replay.size() <= CotReplay.MAX_EVENTS)
    }

    @Test
    fun `the newest survive when the buffer overflows`() = runTest {
        // Dropping the oldest is the right end to lose: the newest events are
        // the ones an operator has not seen.
        val replay = CotReplay(maxEvents = 3)
        for (index in 0 until 5) {
            replay.hold(bytes("event $index"), now = 1_000L + index)
        }
        assertEquals(
            listOf("event 2", "event 3", "event 4"),
            replay.pending(1_010).map { it.toString(Charsets.UTF_8) },
        )
    }

    @Test
    fun `nothing held means nothing to replay`() = runTest {
        assertEquals(emptyList<ByteArray>(), CotReplay().pending(1_000))
    }

    @Test
    fun `the window matches the python side`() {
        // Both endpoints hold the same amount for the same reasons; a client
        // that reconnects should not get a different answer depending on which
        // one it is attached to.
        assertEquals(64, CotReplay.MAX_EVENTS)
        assertEquals(15L * 60 * 1000, CotReplay.MAX_AGE_MS)
    }
}
