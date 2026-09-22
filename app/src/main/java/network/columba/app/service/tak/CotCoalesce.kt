package network.columba.app.service.tak

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Latest wins, for an event sent again before its last version has settled.
 *
 * The Kotlin half of `tools/cot_coalesce.py`.
 *
 * An operator editing a shared drawing and sending it again produces several
 * versions of one event in quick succession, and each is a full transfer. Not
 * ATAK's auto-send: that is markers-only, periodic (about 60 s) rather than on
 * change, and takes the marker codec -- checked against ATAK 5.6 on the bench. Costed with `tools/position_budget.py`,
 * one version of a 736 B drawing is about 1.3 s of channel per member on one
 * LoRa hop before any retry: **about 7.7 s for a team of seven.** A drag that
 * emits a version a second asks for eight times what the channel has, and chat
 * and positions would stop rather than slow down.
 *
 * [LatestWins] is the sender: the first version goes at once, anything inside
 * the window replaces what is held, and only the latest held version goes when
 * the window closes. [Freshness] is the receiver: an older version that
 * finishes its retries after a newer one landed is not drawn.
 */
object CotCoalesce {
    /**
     * How long one uid waits between transfers.
     *
     * Set against the 7.7 s a version costs a team of seven, not against
     * ATAK: much shorter and a single drag occupies the channel on its own.
     * The first version is never delayed by this.
     */
    const val WINDOW_MS = 10_000L

    /** An operator drawing all day must not grow either table without bound. */
    const val MAX_UIDS = 256

    /**
     * What a version *says*, without how it was cut up.
     *
     * Hashing the frames never matched: every transfer draws a random transfer
     * id into its fragment headers, so two identical drawings produced
     * different bytes. The first version of this gate had exactly that bug.
     */
    fun contentDigest(frames: List<ByteArray>): String {
        val sha = MessageDigest.getInstance("SHA-256")
        if (frames.size == 1) {
            sha.update(frames[0])
        } else {
            frames.forEach { sha.update(it, CotFragment.HEADER_BYTES, it.size - CotFragment.HEADER_BYTES) }
        }
        return sha.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * One attribute of the `<event>` element itself, or null.
     *
     * Read off the start tag rather than parsed: this runs on every event and
     * needs one attribute. Confined to the start tag so a `uid` or `time` on a
     * child element -- a link, a remark -- is never mistaken for the event's.
     */
    fun eventAttribute(cotXml: String, name: String): String? {
        val start = cotXml.indexOf("<event")
        val end = if (start < 0) -1 else cotXml.indexOf('>', start)
        if (end < 0) return null
        val tag = cotXml.substring(start, end)
        val key = " $name=\""
        val at = tag.indexOf(key)
        val from = at + key.length
        val to = if (at < 0) -1 else tag.indexOf('"', from)
        return if (to > from) tag.substring(from, to) else null
    }

    /**
     * A CoT timestamp in epoch milliseconds, or null.
     *
     * ATAK writes ISO-8601 with a trailing Z and a varying number of
     * fractional digits, which [Instant.parse] accepts as written.
     */
    fun eventTimeMs(value: String?): Long? =
        try {
            value?.let { Instant.parse(it).toEpochMilli() }
        } catch (_: DateTimeParseException) {
            null
        }
}

/** Per-uid leading-and-trailing throttle for events ATAK re-sends on change. */
class LatestWins(
    private val windowMs: Long = CotCoalesce.WINDOW_MS,
    private val maxUids: Int = CotCoalesce.MAX_UIDS,
) {
    enum class Decision { SEND, HOLD, DROP }

    /** [flushAt] is set only for [Decision.HOLD]: when the caller must flush. */
    data class Offer(val decision: Decision, val flushAt: Long? = null)

    private class Sent(val at: Long, val digest: String)

    private class Held(val frames: List<ByteArray>, val digest: String)

    private val sent = LinkedHashMap<String, Sent>()
    private val held = HashMap<String, Held>()

    var coalesced = 0
        private set
    var identical = 0
        private set

    @Synchronized
    fun decide(uid: String, frames: List<ByteArray>, digest: String, now: Long): Offer {
        val last = sent[uid]
        return when {
            // Same bytes as the version that last went: the far end has it.
            last != null && last.digest == digest -> {
                held.remove(uid)
                identical += 1
                Offer(Decision.DROP)
            }
            last == null || now - last.at >= windowMs -> {
                record(uid, digest, now)
                held.remove(uid)
                Offer(Decision.SEND)
            }
            else -> {
                // A replacement rides the flush already arranged for the first.
                val replacing = held.put(uid, Held(frames, digest)) != null
                if (replacing) coalesced += 1
                if (replacing) Offer(Decision.DROP) else Offer(Decision.HOLD, last.at + windowMs)
            }
        }
    }

    /** The held version for this uid, now due, or null if nothing is held. */
    @Synchronized
    fun flush(uid: String, now: Long): List<ByteArray>? {
        val version = held.remove(uid) ?: return null
        if (sent[uid]?.digest == version.digest) {
            identical += 1
            return null
        }
        record(uid, version.digest, now)
        return version.frames
    }

    private fun record(uid: String, digest: String, now: Long) {
        sent.remove(uid)
        sent[uid] = Sent(now, digest)
        if (sent.size > maxUids) sent.keys.firstOrNull()?.let { sent.remove(it) }
    }
}

/** Drop a version older than the one already drawn for the same uid. */
class Freshness(private val maxUids: Int = CotCoalesce.MAX_UIDS) {
    private val drawn = LinkedHashMap<String, Long>()

