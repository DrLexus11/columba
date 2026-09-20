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
 *   fix -- it will never come free. The contract is one way round only: this
 *   endpoint is the server, ATAK is the client. Whichever binds first wins, so
 *   anything else listening here locks the endpoint out for as long as it
 *   runs.
 *
 * The case that produced this was the endpoint sitting on 8087, which is
 * ATAK's own default CoT input: ATAK held the port with a connection open from
 * itself to itself, and closing Columba handed it over for good. The port moved
 * to 18087 for that reason, so the remaining causes are a stale instance of
 * this app or another CoT tool -- but the advice is the same either way, and
 * the distinction below is what decides whether to wait.
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
                "Another server already holds $host:$port, and waiting will not clear " +
                    "it. This endpoint has to be the server and ATAK the client, so " +
                    "whatever is listening there has to stop -- check for a second copy " +
                    "of this app, or a CoT tool configured to listen rather than connect. " +
                    "ATAK should have an outgoing connection to $host:$port and nothing " +
                    "listening on it."
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
