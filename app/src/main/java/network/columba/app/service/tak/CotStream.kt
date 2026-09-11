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
        // One decision per pass, taken by locate(), so the loop itself holds no
        // control flow beyond "stop when nothing more can be decided". Adding a
        // new reason to reject an event is a Span case and a branch here, not
        // another continue threaded through the scan.
        while (true) {
            when (val span = locate()) {
                is Span.Incomplete -> break
                is Span.Oversized -> resynchronise()
                is Span.Complete -> takeEvent(span.end)?.let(events::add)
            }
        }
        return events
    }

    /** What the buffer currently holds, as far as the framing can tell. */
    private sealed interface Span {
        /** A whole event, within the cap, ending at [end]. */
        data class Complete(val end: Int) : Span

        /** An event past the cap, terminated or not. Its bytes stay put. */
        data object Oversized : Span

        /** Nothing decidable until more bytes arrive. */
        data object Incomplete : Span
    }

    /**
     * Find the next event in the buffer and classify it.
     *
     * Consumes only what can never become an event -- the noise before a start
     * tag, and a stale fragment when no start tag is present at all. Everything
     * else is left for the caller to act on, so the decision and the disposal
     * stay in one place each.
     */
    private fun locate(): Span {
        val start = buffer.indexOf(EVENT_OPEN)
        if (start < 0) {
            // Nothing resembling an event yet. Keep only a fragment that
            // could still become one, so noise cannot accumulate.
            if (buffer.length > EVENT_OPEN.length) {
                buffer.delete(0, buffer.length - EVENT_OPEN.length)
            }
            return Span.Incomplete
        }
        // Discard whatever preceded the event: XML declarations,
        // whitespace between documents, or an event we already gave up on.
        if (start > 0) buffer.delete(0, start)
        val close = buffer.indexOf(EVENT_CLOSE)
        if (close < 0) {
            // An unterminated event that has outgrown the cap will never
            // become valid, so it is oversized now rather than pending.
            return if (buffer.length > maxEventBytes) Span.Oversized else Span.Incomplete
        }
        // The cap applies to finished events too. Checking it only while
        // waiting for a closing tag let a peer carry one past the bound simply
        // by closing it: the event was oversized all along, arrived complete,
        // and was decoded and forwarded to every connected client.
        val end = close + EVENT_CLOSE.length
        return if (end > maxEventBytes) Span.Oversized else Span.Complete(end)
    }

    /**
     * Remove the event ending at [end] and decode it, or null if it is not
     * valid UTF-8.
     *
     * Strictly. String(bytes, UTF_8) substitutes U+FFFD for malformed input, so
     * one bad byte inside an otherwise valid event became a replacement
     * character that passed every later check and reached the mesh as content
     * nobody sent. Counted, never repaired.
     */
    private fun takeEvent(end: Int): String? {
        val bytes = buffer.substring(0, end).toByteArray(Charsets.ISO_8859_1)
        buffer.delete(0, end)
        val text = String(bytes, Charsets.UTF_8)
        if (!text.toByteArray(Charsets.UTF_8).contentEquals(bytes)) {
            malformed++
            return null
        }
        return text
    }

    /**
     * Drop the current event and pick up at the next one that starts.
     *
     * Not a clear(): the bytes after a runaway document are as likely to begin
     * a good event as anything else, and discarding them means one malformed
     * document costs every event that shared a read with it.
     *
     * Searches from index 1, so it always makes progress -- the start tag at
     * index 0 is the one being abandoned. This is also why a terminated
     * oversized span is resynchronised from inside rather than deleted whole:
     * the usual way to get one is an unterminated event followed by a real
     * one, where the closing tag belongs to the second and dropping the span
     * would take the good event with it.
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
