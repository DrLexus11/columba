package network.columba.app.service.tak

/**
 * Each peer's latest position, as drawn, for an ATAK that attaches afterwards.
 *
 * ATAK forgets every contact when it restarts, and a peer whose position does
 * not change -- a manual location, an ATAK with no fix -- may not report again
 * for minutes. So after any restart such a peer was on nobody's map and in
 * nobody's contact list, and GeoChat could not address it, until someone moved
 * its marker by hand (bench, 2026-10-02). Columba still knew every one of them.
 *
 * Replayed exactly as rendered, with the time and stale stamps they had then:
 * a fix that has gone stale arrives stale, greyed, never posing as current --
 * the line [CotReplay] draws for positions. Latest wins; nothing is sent on
 * the air.
 */
class LastPositions(
    private val holdMs: Long = HOLD_MS,
    private val maxPeers: Int = MAX_PEERS,
) {
    companion object {
        /** How long past its stale time a position is still worth replaying, greyed. */
        const val HOLD_MS = 60L * 60 * 1000

        /** Bounds a long run with a changing team. */
        const val MAX_PEERS = 256
    }

    private class Entry(
        val xml: String,
        val staleAtMs: Long,
    )

    private val latest = LinkedHashMap<String, Entry>()

    /** [uid]'s latest drawn position, and when it went or goes stale. */
    @Synchronized
    fun remember(
        uid: String,
        xml: String,
        staleAtMs: Long,
    ) {
        latest.remove(uid)
        latest[uid] = Entry(xml, staleAtMs)
        while (latest.size > maxPeers) latest.remove(latest.keys.first())
    }

    /** What a client attaching now should be given: one position per peer, as drawn. */
    @Synchronized
    fun forReplay(nowMs: Long): List<String> {
        latest.values.removeAll { nowMs > it.staleAtMs + holdMs }
        return latest.values.map { it.xml }
    }
}
