package network.columba.app.service.tak

import android.util.Log
import network.columba.app.rns.api.RnsCore

/**
 * How a TAK frame travels over LXMF, and what happens when one arrives.
 *
 * The Kotlin half of `_fan_out_reliably` and the LXMF receive path in
 * `tools/cot_bridge.py`.
 *
 * **A fragment is a whole LXMF message.** That was always the design -- it is
 * why a `Resource` was rejected as all-or-nothing -- and for a while it was
 * not what either half did. Fragments went out through the marker fan-out as
 * bare packets, which have no proof, no retry and no propagation node.
 * Measured on hardware 2026-09-21: a two-fragment drawing lost its second
 * fragment, nothing retried it, and the far end held half a drawing until the
 * reassembly window expired.
 *
 * A single-packet event still takes the cheap fan-out. It has nothing to be
 * incomplete against, and the airtime costing assumed that.
 */
class TakLxmfCarriage(private val rnsCore: RnsCore) {
    companion object {
        private const val TAG = "TakLxmfCarriage"
    }

    /**
     * Send every fragment of one event to every member, over LXMF.
     *
     * A peer the carrier will not take is logged, not quietly downgraded to a
     * packet: sending unreliably while the caller believes otherwise is worse
     * than not sending, which is the doctrine addressed chat already follows.
     */
    suspend fun send(
        frames: List<ByteArray>,
        registry: TakMembership.Registry,
        lxmf: TakLxmf.Carrier,
        now: Long,
    ) {
        val members = registry.members(now)
        for (frame in frames) {
            for (memberHash in members) {
                if (lxmf.send(memberHash, frame, "") == null) {
                    Log.w(TAG, "LXMF would not take a fragment for a member")
                }
            }
        }
    }

    /**
     * Deliver one frame that arrived over LXMF.
     *
     * Both kinds land here, because both now travel this way. A fragment is
     * reassembled first and drawn once it is whole; a chat line is checked
     * against the sender the carrier authenticated and drawn if it agrees.
     *
     * **Attribution comes from the carrier, not the payload**, and the carrier
     * names an *inbox*. An inbox and a TAK node are two destinations built
     * from the same identity and share no bytes, so the inbox has to be
     * resolved to the node that owns it before anything can be compared to the
     * sender id inside the frame. Comparing them directly meant the check
     * could never pass, and every chat line arriving over LXMF was dropped as
     * a forgery -- found on the bench 2026-09-21.
     *
     * A fragment is keyed on the member the carrier *proved* rather than on a
     * destination hash, which is strictly better than the packet path can
     * manage and costs nothing here.
     */
    suspend fun deliver(
        inbound: TakLxmf.Inbound,
        reassembler: CotReassembler,
        renderer: CotRenderer,
        deliver: suspend (ByteArray) -> Unit,
    ) {
        val signedBy = TakLxmf.memberForLxmf(rnsCore, inbound.sourceHash)
        if (TakPayload.kindOf(inbound.frame) == TakPayload.FRAGMENT_V1) {
            val whole =
                reassembler.feed(signedBy, inbound.frame, System.currentTimeMillis())
                    ?: return
            Log.i(TAG, "reassembled ${whole.size} bytes over LXMF")
            draw(whole, renderer, deliver)
            return
        }
        when (val rendered = renderer.render(inbound, System.currentTimeMillis(), signedBy)) {
            is CotRenderer.Rendered.Cot -> deliver(rendered.xml.toByteArray(Charsets.UTF_8))
            else -> Log.w(TAG, "LXMF frame not drawn: $rendered")
        }
    }

    /**
     * Draw one whole frame, whichever carrier brought it.
     *
     * A reassembled event is an ordinary event again: the same renderer and
     * the same tier-2 fallback as one that arrived in a single packet, which
     * is what keeps everything downstream unaware it was ever in pieces.
     */
    private suspend fun draw(
        data: ByteArray,
        renderer: CotRenderer,
        deliver: suspend (ByteArray) -> Unit,
    ) {
        val payload =
            when (val rendered = renderer.render(data, System.currentTimeMillis())) {
                is CotRenderer.Rendered.Cot -> rendered.xml
                CotRenderer.Rendered.Handled -> return
                CotRenderer.Rendered.NotOurs ->
                    try {
                        CotTier2.decode(data)
                    } catch (_: IllegalArgumentException) {
                        // A frame we cannot read is ordinary: an older node, a
                        // newer dictionary, or simply not ours.
                        return
                    }
            }
        deliver(payload.toByteArray(Charsets.UTF_8))
    }
}
