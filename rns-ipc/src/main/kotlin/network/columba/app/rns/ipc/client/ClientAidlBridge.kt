package network.columba.app.rns.ipc.client

import android.os.Bundle
import android.os.DeadObjectException
import android.os.RemoteException
import android.util.Log
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.suspendCancellableCoroutine
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.ipc.callback.IRnsBoolCallback
import network.columba.app.rns.ipc.callback.IRnsByteArrayCallback
import network.columba.app.rns.ipc.callback.IRnsFloatCallback
import network.columba.app.rns.ipc.callback.IRnsIntCallback
import network.columba.app.rns.ipc.callback.IRnsResultCallback
import network.columba.app.rns.ipc.callback.IRnsStringCallback
import network.columba.app.rns.ipc.callback.IRnsStringListCallback
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Helpers that wrap `oneway` AIDL call+callback pairs in
 * [suspendCancellableCoroutine] so suspend functions on the Kotlin side of
 * the AIDL boundary keep their natural shape.
 *
 * Cancellation semantics: AIDL `oneway` calls don't carry a cancellation
 * signal across the wire, so cancellation here means "drop the result when
 * it arrives." A [ContinuationSlot] guards each callback: the first answer
 * takes the continuation, and a late one, or one after the caller cancelled,
 * finds the slot empty and is discarded.
 *
 * Binder exceptions ([DeadObjectException] from a crashed remote, plus the
 * broader [RemoteException]) are translated to
 * [RnsException]([RnsError.BackendNotReady]) so callers see the same
 * `Result.failure(RnsException(...))` shape regardless of whether the
 * failure originated in the backend or in the IPC layer itself.
 */
internal suspend inline fun awaitResult(
    crossinline call: (IRnsResultCallback) -> Unit,
): Bundle = suspendCancellableCoroutine { cont ->
    val slot = ContinuationSlot(cont)
    val cb = object : IRnsResultCallback.Stub() {
        override fun onSuccess(resultPayload: Bundle?) {
            slot.take()?.resume(resultPayload ?: Bundle.EMPTY)
        }

        override fun onError(error: RnsError?) {
            slot.take()?.resumeWithException(RnsException(error ?: genericIpcFailure("onError with null error")))
        }
    }
    try {
        call(cb)
    } catch (e: RemoteException) {
        slot.take()?.resumeWithException(remoteToRnsException(e))
    }
    // A oneway AIDL call cannot be recalled: on cancellation the slot is
    // emptied, and the late server response finds nothing to resume.
}

internal suspend inline fun awaitBool(
    crossinline call: (IRnsBoolCallback) -> Unit,
): Boolean = suspendCancellableCoroutine { cont ->
    val slot = ContinuationSlot(cont)
    val cb = object : IRnsBoolCallback.Stub() {
        override fun onSuccess(value: Boolean) {
            slot.take()?.resume(value)
        }

        override fun onError(error: RnsError?) {
            slot.take()?.resumeWithException(RnsException(error ?: genericIpcFailure("onError with null error")))
        }
    }
    try {
        call(cb)
    } catch (e: RemoteException) {
        slot.take()?.resumeWithException(remoteToRnsException(e))
    }
}

/**
 * Wraps an [IRnsIntCallback] producing `(value, hasValue)` and returns an
 * `Int?` — useful for getters like `getHopCount` and `getRNodeRssi`.
 */
internal suspend inline fun awaitNullableInt(
    crossinline call: (IRnsIntCallback) -> Unit,
): Int? = suspendCancellableCoroutine { cont ->
    val slot = ContinuationSlot(cont)
    val cb = object : IRnsIntCallback.Stub() {
        override fun onSuccess(value: Int, hasValue: Boolean) {
            slot.take()?.resume(if (hasValue) value else null)
        }

        override fun onError(error: RnsError?) {
            slot.take()?.resumeWithException(RnsException(error ?: genericIpcFailure("onError with null error")))
        }
    }
    try {
        call(cb)
    } catch (e: RemoteException) {
        slot.take()?.resumeWithException(remoteToRnsException(e))
    }
}

internal suspend inline fun awaitNullableString(
    crossinline call: (IRnsStringCallback) -> Unit,
): String? = suspendCancellableCoroutine { cont ->
    val slot = ContinuationSlot(cont)
    val cb = object : IRnsStringCallback.Stub() {
        override fun onSuccess(value: String?) {
            slot.take()?.resume(value)
        }

        override fun onError(error: RnsError?) {
            slot.take()?.resumeWithException(RnsException(error ?: genericIpcFailure("onError with null error")))
        }
    }
    try {
        call(cb)
    } catch (e: RemoteException) {
        slot.take()?.resumeWithException(remoteToRnsException(e))
    }
}

