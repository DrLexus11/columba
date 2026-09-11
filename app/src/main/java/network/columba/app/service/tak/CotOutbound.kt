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
    /** The UID this ATAK calls itself, or null until it has said. */
    var atakUid: String? = null
        private set

    /** Counted rather than logged per event; a refusing peer is loud. */
    var dropped: Long = 0L
        private set

    /**
     * The frame to put on the mesh, or null if this event should not go.
     *
     * Null is ordinary and covers three cases: our own event coming back,
     * something that is not CoT at all, and an event we could not encode.
     */
    fun frame(cotXml: String): ByteArray? {
        // Validated here rather than relied on downstream. rewriteSelfUid
        // returns early -- without parsing -- until an ATAK UID has been
        // learned, so before the first self-report of a session nothing else
        // in this path would look at the event at all, and anything at all
        // could be handed to the mesh.
        try {
            CotEvent.parse(cotXml)
        } catch (_: IllegalArgumentException) {
            dropped++
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
        if (atakUid == null) {
            // Learned, never configured: ATAK announces its own identifier in
            // every position report, and a setting an operator must type is a
            // setting that can be wrong.
            CotEvent.learnAtakUid(cotXml)?.let { atakUid = it }
        }
        return try {
            CotTier2.encode(CotEvent.rewriteSelfUid(cotXml, atakUid, ourUid))
        } catch (_: IllegalArgumentException) {
            dropped++
            null
        }
    }
}
