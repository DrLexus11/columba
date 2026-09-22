package network.columba.app.service.tak

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import org.json.JSONObject

/**
 * Chat that survives a partition, by putting it on LXMF.
 *
 * The Kotlin half of `tools/tak_lxmf.py`. PR B made chat cheap and, in the same
 * move, quietly put it on tier-1 transport: one bare packet per member, no
 * proof, no retry, no queue. That is right for a position report, where a
 * fresher one is seconds away, and wrong for a chat line, where there is no
 * fresher one.
 *
 * ATAK cannot cover for this. Its delivery receipt is a *report* that something
 * landed -- not a retransmission mechanism -- and ATAK never resends. A line
 * lost on LoRa was lost, and the sender saw a message with no tick and no
 * explanation.
 *
 * ## What travels this way, and what does not
 *
 *     addressed (a direct message)   LXMF      receipts, retry, store-and-forward
 *     team broadcast (a room line)   Packet    best effort, and that is all
 *
 * A property of broadcast rather than a shortcut: a room has no member list to
 * retry against and no receipt to wait for, so reliable multicast over a
 * partitionable mesh means either an acknowledgement from every member or blind
 * repetition, and both spend airtime on the assumption somebody missed it. A
 * direct message has exactly one recipient, so all three guarantees are cheap.
 *
 * ## Why a direct message is also a real Columba message
 *
 * The frame rides [FIELD_CUSTOM_DATA], tagged by [FIELD_CUSTOM_TYPE] --
 * upstream LXMF's own pair for an application's payload, which any other client
 * skips. The **text also travels as ordinary LXMF content**, so the same line
 * lands in the recipient's Columba conversation and in their ATAK, and an
 * operator who missed it in one can answer from the other.
 *
 * A room line carries no content at all, for the reverse reason: chatter from a
 * ten-person room has no business burying somebody's personal conversations.
 *
 * The receiving endpoint always reads the frame from the field and never from
 * the content. Content is for humans; the field is the protocol. Keeping that
 * absolute is what stops a rendered message being parsed back as a new one.
 */
object TakLxmf {
    /**
     * Upstream LXMF's own pair for an application's payload: CUSTOM_TYPE names
     * whose data it is, CUSTOM_DATA carries it.
     *
     * Both are flat, which matters more than it looks. The nested alternative
     * -- a dict under CUSTOM_META (0xFD) -- collides with the telemetry extras
     * Columba already keeps there, and a nested structure has to be pre-shaped
     * with backend-private helpers this module cannot reach. CUSTOM_DATA is
     * also the semantically right field: this is an app's data, not metadata
     * about somebody's message.
     */
    const val FIELD_CUSTOM_TYPE = 0xFB
    const val FIELD_CUSTOM_DATA = 0xFC

    /**
     * What CUSTOM_TYPE says, so a client that does not know us skips the
     * payload rather than guessing at it. Versioned, so a future frame layout
     * takes a new tag instead of pretending to be this one.
     */
    const val TAK_CUSTOM_TYPE = "tak.chat.v1"

    /** LXMF's delivery aspects, so a peer's ordinary messaging app answers. */
    const val LXMF_APP_NAME = "lxmf"
    val LXMF_DELIVERY_ASPECTS = listOf("delivery")

    /**
     * The fields a chat frame travels in, ready for `sendLxmfMessageWithMethod`.
     *
     * Flat values only: Columba already pushes raw bytes through this path for
     * voice, so a `ByteArray` is known to cross both backends unchanged.
     */
    fun extraFields(frame: ByteArray): Map<Int, Any> =
        mapOf(FIELD_CUSTOM_TYPE to TAK_CUSTOM_TYPE, FIELD_CUSTOM_DATA to frame)

    /**
     * The TAK frame carried by an inbound LXMF message, or null if it carries
     * none.
     *
     * Null is ordinary: this router is shared with the operator's own
     * messaging, so most of what arrives is a real conversation. Claiming one
     * would be worse than missing ours.
     *
     * Fields reach Kotlin as JSON, where the backend hex-encodes every byte
     * string and renders each field id as its decimal string -- so 0xFC arrives
     * as `"252"`. Both of those are the backend's convention rather than
     * LXMF's, and reading them wrongly would silently drop every message.
     */
    fun frameFrom(fieldsJson: String?): ByteArray? {
        val fields =
            fieldsJson
                ?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?: return null
        if (stringField(fields, FIELD_CUSTOM_TYPE) != TAK_CUSTOM_TYPE) return null
        return stringField(fields, FIELD_CUSTOM_DATA)?.let(::decodeHex)
    }

    /**
     * One arriving frame, with the identity LXMF proved for it.
     *
     * Kept together deliberately: the frame's own sender id is a claim, the
     * source hash is evidence, and separating them is what allowed the claim to
     * be believed on its own.
     */
    data class Inbound(val sourceHash: ByteArray, val frame: ByteArray) {
        override fun equals(other: Any?): Boolean =
            this === other ||
                (
                    other is Inbound &&
                        frame.contentEquals(other.frame) &&
                        sourceHash.contentEquals(other.sourceHash)
                )

        override fun hashCode(): Int = frame.contentHashCode() * 31 + sourceHash.contentHashCode()
    }

    /**
     * Whether the frame's claimed sender is the node that actually sent it.
     *
     * The wire format has four bytes for identity, so what arrives is a lookup
     * key rather than a name. That is fine when the carrier authenticates the
     * sender and we check the two agree -- and worthless without the check,
     * which is the whole of this function.
     */
    fun senderIsAuthentic(signedBy: ByteArray?, claimedSenderId: Int): Boolean =
        signedBy != null && signedBy.size >= 4 &&
            TakMembership.senderIdFor(signedBy) == claimedSenderId

