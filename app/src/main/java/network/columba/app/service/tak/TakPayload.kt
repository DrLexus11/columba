package network.columba.app.service.tak

import network.columba.app.service.PositionCodec

/**
 * What the first byte of a mesh payload means.
 *
 * The Kotlin half of `tools/tak_payload.py`. Three codecs share one
 * destination -- tier 2 CoT, position reports, and chat -- and a receiver tells
 * them apart by byte zero, which each codec happens to use as its own format
 * version. That works only because those version numbers differ.
 *
 * It is a coincidence held in place by nothing, and the kind that survives
 * right up until somebody bumps a version. So byte zero is declared here as a
 * **single namespace** rather than three independent counters: a codec's
 * version *is* its kind, and a new version of one codec takes a new number from
 * this table rather than incrementing its own.
 *
 * The cost of getting it wrong is not a parse error. A position report read as
 * tier 2 fails cleanly; a tier 2 frame read as a position decodes into
 * coordinates, and a marker lands somewhere nobody put it.
 */
object TakPayload {
    const val COT_TIER2 = 1
    const val POSITION_V2 = 2
    const val CHAT_V1 = 3
    const val MARKER_V1 = 4

    /**
     * Every kind that may appear as byte zero, and what produced it. A reader
     * that does not recognise a kind drops the frame rather than guessing: a
     * peer running a newer build is ordinary, and so is a frame that is not
     * ours at all.
     */
    val KINDS: Map<Int, String> =
        mapOf(
            COT_TIER2 to "cot-tier2",
            POSITION_V2 to "position-v2",
            CHAT_V1 to "chat-v1",
            MARKER_V1 to "marker-v1",
        )

    /** The kind byte of a frame, or null if it is empty or unknown. */
    fun kindOf(frame: ByteArray?): Int? {
        val first = frame?.firstOrNull()?.toInt()?.and(0xFF) ?: return null
        return if (first in KINDS) first else null
    }

    /** What produced this frame, for a log line. "unknown" rather than a guess. */
    fun nameOf(frame: ByteArray?): String = KINDS[kindOf(frame)] ?: "unknown"

    init {
        // The invariant the whole scheme rests on, checked where it cannot be
        // forgotten. A collision here is not a failing test somewhere later --
        // it is a marker in the wrong place, in the field.
        require(CotTier2.VERSION.toInt() in KINDS) { "the tier 2 version is not a registered kind" }
        require(PositionCodec.WIRE_VERSION in KINDS) { "the position version is not a registered kind" }
        require(CotChat.VERSION in KINDS) { "the chat version is not a registered kind" }
        require(CotMarker.VERSION in KINDS) { "the marker version is not a registered kind" }
    }
}
