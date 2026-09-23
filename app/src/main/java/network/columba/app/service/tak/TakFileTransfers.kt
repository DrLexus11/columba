package network.columba.app.service.tak

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.model.LinkSpeedProbeResult
import java.util.concurrent.ConcurrentHashMap

/**
 * Files offered to this handset: fetched over a fast path, deferred over LoRa.
 *
 * A `b-f-t-r` notice arrives from a teammate's ATAK. The file is fetched only
 * when the path to its sender is **measured** to be fast -- a Reticulum link
 * handshake, round trip under [FAST_RTT_S]. The first hop is not trusted for
 * this: from a handset on TCP to the deck, and from the deck to a board, the
 * first hop is fast even when LoRa lies further on. On a slow path the request
 * waits and is tried again with backoff, for up to [HOLD_MS].
 *
 * Only once the file is here, and is the file the notice named, does ATAK see
 * the notice -- rewritten to fetch from [TakFileServer] on this handset. ATAK
 * never sees an offer it cannot complete.
 */
class TakFileTransfers(
    private val store: TakFileStore,
    private val server: TakFileServer,
    private val rnsCore: RnsCore,
    private val lxmf: TakLxmf.Carrier,
    private val toAtak: suspend (ByteArray) -> Unit,
    private val team: Team = Team(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val thumbnailer: (ByteArray, Int) -> ByteArray? = TakFileOffer::thumbnail,
) {
    /** Who this node is on the team, and what it knows of the others. */
    class Team(
        /** This node's UID, which a status line is addressed to. Empty sends none. */
        val ourUid: String = "",
        /** This node's sender id, which its offers carry. */
        val ourSenderId: Int = 0,
        val isMember: (ByteArray) -> Boolean = { false },
        val nameOf: (ByteArray) -> String = { "a teammate" },
        val members: () -> List<ByteArray> = { emptyList() },
    )

    private val ourUid get() = team.ourUid
    private val ourSenderId get() = team.ourSenderId
    private val isMember get() = team.isMember
    private val nameOf get() = team.nameOf
    private val members get() = team.members

    companion object {
        private const val TAG = "TakFileTransfers"

        /**
         * A link handshake faster than this is a fast path. LoRa at SF7/BW250
         * spends about 0.26 s on the air for a request and its proof alone,
         * before any BLE hop or channel wait; TCP and Wi-Fi measure tens of
         * milliseconds. The probe's figures are logged for every file, so
         * this is revisited on data rather than guessed at twice.
         */
        const val FAST_RTT_S = 0.5
        private const val PROBE_TIMEOUT_S = 10f
        private const val TICK_MS = 15_000L
        const val FIRST_RETRY_MS = 60_000L
        const val MAX_RETRY_MS = 15L * 60 * 1000
        private const val REQUEST_WAIT_MS = 2L * 60 * 1000
        const val HOLD_MS = 24L * 60 * 60 * 1000

        /**
         * One measurement per sender serves every file waiting on them for this
         * long. Each probe is a link handshake, over LoRa when the path is slow,
         * and three files from one sender were three handshakes on the bench.
         */
        const val PROBE_REUSE_MS = 30_000L

        /** How long an offered file may go unfetched before the sender is told. */
        const val UNFETCHED_MS = 60_000L

        fun isFast(probe: LinkSpeedProbeResult): Boolean =
            probe.isSuccess && (probe.rttSeconds ?: Double.MAX_VALUE) < FAST_RTT_S

        /** Transfers for one endpoint session, keeping files under [parent]/tak_files. */
        fun inDirectory(
            parent: java.io.File,
            rnsCore: RnsCore,
            lxmf: TakLxmf.Carrier,
            registry: TakMembership.Registry,
            ourUid: String,
            toAtak: suspend (ByteArray) -> Unit,
        ): TakFileTransfers {
            val store = TakFileStore(java.io.File(parent, "tak_files"))
            val team =
                Team(
                    ourUid = ourUid,
                    ourSenderId = TakIdentity.destinationFor(ourUid)?.let(TakMembership::senderIdFor) ?: 0,
                    isMember = { registry.isMember(it, System.currentTimeMillis()) },
                    nameOf = { registry.describe(it)?.callsign ?: "a teammate" },
                    members = { registry.members(System.currentTimeMillis()) },
                )
            return TakFileTransfers(store, TakFileServer(store), rnsCore, lxmf, toAtak, team)
        }
    }

    private class Pending(
        val notice: TakFiles.Notice,
        val xml: String,
        val sender: ByteArray,
        val since: Long,
        val offer: TakFileOffer.Offer? = null,
    ) {
        @Volatile var previewed = false

        /** The preview package made for this file, deleted when the file arrives. */
        @Volatile var previewHash: String? = null

        @Volatile var nextTryAt = since

        @Volatile var backoffMs = FIRST_RETRY_MS

        @Volatile var told = false
    }

    /** A file this ATAK offered one member, until they fetch it. */
    private class Offer(val filename: String, val size: Long, val member: ByteArray, val since: Long) {
        @Volatile var told = false
    }

    private val pending = ConcurrentHashMap<String, Pending>()
    private val offers = ConcurrentHashMap<String, Offer>()
    private val probes = ConcurrentHashMap<String, Pair<Long, LinkSpeedProbeResult?>>()

    /** Files offered and not yet here. */
    fun waiting(): Int = pending.size

    /**
     * Take a rendered event if it is a file notice. True when it was, and so
     * must not also be written to ATAK as it stands.
     */
    suspend fun intercept(event: ByteArray, sourceHash: ByteArray): Boolean {
        val xml = String(event, Charsets.UTF_8)
        val notice = TakFiles.parseNotice(xml) ?: return false
        val sender = if (store.has(notice.hash)) null else TakLxmf.memberForLxmf(rnsCore, sourceHash)
        when {
            store.has(notice.hash) -> deliver(notice, xml)
            sender == null -> Log.w(TAG, "${notice.filename} offered by a sender this node cannot name; not fetched")
            else -> {
                Log.i(TAG, "${notice.filename} (${notice.size} bytes) offered; measuring the path to its sender")
                attempt(pending.getOrPut(notice.hash) { Pending(notice, xml, sender, clock()) })
            }
        }
        return true
    }

    /**
     * This ATAK sent a file notice to [recipients] (null for the team). Only
     * they may fetch the file -- the rule that keeps a pin sent to one person
     * off everyone else's map.
     */
    suspend fun offered(cotXml: String, recipients: List<ByteArray>?): Boolean {
        val notice = TakFiles.parseNotice(cotXml) ?: return false
        if (store.has(notice.hash)) {
            sendOffer(notice, recipients)
            store.grant(notice.hash, recipients)
            recipients?.forEach {
                offers[notice.hash + it.toHex()] = Offer(notice.filename, notice.size, it, clock())
            }
            Log.i(TAG, "${notice.filename} offered to ${recipients?.let { "${it.size} member(s)" } ?: "the team"}")
            return true
        }
        Log.w(TAG, "${notice.filename} offered but never uploaded here; nobody will be able to fetch it")
        return false
    }

    /**
     * The offer on the air in place of ATAK's notice: about 55 bytes for a
     * data package, up to three fragments for a QuickPic with its position and
     * a thumbnail. Always over LXMF -- the receiver must know whom to ask.
     */
    private suspend fun sendOffer(notice: TakFiles.Notice, recipients: List<ByteArray>?) {
        val data = store.read(notice.hash) ?: return
        val offer = TakFileOffer.encode(TakFileOffer.offerFor(ourSenderId, notice.hash, notice.filename, data, thumbnailer))
        val frames = if (offer.size <= CotFragment.MAX_FRAGMENT_FRAME_BYTES) listOf(offer) else CotFragment.fragments(offer)
        for (member in recipients ?: members()) {
            for (frame in frames) {
                if (lxmf.send(member, frame, "") == null) Log.w(TAG, "LXMF would not take an offer for a member")
            }
        }
        Log.i(TAG, "Offer for ${notice.filename}: ${offer.size} bytes in ${frames.size} frame(s)")
    }

    /** An offer arrived: rebuild ATAK's notice and fetch, preview or wait. */
    suspend fun onOffer(raw: ByteArray, sourceHash: ByteArray) {
        val offer = TakFileOffer.decode(raw)
        val sender = offer?.let { TakLxmf.memberForLxmf(rnsCore, sourceHash) }
        when {
            offer == null || sender == null || !isMember(sender) ->
                Log.w(TAG, "An offer from a sender this node cannot name; refused")
            TakMembership.senderIdFor(sender) != offer.senderId ->
                Log.w(TAG, "An offer whose claimed sender is not the one proved; refused")
            else -> {
                val xml = TakFileOffer.notice(offer, TakIdentity.uidFor(sender), nameOf(sender), clock())
                val notice = TakFiles.Notice(offer.hash, offer.filename, offer.size)
                if (store.has(offer.hash)) {
                    deliver(notice, xml)
                } else {
                    Log.i(TAG, "${offer.filename} (${offer.size} bytes) offered; measuring the path to its sender")
                    attempt(pending.getOrPut(offer.hash) { Pending(notice, xml, sender, clock(), offer) })
                }
            }
        }
    }

    /** Over a slow path, a QuickPic's thumbnail at its position, once. */
    private suspend fun preview(entry: Pending): Boolean {
        val offer = entry.offer?.takeIf { it.thumbnail != null && it.point != null && !entry.previewed } ?: return false
        entry.previewed = true
        val (bytes, filename) = TakFileOffer.preview(offer, nameOf(entry.sender), clock())
        val hash = store.put(bytes, filename) ?: return false
        entry.previewHash = hash
        val shown = TakFileOffer.Offer(offer.senderId, hash, bytes.size.toLong(), filename, offer.point)
        deliver(
            TakFiles.Notice(hash, filename, bytes.size.toLong()),
            TakFileOffer.notice(shown, TakIdentity.uidFor(entry.sender), nameOf(entry.sender), clock()),
        )
        return true
    }

    /**
     * [deliver], except that a file notice from [sourceHash] is held here until
     * its file is -- so ATAK is never offered what it cannot fetch.
     */
    fun passing(sourceHash: ByteArray, deliver: suspend (ByteArray) -> Unit): suspend (ByteArray) -> Unit =
        { event -> if (!intercept(event, sourceHash)) deliver(event) }

    /** Take an inbound frame if it is a file or a request for one. True when it was. */
    suspend fun takeFile(inbound: TakLxmf.Inbound): Boolean {
        when (TakPayload.kindOf(inbound.frame)) {
            TakPayload.FILE_V1 -> onFile(inbound)
            TakPayload.FILE_REQUEST_V1 -> onRequest(inbound)
            TakPayload.FILE_OFFER_V1 -> onOffer(inbound.frame, inbound.sourceHash)
            else -> return false
        }
        return true
    }

    /** A teammate asked for a file: sent if the member LXMF proved was offered it. */
    suspend fun onRequest(inbound: TakLxmf.Inbound) {
        val hash = TakFiles.decodeRequest(inbound.frame)
        val member = hash?.let { TakLxmf.memberForLxmf(rnsCore, inbound.sourceHash) }
        val data = hash?.let { store.read(it) }
        when {
            hash == null || member == null -> Log.w(TAG, "A file request from a sender this node cannot name; refused")
            data == null -> Log.w(TAG, "Asked for a file that is not held here")
            !store.mayFetch(hash, member, isMember) -> Log.w(TAG, "Asked for ${store.nameOf(hash)} by someone never offered it; refused")
            else -> {
                val name = store.nameOf(hash)
                offers.remove(hash + member.toHex())
                val sent = lxmf.sendFile(member, TakFiles.encodeFile(hash, name, data))
                Log.i(TAG, "Sending $name (${data.size} bytes)" + if (sent == null) ", but LXMF refused" else "")
            }
        }
    }

    /** A file arrived. Kept only if it was asked for, from whom it was asked. */
    suspend fun onFile(inbound: TakLxmf.Inbound) {
        val file = TakFiles.decodeFile(inbound.frame)
        val entry = file?.let { pending[it.hash] }
        val from = entry?.let { TakLxmf.memberForLxmf(rnsCore, inbound.sourceHash) }
        when {
            file == null -> Log.w(TAG, "A file arrived that is not what its hash names; discarded")
            entry == null -> Log.w(TAG, "A file arrived that was not asked for; discarded")
            from == null || !from.contentEquals(entry.sender) ->
                Log.w(TAG, "${entry.notice.filename} arrived from someone other than its sender; discarded")
            store.put(file.data, file.name.ifEmpty { entry.notice.filename }, file.hash) == null ->
                Log.w(TAG, "${entry.notice.filename} could not be stored")
            else -> {
                pending.remove(file.hash)
                // The full package replaces the preview in ATAK; here too, so
                // the list does not keep a copy nobody needs.
                entry.previewHash?.let { store.delete(it) }
                Log.i(TAG, "${entry.notice.filename} arrived, ${file.data.size} bytes, in ${(clock() - entry.since) / 1000}s")
                deliver(entry.notice, entry.xml)
            }
        }
    }

    /** Serve received files to ATAK and retry what is waiting, until [scope] ends. */
    fun start(scope: CoroutineScope): List<Job> = listOf(server.start(scope), run(scope))

    /** Retry what is due, until [scope] ends. */
    fun run(scope: CoroutineScope): Job =
        scope.launch {
            while (isActive) {
                delay(TICK_MS)
                retryDue()
            }
        }

    suspend fun retryDue() {
        val now = clock()
        for ((key, offer) in offers) {
            when {
                now - offer.since > HOLD_MS -> offers.remove(key)
                !offer.told && now - offer.since > UNFETCHED_MS -> {
                    offer.told = true
                    status(
                        "${offer.filename} (${TakFiles.sizeText(offer.size)}) not fetched yet by " +
                            "${nameOf(offer.member)}. Over a slow path a file waits for a fast one; " +
                            "ATAK may report this send as failed while it waits.",
                    )
                }
            }
        }
        for ((hash, entry) in pending) {
            when {
                now - entry.since > HOLD_MS -> {
                    pending.remove(hash)
                    Log.i(TAG, "${entry.notice.filename} never found a fast path; given up after a day")
                }
                now >= entry.nextTryAt -> attempt(entry)
            }
        }
    }

    private suspend fun attempt(entry: Pending) {
        val now = clock()
        // Claimed before the probe, which takes seconds over LoRa: the retry
        // tick must not start a second probe of the same path meanwhile.
        entry.nextTryAt = now + REQUEST_WAIT_MS
        val probe = probe(entry.sender, now)
        Log.i(
            TAG,
            "path to the sender of ${entry.notice.filename}: ${probe?.status ?: "no inbox"}, " +
                "rtt=${probe?.rttSeconds}s, handshake=${probe?.establishmentRateBps}bps, " +
                "hops=${probe?.hops}, first hop=${probe?.nextHopBitrateBps}bps",
        )
        if (probe != null && isFast(probe)) {
            val sent = lxmf.send(entry.sender, TakFiles.encodeRequest(entry.notice.hash), "", propagate = false)
            Log.i(TAG, "Fast path; asked for ${entry.notice.filename}" + if (sent == null) ", but LXMF refused" else "")
            entry.nextTryAt = now + REQUEST_WAIT_MS
        } else {
            Log.i(TAG, "Slow or no path; ${entry.notice.filename} waits, next try in ${entry.backoffMs / 1000}s")
            entry.nextTryAt = now + entry.backoffMs
            entry.backoffMs = minOf(entry.backoffMs * 2, MAX_RETRY_MS)
            val previewed = preview(entry)
            if (!entry.told) {
                entry.told = true
                status(
                    "${entry.notice.filename} (${TakFiles.sizeText(entry.notice.size)}) from " +
                        "${nameOf(entry.sender)} is waiting: the path is too slow to bring it now. " +
                        "It arrives when a fast path appears." + if (previewed) " A preview is on the map." else "",
                )
            }
        }
    }

    /** The path to [sender], measured at most once per [PROBE_REUSE_MS] for all its files. */
    private suspend fun probe(sender: ByteArray, now: Long): LinkSpeedProbeResult? {
        val key = sender.toHex()
        probes[key]?.takeIf { now - it.first < PROBE_REUSE_MS }?.let { return it.second }
        val measured = lxmf.inboxFor(sender)?.let { rnsCore.probeLinkSpeed(it, PROBE_TIMEOUT_S, "direct") }
        probes[key] = now to measured
        return measured
    }

    private suspend fun status(text: String) {
        if (ourUid.isNotEmpty()) toAtak(TakFiles.statusLine(ourUid, text, clock()))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private suspend fun deliver(notice: TakFiles.Notice, xml: String) {
        val url = TakFiles.contentUrl(server.base, notice.hash)
        toAtak(TakFiles.rewriteNotice(xml, url, clock()).toByteArray(Charsets.UTF_8))
        Log.i(TAG, "${notice.filename} offered to ATAK from $url")
    }
}
