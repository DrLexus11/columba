package network.columba.app.rns.ipc.client

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.resume

/** An AIDL callback holds the caller's continuation only until it is used. */
class ContinuationSlotTest {
    @Test
    fun `the first answer takes the continuation, a late one finds nothing`() =
        runBlocking {
            val slotReady = CompletableDeferred<ContinuationSlot<Int>>()
            val result =
                async(start = CoroutineStart.UNDISPATCHED) {
                    suspendCancellableCoroutine<Int> { cont -> slotReady.complete(ContinuationSlot(cont)) }
                }
            val slot = slotReady.await()
            slot.take()?.resume(7)
            assertNull("a second answer must find the slot empty", slot.take())
            assertEquals(7, result.await())
        }

    @Test
    fun `cancelling the caller empties the slot`() =
        runBlocking {
            val slotReady = CompletableDeferred<ContinuationSlot<Int>>()
            val call =
                async(start = CoroutineStart.UNDISPATCHED) {
                    suspendCancellableCoroutine<Int> { cont -> slotReady.complete(ContinuationSlot(cont)) }
                }
            val slot = slotReady.await()
            call.cancel()
            yield()
            assertTrue(call.isCancelled)
            // What the remote process still holds no longer reaches the caller.
            assertNull(slot.take())
        }

    @Test
    fun `an unanswered call keeps its continuation`() =
        runBlocking {
            val slotReady = CompletableDeferred<ContinuationSlot<Int>>()
            val call =
                async(start = CoroutineStart.UNDISPATCHED) {
                    suspendCancellableCoroutine<Int> { cont -> slotReady.complete(ContinuationSlot(cont)) }
                }
            val slot = slotReady.await()
            val cont = slot.take()
            assertNotNull(cont)
            cont!!.resume(1)
            assertEquals(1, call.await())
        }
}