    /**
     * The TAK node destination an LXMF source hash belongs to, or null.
     *
     * An inbox and a TAK node are two destinations built from the same
     * identity, so their hashes have nothing in common -- measured on the
     * bench: one identity gave node `c4be0a5b...` and inbox `42f8f27a...`.
     * Comparing an inbox hash against a sender id taken from a node hash
     * therefore never matches, for any node, and every LXMF chat line was
     * being dropped as a forgery. `tools/cot_bridge.py` does the recall; this
     * is the half that was missing.
     */
    suspend fun memberForLxmf(rnsCore: RnsCore, sourceHash: ByteArray?): ByteArray? {
        if (sourceHash == null || sourceHash.isEmpty()) return null
        val identity = rnsCore.recallIdentity(sourceHash) ?: return null
        return rnsCore.createDestination(
            identity,
            Direction.OUT,
            DestinationType.SINGLE,
            TakIdentity.NODE_APP,
            TakIdentity.NODE_ASPECTS,
        ).getOrNull()?.hash
    }

    /**
     * What this endpoint sends and receives chat through.
     *
     * The Kotlin twin of `tools/tak_lxmf.py`'s Carrier, and it exists for the
     * same reason: carrying is the carrier's job. Left in the endpoint it was
     * two more methods on a class that already does discovery, framing,
     * rendering and four codecs.
     */
    class Carrier(
        private val rnsCore: RnsCore,
        private val rnsLxmf: RnsLxmf,
        private val identity: Identity,
    ) {
        /**
         * Send one chat frame to one peer. True if LXMF took it.
         *
         * Took it, not delivered it: LXMF owns the outcome from here and
         * reports it later. That is the whole point -- the caller is not
         * blocked on a radio, and a peer who is out of range gets the line
         * when they return rather than never.
         */
        /**
         * @return the hash the backend gave this message, or null if LXMF
         *   would not take it. The hash is what a later delivery proof refers
         *   to, which is how a sender draws its own tick instead of waiting
         *   for the far ATAK to send one back across the mesh.
         */
        suspend fun send(
            memberHash: ByteArray,
            frame: ByteArray,
            text: String,
            propagate: Boolean = true,
        ): String? {
            val inbox = inboxFor(memberHash) ?: return null
            return rnsLxmf.sendLxmfMessageWithMethod(
                destinationHash = inbox,
                // Content is for the human; the field is the protocol. The
                // same line therefore lands in the recipient's Columba
                // conversation as well as their ATAK.
                content = text,
                sourceIdentity = identity,
                // Encrypted LXMF packets retain proofs, retries and the
                // propagation fallback without a handshake for each cold
                // conversation. LXMF promotes oversized payloads to DIRECT.
                deliveryMethod = DeliveryMethod.OPPORTUNISTIC,
                // A file request is not left at a propagation node: answered
                // later, it would bring the file over whatever path exists
                // then, which may be the LoRa leg the fetch gate avoided.
                tryPropagationOnFail = propagate,
                extraFields = extraFields(frame),
            ).getOrNull()?.messageHash?.toHexString()
        }

        /**
         * A member's LXMF inbox, derived from the identity we already hold.
         *
         * Nothing extra is announced for this: the TAK node destination and
         * the LXMF inbox are built from the same identity, so knowing a peer
         * well enough to address their node is knowing them well enough to
         * address their inbox. Pivot 1 paying for itself again.
         */
        suspend fun inboxFor(memberHash: ByteArray): ByteArray? {
            val peerIdentity = rnsCore.recallIdentity(memberHash) ?: return null
            return rnsCore.createDestination(
                peerIdentity,
                Direction.OUT,
                DestinationType.SINGLE,
                LXMF_APP_NAME,
                LXMF_DELIVERY_ASPECTS,
            ).getOrNull()?.hash
        }

        private fun ByteArray.toHexString(): String =
            joinToString("") { "%02x".format(it) }

        /**
         * TAK chat frames arriving over LXMF, each with who actually sent it.
         *
         * Everything else on this router is the operator's own messaging and
         * passes straight through -- claiming one would be worse than missing
         * ours.
         *
         * The envelope's source hash travels with the frame and is the only
         * identity here worth anything. LXMF authenticated it; the sender id
         * inside the frame is four bytes the sender chose for itself. Emitting
         * the frame alone let the renderer resolve that self-declared id
         * against the team registry, so any LXMF sender at all -- no team
         * secret, no membership -- could put words on an operator's screen
         * under a member's name simply by knowing four bytes of that member's
         * destination hash, which is public in every announce.
         */
        fun frames(): Flow<Inbound> =
            rnsLxmf.observeMessages().mapNotNull { message ->
                frameFrom(message.fieldsJson)?.let { frame ->
                    Inbound(sourceHash = message.sourceHash, frame = frame)
                }
            }
    }

    private fun stringField(fields: JSONObject, id: Int): String? {
        // Decimal, because the backend writes str(key) over an int key. Hex
        // would be the natural guess and is wrong.
        val value = fields.opt(id.toString()) ?: return null
        return value as? String
    }

    /**
     * Bytes from the backend's hex encoding, or null if it is not hex.
     *
     * Strict about length and alphabet: a malformed payload is a frame we
     * cannot trust, and half-decoding one would hand the codec bytes that mean
     * something else.
     */
    private fun decodeHex(hex: String): ByteArray? {
        if (hex.isEmpty() || hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (index in out.indices) {
            val high = Character.digit(hex[index * 2], 16)
            val low = Character.digit(hex[index * 2 + 1], 16)
            if (high < 0 || low < 0) return null
            out[index] = ((high shl 4) or low).toByte()
        }
        return out
    }
}
