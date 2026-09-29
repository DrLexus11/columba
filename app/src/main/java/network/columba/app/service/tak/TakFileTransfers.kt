package network.columba.app.service.tak

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.columba.app.rns.api.RnsCore
import java.util.concurrent.ConcurrentHashMap

/**
 * Files offered to this handset: fetched over a fast path, deferred over LoRa.
 *
 * A `b-f-t-r` notice arrives from a teammate's ATAK. The file is fetched only
 * while the path to its sender is **measured** to bring it within
 * [TakFileParts.FETCH_BUDGET_MS] -- by timing the parts themselves, a setup
 * part and then a sample; see [TakFileParts]. On a slow path the request waits
 * and is tried again with backoff, for up to [HOLD_MS], without sampling a
 * route already measured slow.
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

        private const val TICK_MS = 15_000L
        const val FIRST_RETRY_MS = 60_000L

        /** How long an offer may wait for a path to its sender before anyone is told. */
        const val PATH_GRACE_MS = 60_000L
        const val MAX_RETRY_MS = 15L * 60 * 1000
        private const val REQUEST_WAIT_MS = 2L * 60 * 1000
        const val HOLD_MS = 24L * 60 * 60 * 1000

        /** How long an offered file may go unfetched before the sender is told. */
        const val UNFETCHED_MS = 60_000L

        /** Where the path table says [inbox] is now, or null -- a path asked for -- if nowhere. */
        private suspend fun routeTo(rnsCore: RnsCore, inbox: ByteArray?): TakFileParts.Route? {
            if (inbox == null) return null
            if (!rnsCore.hasPath(inbox)) {
                rnsCore.requestPath(inbox)
                return null
            }
            return TakFileParts.Route(rnsCore.getHopCount(inbox) ?: -1, rnsCore.getNextHopInterfaceName(inbox) ?: "unknown")
        }

        private fun tooSlow(leftMs: Long) = TakStatusLines.slowReason(leftMs / 1000)

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

        /** Where the part in flight starts, and how long it is. */
        @Volatile var askedOffset = -1L

        @Volatile var askedLength = 0

        /** The route this attempt samples, how many parts it has had, and whether one was judged. */
        @Volatile var route: TakFileParts.Route? = null

        @Volatile var partsThisAttempt = 0

        @Volatile var judged = false

        /**
         * Whether [part] is the one asked for and still waited for: a request
         * is in flight, and the part matches it on offset, length and the
         * file's size. A late answer after a pause, another offset or length,
         * or a different total would each drive the gate on something it did
         * not measure.
         */
        fun isOutstanding(part: TakFileParts.Part): Boolean =
            askedAt != 0L &&
                part.offset == askedOffset &&
                part.data.size == askedLength &&
                part.total == notice.size

        /**
         * Serialises everything that asks for a part of this file.
         *
         * The retry tick's [attempt] and an arriving [onPart] both end in
         * [requestPart], and both suspend -- on the probe and on the send --
         * with the request state updated only around them. Racing, they asked
         * for the same offset twice: one answer was then thrown away by
         * appendPartial, a whole direct LXMF transfer wasted, and the next
         * tick could repeat it. A coroutine lock, because both hold it across
         * a suspension.
         */
        val mutex = Mutex()
    }

    private val pending = ConcurrentHashMap<String, Pending>()
    /** What this ATAK offered, and the parts teammates ask for. */
    private val sending = TakFileSender(store, rnsCore, lxmf, team, clock, thumbnailer) { status(it) }
    private val rates = TakFileParts.PathRates()

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

    /**
     * A whole file arrived. Always refused: this handset never asks for one.
     *
     * It fetches in parts, each requested and timed, only over a path measured
     * to be fast -- see [onPart]. A whole file is what an *older* receiver
     * asks for, and this handset answers those in [TakFileSender.onRequest];
     * it never sends that request itself, so one arriving here was pushed,
     * not fetched. Accepting it because an offer happened to be pending let a
     * sender deliver a file the fetch gate had deliberately deferred -- over a
     * slow path, stored and shown as though it had been asked for.
     */
    fun onFile(inbound: TakLxmf.Inbound) {
        val name = TakFiles.decodeFile(inbound.frame)?.name ?: "a file"
        Log.w(TAG, "$name arrived whole and unasked; this handset fetches in parts. Discarded")
    }

    /**
     * One part of a file being fetched: kept, timed, and the next asked for
     * only while the rest would arrive within [TakFileParts.FETCH_BUDGET_MS].
     */
    suspend fun onPart(inbound: TakLxmf.Inbound) {
        val part = TakFileParts.decodePart(inbound.frame)
        val entry = part?.let { pending[it.hash] }
        val from = entry?.let { TakLxmf.memberForLxmf(rnsCore, inbound.sourceHash) }
        if (part == null || entry == null || from?.contentEquals(entry.sender) != true) {
            Log.w(TAG, "A part arrived that was not asked for, or not from its sender; discarded")
            return
        }
        // The same lock attempt() takes: see Pending.mutex.
        entry.mutex.withLock {
            when {
                // Only the part asked for, while it is still waited for: a late
                // answer after a pause, another offset or length, or a different
                // total would each drive the gate on something it did not measure.
                !entry.isOutstanding(part) ->
                    Log.w(TAG, "A part of ${entry.notice.filename} that was not the one outstanding; discarded")
                !store.appendPartial(part.hash, part.offset, part.data) -> Unit
                store.partialSize(part.hash) >= part.total ->
                    store.takePartial(part.hash)?.let { complete(entry, it, entry.notice.filename) }
                else -> judge(entry, part)
            }
        }
    }

    /**
     * A part kept and the file not yet whole: ask for the next, or pause. The
     * first part of an attempt carries the link and transfer setup and is not
     * judged -- measured 2026-09-26, 64 KB in 16.4 s over one BLE hop, setup
     * included, read as ~32 kbit/s and the file deferred.
     */
    private suspend fun judge(entry: Pending, part: TakFileParts.Part) {
        val have = store.partialSize(part.hash)
        val took = clock() - entry.askedAt
        entry.askedAt = 0
        entry.partsThisAttempt++
        if (entry.partsThisAttempt == 1) {
            Log.i(TAG, "${entry.notice.filename}: $have of ${part.total} bytes; setup part in ${took}ms, not judged")
            requestPart(entry)
            return
        }
        val left = TakFileParts.msLeft(part.total - have, part.data.size, took)
        val bytesPerSecond = part.data.size * 1000.0 / maxOf(took, 1L)
        rates.record(entry.sender.toHex(), entry.route, bytesPerSecond, clock())
        Log.i(
            TAG,
            "${entry.notice.filename}: $have of ${part.total} bytes, last part in ${took}ms " +
                "(${(bytesPerSecond * 8).toLong()} bit/s); the rest ~${left / 1000}s",
        )
        if (left <= TakFileParts.FETCH_BUDGET_MS) {
            entry.judged = true
            requestPart(entry)
        } else {
            waitForFastPath(entry, tooSlow(left))
        }
    }

    /** Ask the sender for the next part. False if it cannot be asked now. */
    private suspend fun requestPart(entry: Pending): Boolean {
        val offset = store.partialSize(entry.notice.hash)
        val length = TakFileParts.partLength(offset, entry.notice.size, entry.partsThisAttempt, entry.judged)
        val request = TakFileParts.encodeRequest(entry.notice.hash, offset, length)
        if (lxmf.send(entry.sender, request, "", propagate = false) == null) return false
        entry.askedAt = clock()
        entry.askedOffset = offset
        entry.askedLength = length
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

    private suspend fun attempt(entry: Pending) = entry.mutex.withLock {
        val now = clock()
        // Re-checked under the lock. An onPart that held it may already have
        // asked for the next part, moving nextTryAt on; attempting anyway is
        // the duplicate request this lock exists to prevent.
        if (now < entry.nextTryAt) return@withLock
        entry.nextTryAt = now + REQUEST_WAIT_MS
        val route = routeTo(rnsCore, lxmf.inboxFor(entry.sender))
        if (route == null && now - entry.since < PATH_GRACE_MS) {
            // An offer arrives before its sender's inbox is known more often
            // than not; the path request just sent is usually answered in
            // seconds. Look again on the next tick and tell nobody yet -- but
            // a QuickPic's preview costs nothing on air, so it is shown now.
            entry.nextTryAt = now + TICK_MS
            Log.i(TAG, "no path to the sender of ${entry.notice.filename} yet; asked, looking again shortly")
            preview(entry)
            return@withLock
        }
        if (route == null) {
            waitForFastPath(entry, TakStatusLines.REASON_NO_PATH)
            return@withLock
        }
        val rate = rates.known(entry.sender.toHex(), route, now)
        Log.i(
            TAG,
            "route to the sender of ${entry.notice.filename}: ${route.hops} hop(s) via ${route.via}; " +
                (rate?.let { "${(it * 8).toLong()} bit/s measured" } ?: "not measured lately"),
        )
        val remaining = entry.notice.size - store.partialSize(entry.notice.hash)
        val left = rate?.let { (remaining * 1000 / it).toLong() }
        if (left != null && left > TakFileParts.FETCH_BUDGET_MS) {
            // Measured slow on this very route: nothing spent on air.
            waitForFastPath(entry, tooSlow(left))
            return@withLock
        }
        // Not measured on this route yet: the sample decides, and over LoRa it
        // takes minutes (86 s for 12 KB at the deck, 2026-09-27). The preview
        // is local and free, so it goes up now. A route measured fast brings
        // the whole file within the budget; a preview then would be a second
        // notice a moment before the first.
        if (rate == null) preview(entry)
        entry.route = route
        entry.partsThisAttempt = 0
        entry.judged = false
        if (!requestPart(entry)) waitForFastPath(entry, null)
    }

    /** Hold the file, show a preview if there is one, and try again later. */
    private suspend fun waitForFastPath(entry: Pending, reason: String?) {
        entry.askedAt = 0
        Log.i(TAG, "${reason ?: TakStatusLines.REASON_SLOW}; ${entry.notice.filename} waits, next try in ${entry.backoffMs / 1000}s")
        entry.nextTryAt = clock() + entry.backoffMs
        entry.backoffMs = minOf(entry.backoffMs * 2, MAX_RETRY_MS)
        val previewed = preview(entry) || entry.previewHash != null
        if (!entry.told) {
            entry.told = true
            status(
                TakStatusLines.heldLine(
                    entry.notice.filename, entry.notice.size, nameOf(entry.sender), reason ?: TakStatusLines.REASON_SLOW, previewed,
                ),
            )
        }
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
