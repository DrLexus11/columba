package network.columba.app.service.tak

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Where ATAK on this handset downloads a file that crossed the mesh.
 *
 * A received notice is handed to ATAK with its URL rewritten to point here, so
 * ATAK's own "download" works unchanged. Plain HTTP on loopback: the URL is
 * ours to write, ATAK's own peer-to-peer transfers use plain HTTP, and nothing
 * off this device can reach it. One call, `GET /Marti/sync/content?hash=H`;
 * everything else is 404.
 */
class TakFileServer(
    private val store: TakFileStore,
    val port: Int = DEFAULT_PORT,
) {
    companion object {
        private const val TAG = "TakFileServer"
        const val DEFAULT_PORT = 18080
        const val HOST = "127.0.0.1"
        private const val MAX_REQUEST_LINE = 8 * 1024
    }

    /** What a rewritten notice points ATAK at. */
    val base: String get() = "http://$HOST:$port"

    /** Serve until [scope] ends. The socket is closed with it. */
    fun start(scope: CoroutineScope): Job =
        scope.launch(Dispatchers.IO) {
            ServerSocket().use { listener ->
                coroutineContext[Job]?.invokeOnCompletion { runCatching { listener.close() } }
                listener.reuseAddress = true
                listener.bind(InetSocketAddress(InetAddress.getByName(HOST), port), 4)
                Log.i(TAG, "Serving received files to ATAK on $base")
                while (isActive) {
                    val client =
                        try {
                            listener.accept()
                        } catch (_: IOException) {
                            break
                        }
                    launch { client.use { answer(it) } }
                }
            }
        }

    private fun answer(client: Socket) {
        val requestLine = readLine(BufferedInputStream(client.getInputStream())) ?: return
        val parts = requestLine.split(" ")
        val path = parts.getOrNull(1).orEmpty()
        val hash = Regex("[?&]hash=([0-9a-fA-F]{64})").find(path)?.groupValues?.get(1)?.lowercase()
        val data = hash?.takeIf { parts.firstOrNull() == "GET" && path.startsWith("/Marti/sync/content") }
            ?.let { store.read(it) }
        val out = client.getOutputStream()
        if (hash == null || data == null) {
            Log.w(TAG, "ATAK asked for $path, which is not held here")
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
        } else {
            val name = store.nameOf(hash).replace("\"", "")
            Log.i(TAG, "Serving $name (${data.size} bytes) to ATAK")
            out.write(
                (
                    "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                        "Content-Length: ${data.size}\r\n" +
                        "Content-Disposition: attachment; filename=\"$name\"\r\nConnection: close\r\n\r\n"
                ).toByteArray(),
            )
            out.write(data)
        }
        out.flush()
    }

    /** The request line; the headers after it are not needed. */
    private fun readLine(input: BufferedInputStream): String? {
        val line = StringBuilder()
        while (line.length < MAX_REQUEST_LINE) {
            val next = input.read()
            if (next < 0) return null
            if (next == '\n'.code) return line.toString().trimEnd('\r')
            line.append(next.toChar())
        }
        return null
    }
}
