package network.columba.app.service.tak

import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.DeliveryStatus

/**
 * Turns LXMF delivery proofs into the delivery ticks ATAK draws.
 *
 * The tick used to come from the far end: its ATAK wrote a delivered-receipt,
 * which crossed the mesh as a second LXMF message with its own retry budget.
 * LXMF has already proved the peer's node holds the message, so that pays
 * twice for one answer -- and on a lossy path the receipt is the half that is
 * lost, leaving no tick on a line that did arrive.
 *
 * Measured on the LoRa path 2026-09-14: receipts were 11 of 24 outbound
 * messages, and every message averaged 1.9 packet attempts.
 */
class DeliveryTicks(private val rnsLxmf: RnsLxmf) {
    /**
     * Watch for proofs and emit a receipt for each one we were waiting on.
     *
     * **PROPAGATED is deliberately not a tick.** A message sitting in the
     * command post's store has reached nobody, and saying otherwise would tell
     * an operator their line landed while it is still waiting for the
     * recipient to come back.
     */
    suspend fun collect(
        proofs: ChatProofs,
        renderer: CotRenderer,
        ourUid: String,
        emit: suspend (ByteArray) -> Unit,
    ) {
        rnsLxmf.observeDeliveryStatus().collect { update ->
            if (update.status != DeliveryStatus.DELIVERED) return@collect
            val pending = proofs.claim(update.messageHash) ?: return@collect
            emit(
                renderer.deliveryReceipt(
                    pending.peer, pending.messageId, pending.room,
                    ourUid, System.currentTimeMillis(),
                ).toByteArray(Charsets.UTF_8),
            )
        }
    }
}
