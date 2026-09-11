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
                    // Abandon the oversized event rather than the connection: a
                    // peer sending one runaway document should not cost us the
                    // ones that follow it.
                    buffer.setLength(0)
                }
                break
            }
            val end = close + EVENT_CLOSE.length
            events.add(
                String(
                    buffer.substring(0, end).toByteArray(Charsets.ISO_8859_1),
                    Charsets.UTF_8,
                ),
            )
            buffer.delete(0, end)
        }
        return events
    }

    /** Bytes held awaiting completion. Exposed for tests and diagnostics. */
    val pending: Int get() = buffer.length

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