internal suspend inline fun awaitFloat(
    crossinline call: (IRnsFloatCallback) -> Unit,
): Float = suspendCancellableCoroutine { cont ->
    val slot = ContinuationSlot(cont)
    val cb = object : IRnsFloatCallback.Stub() {
        override fun onSuccess(value: Float) {
            slot.take()?.resume(value)
        }

        override fun onError(error: RnsError?) {
            slot.take()?.resumeWithException(RnsException(error ?: genericIpcFailure("onError with null error")))
        }
    }
    try {
        call(cb)
    } catch (e: RemoteException) {
        slot.take()?.resumeWithException(remoteToRnsException(e))
    }
}

internal suspend inline fun awaitStringList(
    crossinline call: (IRnsStringListCallback) -> Unit,
): List<String> = suspendCancellableCoroutine { cont ->
    val slot = ContinuationSlot(cont)
    val cb = object : IRnsStringListCallback.Stub() {
        override fun onSuccess(values: MutableList<String>?) {
            slot.take()?.resume(values?.toList() ?: emptyList())
        }

        override fun onError(error: RnsError?) {
            slot.take()?.resumeWithException(RnsException(error ?: genericIpcFailure("onError with null error")))
        }
    }
    try {
        call(cb)
    } catch (e: RemoteException) {
        slot.take()?.resumeWithException(remoteToRnsException(e))
    }
}

internal suspend inline fun awaitNullableByteArray(
    crossinline call: (IRnsByteArrayCallback) -> Unit,
): ByteArray? = suspendCancellableCoroutine { cont ->
    val slot = ContinuationSlot(cont)
    val cb = object : IRnsByteArrayCallback.Stub() {
        override fun onSuccess(data: ByteArray?) {
            slot.take()?.resume(data)
        }

        override fun onError(error: RnsError?) {
            slot.take()?.resumeWithException(RnsException(error ?: genericIpcFailure("onError with null error")))
        }
    }
    try {
        call(cb)
    } catch (e: RemoteException) {
        slot.take()?.resumeWithException(remoteToRnsException(e))
    }
}

/**
 * Convert an `IRnsResultCallback` Bundle response into a [Result] for
 * `suspend fun foo(): Result<T>` shapes. The success path runs [extract] to
 * pull the typed value out of the payload Bundle; an extraction failure is
 * surfaced as `Result.failure(RnsException(Generic))` so the caller still
 * sees a typed error rather than a thrown ClassCastException.
 */
internal inline fun <T> Bundle.unwrap(extract: Bundle.() -> T): Result<T> =
    runCatching { extract() }.recoverCatching {
        throw RnsException(RnsError.Generic("Failed to extract result payload: ${it.message}", it.stackTraceToString()))
    }

private const val OBSERVER_TAG = "ClientRnsObserver"

/**
 * Register an AIDL observer from inside a [kotlinx.coroutines.flow.callbackFlow]
 * producer, guarding the cross-process call against a dead `:reticulum` backend.
 *
 * If the backend process is dead at registration time the call throws
 * [RemoteException] (DeadObjectException). Unguarded, that escapes the producer
 * and crashes the collecting coroutine — Sentry COLUMBA-AZ (networkStatus),
 * COLUMBA-B0 (telemetry), and every other observer-backed flow share the
 * hazard. On failure the producer is closed gracefully (no exception
 * propagated), so the bound-service layer's `flatMapLatest` re-subscribes once
 * the backend rebinds.
 *
 * Closing **without** the cause is deliberate — do NOT change this to
 * `close(e)`. Most of these observers are collected via `.launchIn(scope)`
 * with no `.catch`, so `close(e)` would re-throw the [RemoteException] into
 * that collector and reintroduce the exact uncaught-coroutine crash this
 * guards against. The dead-backend signal is intentionally dropped here; the
 * rebind path is what restores the stream.
 *
 * @return true if registration succeeded; false if the backend was dead — in
 *   which case the flow is already closed and the caller should
 *   `return@callbackFlow` to skip the awaitClose unregister.
 */
internal inline fun ProducerScope<*>.registerObserverOrClose(register: () -> Unit): Boolean =
    try {
        register()
        true
    } catch (e: RemoteException) {
        Log.w(OBSERVER_TAG, "observer registration failed; :reticulum backend dead", e)
        close()
        false
    }

internal fun remoteToRnsException(e: RemoteException): RnsException =
    when (e) {
        is DeadObjectException -> RnsException(RnsError.BackendNotReady)
        else -> RnsException(RnsError.Generic(e.message ?: "Remote call failed", e.stackTraceToString()))
    }

internal fun genericIpcFailure(reason: String): RnsError =
    RnsError.Generic("IPC layer error: $reason", null)
