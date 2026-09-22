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
    private val clock: () -> Long = System::currentTimeMillis,
) {
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

        fun isFast(probe: LinkSpeedProbeResult): Boolean =
            probe.isSuccess && (probe.rttSeconds ?: Double.MAX_VALUE) < FAST_RTT_S

        /** Transfers for one endpoint session, keeping files under [parent]/tak_files. */
        fun inDirectory(
            parent: java.io.File,
            rnsCore: RnsCore,
            lxmf: TakLxmf.Carrier,
            toAtak: suspend (ByteArray) -> Unit,
        ): TakFileTransfers {
            val store = TakFileStore(java.io.File(parent, "tak_files"))
            return TakFileTransfers(store, TakFileServer(store), rnsCore, lxmf, toAtak)
        }
    }

    private class Pending(
        val notice: TakFiles.Notice,
        val xml: String,
        val sender: ByteArray,
        val since: Long,
    ) {
        @Volatile var nextTryAt = since

        @Volatile var backoffMs = FIRST_RETRY_MS
    }

    private val pending = ConcurrentHashMap<String, Pending>()

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

    /** Take an inbound frame if it is a file. True when it was. */
    suspend fun takeFile(inbound: TakLxmf.Inbound): Boolean {
        if (TakPayload.kindOf(inbound.frame) != TakPayload.FILE_V1) return false
        onFile(inbound)
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
            store.put(file.data, file.name.ifEmpty { entry.notice.filename }, file.hash) == null ->
                Log.w(TAG, "${entry.notice.filename} could not be stored")
            else -> {
                pending.remove(file.hash)
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
        val inbox = lxmf.inboxFor(entry.sender)
        val probe = inbox?.let { rnsCore.probeLinkSpeed(it, PROBE_TIMEOUT_S, "direct") }
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
        }
    }

    private suspend fun deliver(notice: TakFiles.Notice, xml: String) {
        val url = TakFiles.contentUrl(server.base, notice.hash)
        toAtak(TakFiles.rewriteNotice(xml, url, clock()).toByteArray(Charsets.UTF_8))
        Log.i(TAG, "${notice.filename} offered to ATAK from $url")
    }
}
