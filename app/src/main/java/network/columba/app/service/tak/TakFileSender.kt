package network.columba.app.service.tak

import android.util.Log
import network.columba.app.rns.api.RnsCore
import java.util.concurrent.ConcurrentHashMap

/**
 * The sending half of TAK files: what this handset's ATAK offered, and the
 * parts teammates ask for.
 *
 * Split from [TakFileTransfers], which keeps the receiving half. A file is sent
 * only to a member its offer was addressed to -- the rule that keeps a pin sent
 * to one person off everyone else's map -- and always DIRECT, never left at a
 * propagation node, which would hand it on over whatever path the receiver has.
 */
class TakFileSender(
    private val store: TakFileStore,
    private val rnsCore: RnsCore,
    private val lxmf: TakLxmf.Carrier,
    private val team: TakFileTransfers.Team,
    private val clock: () -> Long,
    private val thumbnailer: (ByteArray, Int) -> ByteArray?,
    private val status: suspend (String) -> Unit,
) {
    companion object {
        private const val TAG = "TakFileSender"
    }

    /** A file this ATAK offered one member, until they fetch it. */
    private class Offer(val filename: String, val size: Long, val member: ByteArray, val since: Long) {
        @Volatile var told = false
    }

    private val offers = ConcurrentHashMap<String, Offer>()

    /**
     * This ATAK sent a file notice to [recipients] (null for the team). True
     * when an offer went in its place; false when the file was never uploaded
     * here, and ATAK's notice should go as it is.
     */
    suspend fun offered(cotXml: String, recipients: List<ByteArray>?): Boolean {
        val notice = TakFiles.parseNotice(cotXml) ?: return false
        if (!store.has(notice.hash)) {
            Log.w(TAG, "${notice.filename} offered but never uploaded here; nobody will be able to fetch it")
            return false
        }
        // Granted before the offer is published, not after. The sends below
        // suspend, and a recipient on a fast path can read the offer and ask
        // for the first part before they return -- which then found no grant,
        // was refused, and was not necessarily asked again, leaving a valid
        // transfer stuck. Nothing can ask for a file before it is offered, so
        // granting first opens no window that sending first had closed.
        store.grant(notice.hash, recipients)
        recipients?.forEach { offers[notice.hash + it.toHex()] = Offer(notice.filename, notice.size, it, clock()) }
        sendOffer(notice, recipients)
        Log.i(TAG, "${notice.filename} offered to ${recipients?.let { "${it.size} member(s)" } ?: "the team"}")
        return true
    }

    /**
     * The offer on the air in place of ATAK's notice: about 55 bytes for a
     * data package, up to three fragments for a QuickPic with its position and
     * a thumbnail. Always over LXMF -- the receiver must know whom to ask.
     */
    private suspend fun sendOffer(notice: TakFiles.Notice, recipients: List<ByteArray>?) {
        val data = store.read(notice.hash) ?: return
        val offer = TakFileOffer.encode(TakFileOffer.offerFor(team.ourSenderId, notice.hash, notice.filename, data, thumbnailer))
        val frames = if (offer.size <= CotFragment.MAX_FRAGMENT_FRAME_BYTES) listOf(offer) else CotFragment.fragments(offer)
        for (member in recipients ?: team.members()) {
            for (frame in frames) {
                if (lxmf.send(member, frame, "") == null) Log.w(TAG, "LXMF would not take an offer for a member")
            }
        }
        Log.i(TAG, "Offer for ${notice.filename}: ${offer.size} bytes in ${frames.size} frame(s)")
    }

    /** A teammate asked for a whole file (an older receiver): sent if offered it. */
    suspend fun onRequest(inbound: TakLxmf.Inbound) {
        val hash = TakFiles.decodeRequest(inbound.frame) ?: return
        val member = allowed(hash, inbound.sourceHash) ?: return
        val data = store.read(hash) ?: return
        val name = store.nameOf(hash)
        val sent = lxmf.sendFile(member, TakFiles.encodeFile(hash, name, data))
        Log.i(TAG, "Sending $name (${data.size} bytes)" + if (sent == null) ", but LXMF refused" else "")
    }

    /** A teammate asked for part of a file: sent if they were offered it. */
    suspend fun onPartRequest(inbound: TakLxmf.Inbound) {
        val request = TakFileParts.decodeRequest(inbound.frame) ?: return
        val member = allowed(request.hash, inbound.sourceHash) ?: return
        val data = store.read(request.hash)?.takeIf { request.offset <= it.size } ?: return
        val end = minOf(data.size.toLong(), request.offset + minOf(request.length, TakFileParts.PART_BYTES))
        val part = data.copyOfRange(request.offset.toInt(), end.toInt())
        val sent = lxmf.sendFile(member, TakFileParts.encodePart(request.hash, request.offset, data.size.toLong(), part))
        Log.i(
            TAG,
            "Sending ${store.nameOf(request.hash)} bytes ${request.offset}-$end" + if (sent == null) ", but LXMF refused" else "",
        )
    }

    /** The member LXMF proved sent a request, if they were offered this file. */
    private suspend fun allowed(hash: String, sourceHash: ByteArray): ByteArray? {
        val member = TakLxmf.memberForLxmf(rnsCore, sourceHash)
        return when {
            member == null -> null.also { Log.w(TAG, "A file request from a sender this node cannot name; refused") }
            !store.has(hash) -> null.also { Log.w(TAG, "Asked for a file that is not held here") }
            !store.mayFetch(hash, member, team.isMember) ->
                null.also { Log.w(TAG, "Asked for ${store.nameOf(hash)} by someone never offered it; refused") }
            else -> member.also { offers.remove(hash + it.toHex()) }
        }
    }

    /** Tell this ATAK, once, about offers a member has not fetched after a minute. */
    suspend fun retryDue(now: Long) {
        for ((key, offer) in offers) {
            when {
                now - offer.since > TakFileTransfers.HOLD_MS -> offers.remove(key)
                !offer.told && now - offer.since > TakFileTransfers.UNFETCHED_MS -> {
                    offer.told = true
                    status(
                        "${offer.filename} (${TakFiles.sizeText(offer.size)}) not fetched yet by " +
                            "${team.nameOf(offer.member)}. Over a slow path a file waits for a fast one; " +
                            "ATAK may report this send as failed while it waits.",
                    )
                }
            }
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
