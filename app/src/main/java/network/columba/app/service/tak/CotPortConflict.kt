package network.columba.app.service.tak

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Why the endpoint could not take its port, in terms an operator can act on.
 *
 * "bind failed: EADDRINUSE" is accurate and useless. It was the whole of what
 * the settings screen said for an hour on hardware 2026-09-13 while a message
 * that had crossed three hops sat undelivered, and it named neither the cause
 * nor the cure.
 *
 * The two causes want opposite responses, and telling them apart is the point:
 *
 * - **A previous run has not let go yet.** Transient. Waiting is the fix, and
 *   the retry loop already does it.
 * - **Another server owns the port.** Not transient, and waiting is *not* the
 *   fix -- it will never come free. On this device that means ATAK has an
 *   **input** configured on the same port. The contract is the other way
 *   round: this endpoint is the server, ATAK is the client. Whichever binds
 *   first wins, so closing Columba hands ATAK the port and it does not give it
 *   back.
 *
 * The probe is a connect attempt. Something that accepts a connection is a live
 * server; something that refuses is a socket on its way out.
 */
class CotPortConflict(
    private val probe: suspend (String, Int) -> Boolean = ::accepts,
) {
    enum class Cause { ANOTHER_SERVER, NOT_RELEASED_YET }

    suspend fun diagnose(host: String, port: Int): Cause =
        if (probe(host, port)) Cause.ANOTHER_SERVER else Cause.NOT_RELEASED_YET

    /** What to show an operator who has just found the endpoint stopped. */
    fun explain(cause: Cause, host: String, port: Int): String =
        when (cause) {
            Cause.ANOTHER_SERVER ->
                "Another server already holds $host:$port. This is almost always an " +
                    "ATAK input on the same port. The endpoint has to be the server and " +
                    "ATAK the client: remove that input in ATAK's network connections, " +
                    "leaving only the outgoing connection to $host:$port."
            Cause.NOT_RELEASED_YET ->
                "$host:$port is still held from a previous run. Retrying."
        }

    companion object {
        /** Long enough for loopback, short enough not to stall the retry. */
        const val PROBE_TIMEOUT_MS = 500

        private suspend fun accepts(host: String, port: Int): Boolean =
            withContext(Dispatchers.IO) {
                runCatching {
                    Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS) }
                }.isSuccess
            }
    }
}
