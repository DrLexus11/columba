package network.columba.app.service.tak

/**
 * Reassembles whole CoT events from arbitrary TCP fragments.
 *
 * The Kotlin half of `tools/cot_endpoint.py`. ATAK opens a TCP connection and
 * writes CoT documents back to back with no enclosing root element and no
 * length prefix, so the reader has to find the boundaries itself.
 *
 * A segment can carry half an event, several events, or an event split
 * mid-attribute; all three happen in practice, and treating a read() as a
 * message is the classic way to lose CoT under load.
 */
class CotStream(private val maxEventBytes: Int = MAX_EVENT_BYTES) {
    private val buffer = StringBuilder()

    /** Add received bytes; return the complete events they finished. */
    fun feed(chunk: ByteArray, length: Int = chunk.size): List<String> {
        if (length <= 0) return emptyList()
        // CoT is ASCII-framed: the tags that delimit an event cannot appear
        // inside a multi-byte sequence, so scanning as ISO-8859-1 finds the
        // same boundaries a byte scan would, and every byte survives the round
        // trip to be decoded as UTF-8 once the event is whole. Decoding as
        // UTF-8 here would substitute U+FFFD across a fragment boundary and
        // corrupt any event split mid-character.
        buffer.append(String(chunk, 0, length, Charsets.ISO_8859_1))
        val events = mutableListOf<String>()
        while (true) {
            val start = buffer.indexOf(EVENT_OPEN)
            if (start < 0) {
                // Nothing resembling an event yet. Keep only a fragment that
                // could still become one, so noise cannot accumulate.
                if (buffer.length > EVENT_OPEN.length) {
                    buffer.delete(0, buffer.length - EVENT_OPEN.length)
                }
                break
            }
            // Discard whatever preceded the event: XML declarations,
            // whitespace between documents, or an event we already gave up on.
            if (start > 0) buffer.delete(0, start)
            val close = buffer.indexOf(EVENT_CLOSE)
            if (close < 0) {
                if (buffer.length > maxEventBytes) {
                    // Abandon the oversized event, not the connection and not
                    // whatever followed it. Clearing the buffer threw away any
                    // complete event that shared a read with a runaway one.
                    // Looping rather than breaking drains an oversized read
                    // inside this call; each pass removes at least one byte.
                    resynchronise()
                    continue
                }
                break
            }
            val end = close + EVENT_CLOSE.length
            if (end > maxEventBytes) {
                // The cap has to apply to finished events too. Checking it only
                // while waiting for a closing tag meant a peer could carry one
                // past the bound simply by closing it: the event was oversized
                // all along, but arrived complete, so it was decoded and
                // forwarded to every connected client.
                //
                // Resynchronised from *inside* the span rather than dropping
                // the span whole. The usual way to get here is an unterminated
                // event followed by a real one, where this close tag belongs to
                // the second and the span covers both -- so deleting the span
                // discards the good event along with the runaway, which is the
                // failure the branch above was just fixed for.
                resynchronise()
                continue
            }
            val bytes = buffer.substring(0, end).toByteArray(Charsets.ISO_8859_1)
            buffer.delete(0, end)
            val text = String(bytes, Charsets.UTF_8)
            // Strictly. String(bytes, UTF_8) substitutes U+FFFD for malformed
            // input, so one bad byte inside an otherwise valid event became a
            // replacement character that passed every later check and reached
            // the mesh as content nobody sent. Counted, never repaired.
            if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
                malformed++
                continue
            }
            events.add(text)
        }
        return events
    }

    /**
     * Drop the current event and pick up at the next one that starts.
     *
     * Not a clear(): the bytes after a runaway document are as likely to begin
     * a good event as anything else, and discarding them means one malformed
     * document costs every event that shared a read with it.
     */
    private fun resynchronise() {
        val next = buffer.indexOf(EVENT_OPEN, 1)
        if (next < 0) buffer.setLength(0) else buffer.delete(0, next)
    }

    /** Bytes held awaiting completion. Exposed for tests and diagnostics. */
    val pending: Int get() = buffer.length

    /** Events discarded for not being valid UTF-8. A peer sending them is loud. */
    var malformed: Long = 0L
        private set

    companion object {
        const val EVENT_OPEN = "<event"
        const val EVENT_CLOSE = "</event>"

        /**
         * An unterminated event must not grow without bound. A peer that opens
         * "<event" and never closes it would otherwise consume memory until
         * the process dies, and the largest legitimate CoT seen from ATAK is a
         * 6 KB drawing. On a phone this ceiling matters more than on the deck.
         */
        const val MAX_EVENT_BYTES = 256 * 1024
    }
}
