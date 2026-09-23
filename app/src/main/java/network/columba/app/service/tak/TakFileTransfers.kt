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
    private val isMember get() = team.isMember
    private val nameOf get() = team.nameOf

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

        /** When the part in flight was asked for; 0 when none is. */
        @Volatile var askedAt = 0L
    }

    private val pending = ConcurrentHashMap<String, Pending>()
    /** What this ATAK offered, and the parts teammates ask for. */
    private val sending = TakFileSender(store, rnsCore, lxmf, team, clock, thumbnailer) { status(it) }
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

    /** This ATAK sent a file notice; true when an offer went in its place. See [TakFileSender]. */
    suspend fun offered(cotXml: String, recipients: List<ByteArray>?): Boolean = sending.offered(cotXml, recipients)

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
            TakPayload.FILE_REQUEST_V1 -> sending.onRequest(inbound)
            TakPayload.FILE_PART_REQUEST_V1 -> sending.onPartRequest(inbound)
            TakPayload.FILE_PART_V1 -> onPart(inbound)
            TakPayload.FILE_OFFER_V1 -> onOffer(inbound.frame, inbound.sourceHash)
            else -> return false
        }
        return true
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
            else -> complete(entry, file.data, file.name)
        }
    }

    /**
     * One part of a file being fetched: kept, timed, and the next asked for
     * only while the rest would arrive within [TakFileParts.FETCH_BUDGET_MS].
     */
    suspend fun onPart(inbound: TakLxmf.Inbound) {
        val part = TakFileParts.decodePart(inbound.frame)
        val entry = part?.let { pending[it.hash] }
        val from = entry?.let { TakLxmf.memberForLxmf(rnsCore, inbound.sourceHash) }
        when {
            part == null || entry == null || from == null || !from.contentEquals(entry.sender) ->
                Log.w(TAG, "A part arrived that was not asked for, or not from its sender; discarded")
            !store.appendPartial(part.hash, part.offset, part.data) -> Unit
            store.partialSize(part.hash) >= part.total ->
                store.takePartial(part.hash)?.let { complete(entry, it, entry.notice.filename) }
            else -> {
                val have = store.partialSize(part.hash)
                val took = clock() - entry.askedAt
                val left = TakFileParts.msLeft(part.total - have, part.data.size, took)
                entry.askedAt = 0
                Log.i(TAG, "${entry.notice.filename}: $have of ${part.total} bytes, last part in ${took}ms; the rest ~${left / 1000}s")
                if (left <= TakFileParts.FETCH_BUDGET_MS) {
                    requestPart(entry)
                } else {
                    waitForFastPath(entry, "at the rate this path is giving, the rest would take about ${maxOf(1, left / 60_000)} min")
                }
            }
        }
    }

    /** Ask the sender for the next part. False if it cannot be asked now. */
    private suspend fun requestPart(entry: Pending): Boolean {
        val offset = store.partialSize(entry.notice.hash)
        val length = TakFileParts.nextPartLength(offset, entry.notice.size)
        val request = TakFileParts.encodeRequest(entry.notice.hash, offset, length)
        if (lxmf.send(entry.sender, request, "", propagate = false) == null) return false
        entry.askedAt = clock()
        // A part that never comes -- a link that dropped -- is asked for again
        // on the retry tick after the budget, from wherever the file got to.
        entry.nextTryAt = entry.askedAt + TakFileParts.FETCH_BUDGET_MS
        Log.i(TAG, "Asked for ${entry.notice.filename} bytes $offset-${offset + length} of ${entry.notice.size}")
        return true
    }

    /** The file is whole: checked against its hash, kept, and offered to ATAK. */
    private suspend fun complete(entry: Pending, data: ByteArray, name: String) {
        pending.remove(entry.notice.hash)
        if (store.put(data, name.ifEmpty { entry.notice.filename }, entry.notice.hash) == null) {
            Log.w(TAG, "${entry.notice.filename} was fetched but is not the file its hash names; discarded")
            return
        }
        // The full package replaces the preview in ATAK; here too, so the list
        // does not keep a copy nobody needs.
        entry.previewHash?.let { store.delete(it) }
        Log.i(TAG, "${entry.notice.filename} arrived, ${data.size} bytes, in ${(clock() - entry.since) / 1000}s")
        deliver(entry.notice, entry.xml)
    }

    /** Serve received files to ATAK and retry what is waiting, until [scope] ends. */
    fun start(scope: CoroutineScope): List<Job> =
        listOf(
            server.start(scope),
            scope.launch {
                while (isActive) {
                    delay(TICK_MS)
                    retryDue()
                }
            },
        )

    suspend fun retryDue() {
        val now = clock()
        sending.retryDue(now)
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
        // Round trip is the cheap pre-filter: LoRa in the path never pays for
        // a sample. Whether the file arrives in time is measured by the parts.
        if (!(probe != null && isFast(probe) && requestPart(entry))) waitForFastPath(entry, null)
    }

    /** Hold the file, show a preview if there is one, and try again later. */
    private suspend fun waitForFastPath(entry: Pending, reason: String?) {
        Log.i(TAG, "${reason ?: "Slow or no path"}; ${entry.notice.filename} waits, next try in ${entry.backoffMs / 1000}s")
        entry.nextTryAt = clock() + entry.backoffMs
        entry.backoffMs = minOf(entry.backoffMs * 2, MAX_RETRY_MS)
        val previewed = preview(entry)
        if (!entry.told) {
            entry.told = true
            status(
                "${entry.notice.filename} (${TakFiles.sizeText(entry.notice.size)}) from " +
                    "${nameOf(entry.sender)} is waiting: ${reason ?: "the path is too slow to bring it now"}. " +
                    "It arrives when a fast path appears." + if (previewed) " A preview is on the map." else "",
            )
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
