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

    private fun transfers(fastPath: Boolean, sender: ByteArray = deck): TakFileTransfers {
        coEvery { rnsCore.recallIdentity(any()) } returns identity
        coEvery { rnsCore.createDestination(any(), any(), any(), any(), any()) } returns
            Result.success(
                Destination(sender, "", identity, Direction.OUT, DestinationType.SINGLE, "rnstransport", listOf("tak", "node")),
            )
        coEvery { carrier.inboxFor(any()) } returns inboxOfDeck
        coEvery { carrier.send(any(), any(), any(), any()) } returns "sent"
        coEvery { rnsCore.probeLinkSpeed(any(), any(), any()) } returns
            LinkSpeedProbeResult(
                status = "success", establishmentRateBps = 40_000, expectedRateBps = null,
                rttSeconds = if (fastPath) 0.04 else 1.8, hops = 2, linkReused = false,
            )
        val store = TakFileStore(folder.newFolder())
        return TakFileTransfers(store, TakFileServer(store), rnsCore, carrier, { toAtak += String(it, Charsets.UTF_8) })
    }

    @Test
    fun `over a fast path the file is asked for, and ATAK sees nothing until it is here`() =
        runTest {
            val files = transfers(fastPath = true)
            assertTrue(files.intercept(notice().toByteArray(), sourceHash = inboxOfDeck))

            coVerify { carrier.send(deck, TakFiles.encodeRequest(hash), "", propagate = false) }
            assertTrue("ATAK is not offered what it cannot fetch yet", toAtak.isEmpty())

            files.onFile(TakLxmf.Inbound(inboxOfDeck, TakFiles.encodeFile(hash, "Recon1.zip", data)))

            assertEquals(1, toAtak.size)
            assertTrue(toAtak.single().contains("http://127.0.0.1:18080/Marti/sync/content?hash=$hash"))
            assertEquals(0, files.waiting())
        }

    @Test
    fun `over a slow path the file waits and nothing is asked for`() =
        runTest {
            val files = transfers(fastPath = false)
            files.intercept(notice().toByteArray(), sourceHash = inboxOfDeck)

            coVerify(exactly = 0) { carrier.send(any(), any(), any(), any()) }
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
}
