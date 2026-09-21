package network.columba.app.service.tak

import android.util.Log
import network.columba.app.service.PositionCodec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Turning what the team sent into CoT the local ATAK will draw.
 *
 * Split out of `CotEndpointManager`, which owns sockets and the endpoint's
 * lifecycle and had grown to hold this as well. The seam is a real one: nothing
 * here touches a socket or the mesh, so the whole mesh-to-ATAK decision is a
 * pure function of a frame, the clock and who is on the team -- which is also
 * what makes it testable without a listener and two connected clients.
 *
 * Every render answers the same three-way question, because the manager has to
 * distinguish "here is XML to write" from "this was ours and there is nothing
 * to draw" from "not ours, try tier 2". Collapsing the middle case into the
 * last one would put a frame we deliberately declined through the tier-2
 * fallback and onto the map anyway.
 */
class CotRenderer(
    private val registry: TakMembership.Registry,
    private val team: String,
    private val nodeHash: ByteArray,
    private val positionStaleMs: Long,
    private val chatStaleMs: Long,
) {
    /** What a frame turned out to be. */
    sealed interface Rendered {
        /** CoT to write to every connected client. */
        data class Cot(val xml: String) : Rendered

        /** Ours, and deliberately not drawn. Must not fall through to tier 2. */
        data object Handled : Rendered

        /** Not one of our codecs. The caller tries tier 2. */
        data object NotOurs : Rendered
    }

    companion object {
        private const val TAG = "CotRenderer"

        /** Shown when a member's announce has not told us a callsign. */
        private const val UNKNOWN_CALLSIGN = "UNKNOWN"
    }

    /**
     * Render one frame from the mesh.
     *
     * Byte zero says which codec produced it. One namespace shared by all of
     * them rather than three independent version counters -- see [TakPayload]
     * for why that distinction matters. A new codec is a branch here and a
     * function below.
     */
    fun render(raw: ByteArray, now: Long): Rendered =
        when (TakPayload.kindOf(raw)) {
            TakPayload.POSITION_V2 -> position(raw, now)
            TakPayload.CHAT_V1 -> chat(raw, now)
            TakPayload.MARKER_V1 -> marker(raw, now)
            else -> Rendered.NotOurs
        }

    /**
     * Render a frame that arrived over LXMF, where the carrier proved who sent
     * it.
     *
     * The sender id inside a frame is four bytes the sender chose for itself;
     * `signedBy` is the TAK node destination the carrier's authenticated
     * source hash resolves to. It has to be resolved rather than compared
     * directly: an inbox and a node are different destinations built from the
     * same identity, so their hashes share nothing. Rendering on the
     * claim alone let any LXMF sender at all -- no fleet secret, no membership
     * -- put words on an operator's screen under a member's name, needing only
     * four bytes of that member's destination hash, which every announce
     * publishes.
     *
     * The packet path has no envelope to check and authenticates differently:
     * it arrives on this node's own destination. Hence two entry points rather
     * than one that sometimes checks.
     */
    fun render(inbound: TakLxmf.Inbound, now: Long, signedBy: ByteArray?): Rendered {
        // A chat line, or a marker sent to this node in particular. Either way
        // the frame's claimed sender must agree with the one LXMF proved, or
        // any member could put words, or a pin, under another member's name.
        val claimed = CotChat.decode(inbound.frame)?.senderId ?: CotMarker.decode(inbound.frame)?.senderId
        if (claimed == null || !TakLxmf.senderIsAuthentic(signedBy, claimed)) {
            Log.w(TAG, "LXMF frame does not match its sender, not shown")
            return Rendered.Handled
        }
        return render(inbound.frame, now)
    }

    /** A peer's position report. */
    private fun position(raw: ByteArray, now: Long): Rendered {
        val fix = PositionCodec.decode(raw) ?: return Rendered.NotOurs
        // Four bytes of identity is a lookup key here, not an identity.
        // Membership is the table that turns it back into a whole destination
        // hash, so a peer's track carries the same UID as everything else that
        // node sends rather than a track of its own.
        val sender = registry.resolveSenderId(fix.senderId, now) ?: return Rendered.Handled
        return Rendered.Cot(
            CotPosition.buildCot(
                fix,
                TakIdentity.uidFor(sender),
                callsignOf(sender),
                positionStaleMs,
                team = team,
            ),
        )
    }

    /** A peer's marker. */
    private fun marker(raw: ByteArray, now: Long): Rendered {
        val marker = CotMarker.decode(raw) ?: return Rendered.NotOurs
        // A marker from a node this team has never heard announce. Drawing it
        // under an invented identity puts an object on the map nobody can be
        // asked about.
        val sender = registry.resolveSenderId(marker.senderId, now) ?: return Rendered.Handled
        return Rendered.Cot(
            CotMarker.buildMarkerCot(
                marker,
                TakIdentity.uidFor(sender),
                callsignOf(sender),
                cotTime(now),
                // The author's own stale, not one invented here: a spot marker
                // is good for a year and an SPI for twenty seconds.
                cotTime(now + marker.staleSeconds * 1000L),
            ),
        )
    }

    /** A peer's chat line or receipt. */
    private fun chat(raw: ByteArray, now: Long): Rendered {
        val message = CotChat.decode(raw) ?: return Rendered.NotOurs
        // Two reasons to draw nothing, taken together because the outcome is
        // the same: ours, shown to nobody.
        //
        // Addressed to somebody else -- the packet arriving on our destination
        // was previously the only check, and that is a claim about routing
        // rather than about intent, so a frame misrouted or sent here
        // deliberately by another member went straight to the ATAK socket and
        // put private words on an operator's screen.
        //
        // Or from a node this team has never heard announce, where putting
        // words on the screen under an identity we cannot name is worse than
        // not showing them.
        val forUs = message.isAddressedTo(TakIdentity.uidFor(nodeHash))
        val sender = registry.resolveSenderId(message.senderId, now)
        if (!forUs || sender == null) {
            if (!forUs) Log.i(TAG, "Direct chat addressed to another member, not shown")
            return Rendered.Handled
        }
        return Rendered.Cot(
            CotChat.buildChatCot(
                message,
                TakIdentity.uidFor(sender),
                callsignOf(sender),
                cotTime(now),
                cotTime(now + chatStaleMs),
            ),
        )
    }

    /**
     * The delivery receipt a peer would have sent, rendered from LXMF's proof.
     *
     * The tick used to come from the far ATAK, as a receipt that crossed the
     * mesh as a second LXMF message with its own retry budget. LXMF has
     * already proved the peer's node holds the message, so that pays twice for
     * one answer -- and on a lossy path the receipt is the half that is lost,
     * leaving no tick on a line that did arrive.
     *
     * Built as though the peer had sent it, because that is exactly the event
     * this replaces: attributing it to ourselves would file the tick in a
     * conversation with ourselves, which is the bug that broke replies in
     * September.
     */
    fun deliveryReceipt(peer: ByteArray, messageId: String, room: String,
                        ourUid: String, now: Long): String =
        CotChat.buildChatCot(
            CotChat.Message(
                kind = CotChat.KIND_DELIVERED,
                senderId = TakMembership.senderIdFor(peer),
                messageId = messageId,
                room = room,
                text = "",
                recipient = ourUid,
            ),
            TakIdentity.uidFor(peer),
            callsignOf(peer),
            cotTime(now),
            cotTime(now + chatStaleMs),
        )

    /** As claimed in the member's announce. For display, never for trust. */
    private fun callsignOf(sender: ByteArray): String =
        registry.describe(sender)?.callsign ?: UNKNOWN_CALLSIGN

    /** CoT wants ISO 8601 in UTC with a Z, to millisecond precision. */
    private fun cotTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))
}
