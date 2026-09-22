package network.columba.app.service.tak

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * The file side of a TAK server, on this handset, for the ATAK beside it.
 *
 * ATAK connected to Columba's endpoint treats 127.0.0.1 as its TAK server, and
 * before it sends a file through the stream it uploads it here -- on plain
 * HTTP 8080, measured on the A54 2026-09-22: "Check URL is
 * http://127.0.0.1:8080/Marti/s...", and with nothing listening the upload
 * failed and the notice was never sent. The Kotlin twin of
 * `tools/tak_file_service.py`, which the deck reaches through nginx:
 *
 *     GET  /Marti/sync/missionquery?hash=H          200 + URL if held, else 404
 *     POST /Marti/sync/missionupload?hash=H&filename=F   multipart, part `assetfile`
 *     PUT  /Marti/api/sync/metadata/H/tool          tag; accepted and ignored
 *     GET  /Marti/sync/content?hash=H               the file, for this ATAK
 *
 * Loopback only: nothing off this device can reach it.
 */
class TakFileServer(
    private val store: TakFileStore,
    val port: Int = DEFAULT_PORT,
) {
    companion object {
        private const val TAG = "TakFileServer"
        const val DEFAULT_PORT = 8080
        const val HOST = "127.0.0.1"
        private const val MAX_HEADER_LINE = 8 * 1024
        private const val MAX_BODY = 64 * 1024 * 1024
    }

    /** What a rewritten notice, and an upload's reply, point ATAK at. */
    val base: String get() = "http://$HOST:$port"

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

    /** Serve until [scope] ends. The socket is closed with it. */
    fun start(scope: CoroutineScope): Job =
        scope.launch(Dispatchers.IO) {
            ServerSocket().use { listener ->
                coroutineContext[Job]?.invokeOnCompletion { runCatching { listener.close() } }
                listener.reuseAddress = true
                listener.bind(InetSocketAddress(InetAddress.getByName(HOST), port), 4)
                Log.i(TAG, "ATAK's file calls answered on $base")
                while (isActive) {
                    val client =
                        try {
                            listener.accept()
                        } catch (_: IOException) {
                            break
                        }
                    launch { client.use { runCatching { answer(it) }.onFailure { e -> Log.w(TAG, "File call failed", e) } } }
                }
            }
        }

    private fun answer(client: Socket) {
        val request = read(BufferedInputStream(client.getInputStream())) ?: return
        val route = request.path.substringBefore('?')
        val hash = Regex("[?&]hash=([0-9a-fA-F]{64})").find(request.path)?.groupValues?.get(1)?.lowercase()
        val out = client.getOutputStream()
        val reply: Triple<Int, ByteArray, Map<String, String>> =
            when {
                request.method == "GET" && route == "/Marti/sync/missionquery" ->
                    if (hash != null && store.has(hash)) ok(url(hash)) else notFound()
                request.method == "GET" && route == "/Marti/sync/content" -> content(hash)
                request.method == "POST" && route == "/Marti/sync/missionupload" -> upload(request, hash)
                request.method == "PUT" && route.startsWith("/Marti/api/sync/metadata/") -> ok(ByteArray(0))
                else -> {
                    Log.i(TAG, "ATAK asked for $route; not something this serves")
                    notFound()
                }
            }
        val (code, body, headers) = reply
        val status = if (code == 200) "200 OK" else if (code == 400) "400 Bad Request" else "404 Not Found"
        val head = StringBuilder("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
        headers.forEach { (key, value) -> head.append("$key: $value\r\n") }
        out.write(head.append("\r\n").toString().toByteArray())
        out.write(body)
        out.flush()
    }

    private fun url(hash: String) = TakFiles.contentUrl(base, hash).toByteArray()

    private fun ok(body: ByteArray, headers: Map<String, String> = emptyMap()) = Triple(200, body, headers)

    private fun notFound() = Triple(404, ByteArray(0), emptyMap<String, String>())

    private fun content(hash: String?): Triple<Int, ByteArray, Map<String, String>> {
        val data = hash?.let { store.read(it) }
        if (hash == null || data == null) {
            Log.w(TAG, "ATAK asked for a file that is not held here")
            return notFound()
        }
        val name = store.nameOf(hash).replace("\"", "")
        Log.i(TAG, "Serving $name (${data.size} bytes) to ATAK")
        return ok(
            data,
            mapOf("Content-Type" to "application/octet-stream", "Content-Disposition" to "attachment; filename=\"$name\""),
        )
    }

    private fun upload(request: Request, hash: String?): Triple<Int, ByteArray, Map<String, String>> {
        val filename = Regex("[?&]filename=([^&]*)").find(request.path)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }.orEmpty()
        val data = multipartFile(request.headers["content-type"], request.body) ?: request.body
        val stored = store.put(data, filename, hash)
        return if (stored == null) {
            Log.w(TAG, "Upload refused: it is not the file its hash names")
            Triple(400, ByteArray(0), emptyMap())
        } else {
            Log.i(TAG, "ATAK uploaded $filename (${data.size} bytes)")
            ok(url(stored))
        }
    }

    /** The `assetfile` part of a multipart body, or its first part, or null if not multipart. */
    private fun multipartFile(contentType: String?, body: ByteArray): ByteArray? {
        val boundary =
            contentType?.takeIf { it.startsWith("multipart/", ignoreCase = true) }
                ?.let { Regex("boundary=\"?([^\";]+)\"?").find(it)?.groupValues?.get(1) }
                ?: return null
        val delimiter = "--$boundary".toByteArray()
        val parts = mutableListOf<ByteArray>()
        var at = indexOf(body, delimiter, 0)
        while (at >= 0) {
            val start = at + delimiter.size
            val next = indexOf(body, delimiter, start)
            if (next < 0) break
            parts += body.copyOfRange(start, next)
            at = next
        }
        val separator = "\r\n\r\n".toByteArray()
        val files =
            parts.mapNotNull { part ->
                val split = indexOf(part, separator, 0).takeIf { it >= 0 } ?: return@mapNotNull null
                val headers = String(part, 0, split, Charsets.ISO_8859_1)
                // The part ends with CRLF before the next delimiter.
                val end = if (part.size >= 2 && part[part.size - 2] == '\r'.code.toByte()) part.size - 2 else part.size
                headers to part.copyOfRange(split + separator.size, maxOf(split + separator.size, end))
            }
        return (files.firstOrNull { it.first.contains("name=\"assetfile\"") } ?: files.firstOrNull())?.second
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int): Int {
        outer@ for (i in from..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private fun read(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull()?.coerceIn(0, MAX_BODY) ?: 0
        val body = ByteArray(length)
        var got = 0
        while (got < length) {
            val n = input.read(body, got, length - got)
            if (n < 0) break
            got += n
        }
        val parts = requestLine.split(" ")
        return Request(parts.getOrElse(0) { "" }, parts.getOrElse(1) { "" }, headers, body.copyOf(got))
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (line.length < MAX_HEADER_LINE) {
            val next = input.read()
            if (next < 0) return null
            if (next == '\n'.code) return line.toString().trimEnd('\r')
            line.append(next.toChar())
        }
        return null
    }
}
