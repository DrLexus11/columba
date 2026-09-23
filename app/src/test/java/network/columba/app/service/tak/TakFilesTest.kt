package network.columba.app.service.tak

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.LinkSpeedProbeResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Files over the mesh, the receiving half: frames, ATAK's notice, the fetch
 * gate. The Kotlin half of `tests/test_tak_files.py`, with frames asserted
 * against bytes the Python codec produced.
 */
class TakFilesTest {
    @get:Rule val folder = TemporaryFolder()

    private val data = "QuakeMesh D2 vector".toByteArray(Charsets.UTF_8)
    private val hash = "8096a5dc401a94e6166c0fb65a518d1078028a5a5b91e7e324fa2940584c35f7"
    private val deck = ByteArray(16) { 0x33 }
    private val stranger = ByteArray(16) { 0x44 }
    private val inboxOfDeck = ByteArray(16) { 0x55 }

    private fun notice(fileHash: String = hash) =
        "<event version=\"2.0\" uid=\"d8e20920-dff0-4c07-b4f7-0f0f98b9891d\" type=\"b-f-t-r\" " +
            "time=\"2026-09-22T10:39:32.087Z\" start=\"2026-09-22T10:39:32.087Z\" " +
            "stale=\"2026-09-22T10:39:42.087Z\" how=\"h-e\"><point lat=\"41.0\" lon=\"29.0\" hae=\"50\" " +
            "ce=\"nan\" le=\"nan\"/><detail><fileshare filename=\"Recon1.zip\" " +
            "senderUrl=\"https://192.168.240.1:8443/Marti/api/sync/metadata/$fileHash/tool\" " +
            "sizeInBytes=\"${data.size}\" sha256=\"$fileHash\" senderUid=\"ANDROID-0000000000000000\" " +
            "senderCallsign=\"SENDER\" name=\"Recon1\"/><marti><dest callsign=\"NEXUS\"/></marti></detail></event>"

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `frames match the deck byte for byte`() {
        assertArrayEquals(hex("06$hash"), TakFiles.encodeRequest(hash))
        assertArrayEquals(
            hex("07${hash}0a5265636f6e312e7a69705175616b654d65736820443220766563746f72"),
            TakFiles.encodeFile(hash, "Recon1.zip", data),
        )
        assertEquals(hash, TakFiles.decodeRequest(TakFiles.encodeRequest(hash)))
        val file = TakFiles.decodeFile(TakFiles.encodeFile(hash, "Recon1.zip", data))!!
        assertEquals("Recon1.zip", file.name)
        assertArrayEquals(data, file.data)
    }

    @Test
    fun `a file that is not what its hash names is refused`() {
        val frame = TakFiles.encodeFile(hash, "Recon1.zip", data)
        frame[frame.size - 1] = (frame[frame.size - 1].toInt() xor 0xFF).toByte()
        assertNull(TakFiles.decodeFile(frame))
    }

    @Test
    fun `atak's notice is read and rewritten to point here`() {
        val found = TakFiles.parseNotice(notice())!!
        assertEquals(hash, found.hash)
        assertEquals("Recon1.zip", found.filename)
        assertNull(TakFiles.parseNotice(notice().replace("b-f-t-r", "u-d-f")))

        val url = TakFiles.contentUrl("http://127.0.0.1:18080", hash)
        val rewritten = TakFiles.rewriteNotice(notice(), url, nowMs = 1_790_000_000_000)
        assertTrue(rewritten.contains("senderUrl=\"$url\""))
        assertFalse("the sender's host is not reachable from here", rewritten.contains("192.168.240.1"))
        assertFalse("ATAK's ten-second stale is gone", rewritten.contains("stale=\"2026-09-22T10:39:42.087Z\""))
    }

    // ---- the fetch gate ----

    private val rnsCore = mockk<RnsCore>()
    private val carrier = mockk<TakLxmf.Carrier>()
    private val toAtak = mutableListOf<String>()

    private val identity = Identity(ByteArray(16) { 0x01 }, ByteArray(64) { 0x02 }, null)

    private var now = 1_790_000_000_000L