    var stale = 0
        private set

    /**
     * True if this event should be drawn.
     *
     * Anything without a uid or a readable time is admitted: this stops a
     * known older version overwriting a newer one, it does not refuse what it
     * cannot place. The same time again is admitted too -- a replay to a
     * reconnecting client is the same event, and ATAK recognises it by uid.
     */
    @Synchronized
    fun admit(cotXml: String): Boolean {
        val uid = CotCoalesce.eventAttribute(cotXml, "uid")
        val time = CotCoalesce.eventTimeMs(CotCoalesce.eventAttribute(cotXml, "time"))
        if (uid == null || time == null) return true
        val newest = drawn[uid]
        val fresh = newest == null || time >= newest
        if (fresh) {
            drawn.remove(uid)
            drawn[uid] = time
            if (drawn.size > maxUids) drawn.keys.firstOrNull()?.let { drawn.remove(it) }
        } else {
            stale += 1
        }
        return fresh
    }
}

/**
 * The sender's side, bound to one session.
 *
 * The trailing flush runs in the session's own scope, attached when the
 * session starts serving. A held version therefore dies with its session
 * instead of firing later against a carrier that has been torn down.
 */
class CotVersions(
    private val latest: LatestWins = LatestWins(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var scope: CoroutineScope? = null

    fun attach(scope: CoroutineScope) {
        this.scope = scope
    }

    /**
     * Offer one version, sending it now, holding it for the trailing edge, or
     * dropping it as superseded.
     *
     * [addressees] is who this version is for, null for the team. It is part
     * of the key: one drawing shared with A and then with B is two streams of
     * versions, and B's must not coalesce A's away.
     */
    suspend fun submit(
        cotXml: String,
        frames: List<ByteArray>,
        addressees: List<ByteArray>? = null,
        send: suspend (List<ByteArray>) -> Unit,
    ) {
        if (frames.isEmpty()) return
        val uid =
            CotCoalesce.eventAttribute(cotXml, "uid")?.let { eventUid ->
                if (addressees == null) {
                    eventUid
                } else {
                    eventUid + "\u0000" +
                        addressees.map { hash -> hash.joinToString("") { "%02x".format(it) } }.sorted().joinToString(",")
                }
            }
        val offer =
            if (uid == null) {
                LatestWins.Offer(LatestWins.Decision.SEND)
            } else {
                latest.decide(uid, frames, CotCoalesce.contentDigest(frames), clock())
            }
        val flushAt = offer.flushAt
        val sessionScope = scope
        when {
            offer.decision == LatestWins.Decision.SEND -> send(frames)
            // Not attached is not expected; sending now beats holding a
            // version that nothing will ever flush.
            offer.decision == LatestWins.Decision.HOLD && sessionScope == null -> send(frames)
            offer.decision == LatestWins.Decision.HOLD && flushAt != null && uid != null ->
                sessionScope?.launch {
                    delay(flushAt - clock())
                    latest.flush(uid, clock())?.let { send(it) }
                }
            else -> Unit
        }
    }
}
