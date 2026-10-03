package network.columba.app.rns.ipc.client

import kotlinx.coroutines.CancellableContinuation
import java.util.concurrent.atomic.AtomicReference

/**
 * The caller's continuation, held by an AIDL callback only until it is used.
 *
 * The callback is a binder object, and the remote process keeps it reachable
 * until its own garbage collection runs. Holding the continuation for that
 * long kept the caller's whole coroutine -- and whatever launched it -- in
 * memory: LeakCanary caught a destroyed MeshService held by a getHopCount
 * callback (2026-10-03). The slot is emptied by the first answer, and on
 * cancellation, so the callback outlives nothing of the caller's.
 */
internal class ContinuationSlot<T>(
    continuation: CancellableContinuation<T>,
) {
    private val ref = AtomicReference<CancellableContinuation<T>?>(continuation)

    init {
        continuation.invokeOnCancellation { ref.set(null) }
    }

    /** The continuation, to the first caller only. */
    fun take(): CancellableContinuation<T>? = ref.getAndSet(null)
}