    private fun transfers(fastPath: Boolean, sender: ByteArray = deck, ourUid: String = ""): TakFileTransfers {
        coEvery { rnsCore.recallIdentity(any()) } returns identity
        coEvery { rnsCore.createDestination(any(), any(), any(), any(), any()) } returns
            Result.success(
                Destination(sender, "", identity, Direction.OUT, DestinationType.SINGLE, "rnstransport", listOf("tak", "node")),
            )
        coEvery { carrier.inboxFor(any()) } returns inboxOfDeck
        coEvery { carrier.send(any(), any(), any(), any(), any()) } returns "sent"
        coEvery { carrier.sendFile(any(), any()) } answers {
            sentFiles += firstArg<ByteArray>() to secondArg<ByteArray>()
            "sent"
        }
        coEvery { rnsCore.probeLinkSpeed(any(), any(), any()) } returns
            LinkSpeedProbeResult(
                status = "success", establishmentRateBps = 40_000, expectedRateBps = null,
                rttSeconds = if (fastPath) 0.04 else 1.8, hops = 2, linkReused = false,
            )
        store = TakFileStore(folder.newFolder())
        return TakFileTransfers(
            store, TakFileServer(store), rnsCore, carrier, { toAtak += String(it, Charsets.UTF_8) },
            TakFileTransfers.Team(ourUid = ourUid, nameOf = { if (it.contentEquals(deck)) "DECK" else "LEXUS" }),
            clock = { now },
        )
    }

    private lateinit var store: TakFileStore
    private val sentFiles = mutableListOf<Pair<ByteArray, ByteArray>>()

    @Test
    fun `over a fast path the file is asked for, and ATAK sees nothing until it is here`() =
        runTest {
            val files = transfers(fastPath = true)
            assertTrue(files.intercept(notice().toByteArray(), sourceHash = inboxOfDeck))

            coVerify { carrier.send(deck, TakFiles.encodeRequest(hash), "", propagate = false) }
            assertTrue("ATAK is not offered what it cannot fetch yet", toAtak.isEmpty())

            files.onFile(TakLxmf.Inbound(inboxOfDeck, TakFiles.encodeFile(hash, "Recon1.zip", data)))

            assertEquals(1, toAtak.size)
            assertTrue(toAtak.single().contains("http://127.0.0.1:8080/Marti/sync/content?hash=$hash"))
            assertEquals(0, files.waiting())
        }

    @Test
    fun `over a slow path the file waits and nothing is asked for`() =
        runTest {
            val files = transfers(fastPath = false)
            files.intercept(notice().toByteArray(), sourceHash = inboxOfDeck)

            coVerify(exactly = 0) { carrier.send(any(), any(), any(), any(), any()) }
            assertEquals(1, files.waiting())
            assertTrue(toAtak.isEmpty())
        }

    @Test
    fun `a file nobody asked for is discarded`() =
        runTest {
            val files = transfers(fastPath = true)
            files.onFile(TakLxmf.Inbound(inboxOfDeck, TakFiles.encodeFile(hash, "Recon1.zip", data)))
            assertTrue(toAtak.isEmpty())
        }

    @Test
    fun `a file from someone other than its sender is discarded`() =
        runTest {
            val files = transfers(fastPath = true)
            files.intercept(notice().toByteArray(), sourceHash = inboxOfDeck)
            // The same inbox now resolves to a different node.
            coEvery { rnsCore.createDestination(any(), any(), any(), any(), any()) } returns
                Result.success(
                    Destination(stranger, "", identity, Direction.OUT, DestinationType.SINGLE, "rnstransport", listOf("tak", "node")),
                )
            files.onFile(TakLxmf.Inbound(inboxOfDeck, TakFiles.encodeFile(hash, "Recon1.zip", data)))

            assertTrue(toAtak.isEmpty())
            assertEquals(1, files.waiting())
        }

    @Test
    fun `anything that is not a notice passes through`() =
        runTest {
            val files = transfers(fastPath = true)
            assertFalse(files.intercept(notice().replace("b-f-t-r", "u-d-f").toByteArray(), inboxOfDeck))
        }

    // ---- sending: this ATAK's file, asked for by a teammate ----

    @Test
    fun `a teammate the file was offered to is sent it`() =
        runTest {
            val files = transfers(fastPath = true)
            store.put(data, "Recon1.zip", hash)
            files.offered(notice(), listOf(deck))
            files.onRequest(TakLxmf.Inbound(inboxOfDeck, TakFiles.encodeRequest(hash)))

            assertEquals(1, sentFiles.size)
            assertArrayEquals(deck, sentFiles.single().first)
            assertArrayEquals(TakFiles.encodeFile(hash, "Recon1.zip", data), sentFiles.single().second)
        }

    @Test
    fun `a teammate who was not offered it is refused`() =
        runTest {
            val files = transfers(fastPath = true, sender = stranger)
            store.put(data, "Recon1.zip", hash)
            files.offered(notice(), listOf(deck))
            files.onRequest(TakLxmf.Inbound(inboxOfDeck, TakFiles.encodeRequest(hash)))

            assertTrue(sentFiles.isEmpty())
        }

