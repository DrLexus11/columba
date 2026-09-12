package network.columba.app.service.tak

/**
 * Everything that happens to an event between ATAK and the mesh.
 *
 * Separated from the socket so the order of the three steps can be tested,
 * because the order is the whole of it: learn the device's UID, refuse anything
 * that is already ours, then rewrite our self-reports. Getting the second and
 * third the wrong way round rewrites an echo into a fresh event and produces a
 * loop that sustains itself.
 *
 * One of these per endpoint rather than one per app. The learned UID belongs to
 * the run: changing team tears the endpoint down, and a UID carried across that
 * would be a claim about an ATAK nobody has heard from since.
 */
class CotOutbound(val ourUid: String) {
    /**
     * The UID this ATAK calls itself, or null until it has said.
     *
     * Volatile because one of these is shared by every accepted connection,
     * and ATAK opens more than one. A UID learned on one client's IO thread
     * was not guaranteed visible to another's, so a self-report arriving
     * concurrently could read null, skip [CotEvent.rewriteSelfUid] and put the
     * ANDROID-xxxx device identifier on the mesh -- the single thing pivot 1
     * exists to keep off it.
     */
    @Volatile
    var atakUid: String? = null
        private set

    /**
     * Counted rather than logged per event; a refusing peer is loud.
     *
     * Atomic for the same reason: incremented from several client coroutines,
     * and a count that loses updates under exactly the load that produces them
     * is worse than no count.
     */
    val dropped: Long get() = droppedCount.get()

    private val droppedCount = java.util.concurrent.atomic.AtomicLong()

    /**
     * The frame to put on the mesh, or null if this event should not go.
     *
     * Null is ordinary and covers three cases: our own event coming back,
     * something that is not CoT at all, and an event we could not encode.
     */
    /**
     * Whether this event is our own, come back to us.
     *
     * Exposed so a caller routing an event to a different codec can ask the
     * same question [frame] asks, rather than reimplementing the guard and
     * getting the order wrong -- which is the bug this class exists to stop.
     */
    fun isEcho(cotXml: String): Boolean = CotEvent.isSelfAddressed(cotXml, ourUid)

    /**
     * Learn what this ATAK calls itself, without sending anything.
     *
     * Separated from [frame] because the typed codecs return early: a session
     * that opened with a marker or a chat line never reached [frame] at all,
     * so nothing was ever learned, and every later self-report that fell
     * through to tier 2 kept the ANDROID-xxxx device identifier that pivot 1
     * exists to remove.
     *
     * Call it on every event that survives the echo guard, whichever codec
     * then handles it. Learning is once per session, so calling it twice costs
     * a parse and changes nothing.
     */
    fun observe(cotXml: String) {
        if (atakUid != null) return
        // Learned, never configured: ATAK announces its own identifier in
        // every position report, and a setting an operator must type is a
        // setting that can be wrong.
        CotEvent.learnAtakUid(cotXml)?.let { atakUid = it }
    }

    fun frame(cotXml: String): ByteArray? {
        // Validated here rather than relied on downstream. rewriteSelfUid
        // returns early -- without parsing -- until an ATAK UID has been
        // learned, so before the first self-report of a session nothing else
        // in this path would look at the event at all, and anything at all
        // could be handed to the mesh.
        try {
            CotEvent.parse(cotXml)
        } catch (_: IllegalArgumentException) {
            droppedCount.incrementAndGet()
            return null
        }
        // The echo guard comes first, before anything is learned from the
        // event. Our own self-report echoed back carries <takv> and is a
        // perfectly well-formed self-report, so learning from it would set the
        // ATAK UID to our own -- after which no genuine self-report matches it
        // and none is ever rewritten again. The device would report itself as
        // ANDROID-xxxx to the whole team for the rest of the session, which is
        // the one outcome this pipeline exists to prevent.
        if (CotEvent.isSelfAddressed(cotXml, ourUid)) return null
        observe(cotXml)
        return try {
            CotTier2.encode(CotEvent.rewriteSelfUid(cotXml, atakUid, ourUid))
        } catch (_: IllegalArgumentException) {
            droppedCount.incrementAndGet()
            null
        }
    }
}
