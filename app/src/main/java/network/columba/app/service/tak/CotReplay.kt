package network.columba.app.service.tak

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What the endpoint holds for an ATAK that is not attached yet.
 *
 * The Kotlin half of the replay buffer in `tools/cot_bridge.py`.
 *
 * Columba is reliable because LXMF **persists** a message. ATAK is handed a
 * live stream and kept nothing, so anything rendered while it was detached was
 * gone -- and ATAK detaches routinely, for seconds on a reconnect and for
 * minutes when a phone is locked. Seen twice on hardware 2026-09-12: a reply
 * crossed two LoRa hops, was decoded and rebuilt correctly, and was dropped at
 * the socket because nothing was listening. Same data, same path, two different
 * guarantees.
 *
 * **Tier 2 only.** Position is deliberately not held: it is latest-wins, a
 * fresher one is seconds away, and replaying a ten-minute-old fix as though it
 * were current puts somebody on the map where they are not, which is worse than
 * showing nothing. The tiering in `TAKNative.md` already drew that line; this
 * applies it at the socket.
 */
class CotReplay(
    private val maxEvents: Int = MAX_EVENTS,
    private val maxAgeMs: Long = MAX_AGE_MS,
) {
    companion object {
        /**
         * Fifteen minutes covers a reconnect, an app restart and a locked
         * screen without reaching back into content an operator has moved on
         * from. The count bounds a bridge nobody ever attaches to.
         */
        const val MAX_EVENTS = 64
        const val MAX_AGE_MS = 15L * 60 * 1000
    }

    private val held = ArrayDeque<Pair<Long, ByteArray>>()
    private val lock = Mutex()

    /** Keep one rendered event for whoever attaches next. */
    suspend fun hold(payload: ByteArray, now: Long) {
        lock.withLock {
            expire(now)
            while (held.size >= maxEvents) held.removeFirst()
            held.addLast(now to payload)
        }
    }

    /**
     * What a client attaching now has missed, oldest first.
     *
     * Oldest first so a conversation arrives in the order it happened. Every
     * frame carries its own uid and message id, so a client that already has
     * one recognises it again -- replay is safe to repeat and cheap to ignore.
     */
    suspend fun pending(now: Long): List<ByteArray> =
        lock.withLock {
            expire(now)
            held.map { it.second }
        }

    /** For the settings screen and for tests. */
    suspend fun size(): Int = lock.withLock { held.size }

    private fun expire(now: Long) {
        while (held.isNotEmpty() && now - held.first().first > maxAgeMs) {
            held.removeFirst()
        }
    }
}
