package network.columba.app.service.tak

/**
 * What the "Columba files" contact says in ATAK. One line each, state first:
 * they are read on a Nexus-sized screen, and a paragraph there is several lines
 * of scrolling (operator, 2026-09-26). Byte-identical with the deck's
 * `tak_files` and pinned in `tak_native_v1.json`.
 */
object TakStatusLines {
    const val REASON_SLOW = "slow path"
    const val REASON_NO_PATH = "no path"
    const val REASON_PART_TIMED_OUT = "part timed out"

    /** The path is slow, and roughly how long the rest would take. */
    fun slowReason(secondsLeft: Long): String = "$REASON_SLOW ~${maxOf(1, secondsLeft / 60)}min"

    /** A file offered to this node is held here until a fast path appears. */
    fun heldLine(filename: String, size: Long, sender: String, reason: String, previewed: Boolean): String =
        "HELD $filename ${TakFiles.sizeText(size).replace(" ", "")} fr $sender - $reason" +
            if (previewed) ". Preview on map" else ""

    /** A file this node offered has not been fetched by a member yet. */
    fun unfetchedLine(filename: String, size: Long, member: String): String =
        "NOT FETCHED $filename ${TakFiles.sizeText(size).replace(" ", "")} by $member - slow path. Still held"
}