    /**
     * ATAK's calls over real HTTP. Measured on the A54: ATAK checks and uploads
     * on http://127.0.0.1:8080 before it sends a file notice through the stream,
     * and with nothing listening the notice never went.
     */
    @Test
    fun `atak's upload calls are answered`() {
        val served = TakFileStore(folder.newFolder())
        val server = TakFileServer(served, port = 28080)
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
        server.start(scope)
        try {
            Thread.sleep(300)
            assertEquals(404, call("GET", "/Marti/sync/missionquery?hash=$hash").first)

            val boundary = "atakboundary"
            val body =
                (
                    "--$boundary\r\nContent-Disposition: form-data; name=\"assetfile\"; filename=\"Recon1.zip\"\r\n" +
                        "Content-Type: application/octet-stream\r\n\r\n"
                ).toByteArray() + data + "\r\n--$boundary--\r\n".toByteArray()
            val upload =
                call("POST", "/Marti/sync/missionupload?hash=$hash&filename=Recon1.zip", body, "multipart/form-data; boundary=$boundary")
            assertEquals(200, upload.first)
            assertTrue(String(upload.second).contains(hash))

            assertEquals(200, call("PUT", "/Marti/api/sync/metadata/$hash/tool", "x".toByteArray()).first)
            assertEquals(200, call("GET", "/Marti/sync/missionquery?hash=$hash").first)
            assertArrayEquals(data, call("GET", "/Marti/sync/content?hash=$hash").second)

            val tampered = data + byteArrayOf(1)
            assertEquals(400, call("POST", "/Marti/sync/missionupload?hash=$hash", tampered, "application/octet-stream").first)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private fun call(method: String, path: String, body: ByteArray? = null, type: String? = null): Pair<Int, ByteArray> {
        val connection = java.net.URL("http://127.0.0.1:28080$path").openConnection() as java.net.HttpURLConnection
        connection.requestMethod = method
        if (body != null) {
            connection.doOutput = true
            type?.let { connection.setRequestProperty("Content-Type", it) }
            connection.outputStream.use { it.write(body) }
        }
        val code = connection.responseCode
        val bytes = (if (code < 400) connection.inputStream else connection.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return code to bytes
    }

    // ---- saying what ATAK cannot ----

    private val ours = "urtn-" + "44".repeat(16)

    @Test
    fun `a deferred file is announced to this ATAK once, from Columba`() =
        runTest {
            val files = transfers(fastPath = false, ourUid = ours)
            files.intercept(notice().toByteArray(), sourceHash = inboxOfDeck)
            now += TakFileTransfers.MAX_RETRY_MS
            files.retryDue()

            assertEquals("one line, not one per retry", 1, toAtak.size)
            val line = toAtak.single()
            assertTrue(line.contains("Recon1.zip") && line.contains("from DECK is waiting"))
            assertTrue("from Columba, not in the teammate's name", line.contains(TakFiles.STATUS_UID))
        }

    @Test
    fun `files waiting on one sender share one probe`() =
        runTest {
            val files = transfers(fastPath = false)
            files.intercept(notice().toByteArray(), sourceHash = inboxOfDeck)
            val other = TakFiles.sha256Hex("another file".toByteArray())
            files.intercept(notice(other).toByteArray(), sourceHash = inboxOfDeck)

            assertEquals(2, files.waiting())
            coVerify(exactly = 1) { rnsCore.probeLinkSpeed(any(), any(), any()) }
        }

    @Test
    fun `a sender is told when an offer goes unfetched`() =
        runTest {
            val files = transfers(fastPath = true, ourUid = ours)
            store.put(data, "Recon1.zip", hash)
            files.offered(notice(), listOf(stranger))
            files.retryDue()
            assertTrue("not before a minute", toAtak.isEmpty())

            now += TakFileTransfers.UNFETCHED_MS + 1
            files.retryDue()
            files.retryDue()
            assertEquals(1, toAtak.size)
            assertTrue(toAtak.single().contains("not fetched yet by LEXUS"))
        }

    @Test
    fun `an offer that is fetched says nothing`() =
        runTest {
            val files = transfers(fastPath = true, ourUid = ours)
            store.put(data, "Recon1.zip", hash)
            files.offered(notice(), listOf(deck))
            files.onRequest(TakLxmf.Inbound(inboxOfDeck, TakFiles.encodeRequest(hash)))
            now += TakFileTransfers.UNFETCHED_MS + 1
            files.retryDue()
            assertTrue(toAtak.isEmpty())
        }
}
