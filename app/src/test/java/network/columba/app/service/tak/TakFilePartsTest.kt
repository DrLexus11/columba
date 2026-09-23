package network.columba.app.service.tak

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.model.LinkSpeedProbeResult
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A file fetched a part at a time, each timed. Round-trip time says LoRa is
 * not in the path; it does not say a file will arrive in reasonable time --
 * BLE is short and slow. The Kotlin half of `PartsTests` in
 * `tests/test_tak_files.py`, with frames from `tak_native_v1.json`.
 */
class TakFilePartsTest {
    @get:Rule val folder = TemporaryFolder()

    private val v =
        JSONObject(
            checkNotNull(javaClass.classLoader?.getResourceAsStream("tak_native_v1.json")).bufferedReader().readText(),
        ).getJSONObject("file")

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun `frames and budget match the deck`() {
        assertEquals(TakPayload.FILE_PART_REQUEST_V1, v.getInt("part_request_kind"))
        assertEquals(TakPayload.FILE_PART_V1, v.getInt("part_kind"))
        assertEquals(TakFileParts.FETCH_BUDGET_MS / 1000, v.getLong("fetch_budget_seconds"))
        assertEquals(TakFileParts.FIRST_PART_BYTES, v.getInt("first_part_bytes"))
        assertEquals(TakFileParts.PART_BYTES, v.getInt("part_bytes"))
        val hash = v.getString("hash")
        val request = v.getJSONObject("part_request")
        assertEquals(
            request.getString("frame"),
            TakFileParts.encodeRequest(hash, request.getLong("offset"), request.getInt("length")).hex(),
        )
        val part = v.getJSONObject("part")
        val frame = TakFileParts.encodePart(hash, part.getLong("offset"), part.getLong("total"), hex(part.getString("data")))
        assertEquals(part.getString("frame"), frame.hex())
        val back = TakFileParts.decodePart(frame)!!
        assertEquals(part.getLong("offset"), back.offset)
        assertArrayEquals(hex(part.getString("data")), back.data)
    }

    // ---- fetching ----

    private val big = ByteArray(1024 * 1024) { (it % 251).toByte() }
    private val bigHash = TakFiles.sha256Hex(big)
    private val deck = ByteArray(16) { 0x33 }
    private val inbox = ByteArray(16) { 0x55 }
    private val rnsCore = mockk<RnsCore>()
    private val carrier = mockk<TakLxmf.Carrier>()
    private val requests = mutableListOf<TakFileParts.Request>()
    private val toAtak = mutableListOf<String>()
    private var now = 1_790_000_000_000L
    private lateinit var store: TakFileStore

    private fun transfers(): TakFileTransfers {
        val identity = Identity(ByteArray(16) { 0x01 }, ByteArray(64) { 0x02 }, null)
        coEvery { rnsCore.recallIdentity(any()) } returns identity
        coEvery { rnsCore.createDestination(any(), any(), any(), any(), any()) } returns
            Result.success(Destination(deck, "", identity, Direction.OUT, DestinationType.SINGLE, "rnstransport", listOf("tak", "node")))
        coEvery { carrier.inboxFor(any()) } returns inbox
        coEvery { carrier.send(any(), any(), any(), any(), any()) } answers {
            TakFileParts.decodeRequest(secondArg())?.let { requests += it }
            "sent"
        }
        coEvery { rnsCore.probeLinkSpeed(any(), any(), any()) } returns LinkSpeedProbeResult("success", 40_000, null, 0.02, 1, false)
        store = TakFileStore(folder.newFolder())
        return TakFileTransfers(
            store, TakFileServer(store), rnsCore, carrier, { toAtak += String(it, Charsets.UTF_8) },
            TakFileTransfers.Team(ourUid = "urtn-" + "44".repeat(16), isMember = { true }, nameOf = { "DECK" }),
            clock = { now },
        )
    }

    private suspend fun offered(files: TakFileTransfers) {
        val offer = TakFileOffer.Offer(TakMembership.senderIdFor(deck), bigHash, big.size.toLong(), "Recon9.zip")
        files.onOffer(TakFileOffer.encode(offer), inbox)
    }

    /** The part last asked for arrives after [ms]. */
    private suspend fun partArrives(files: TakFileTransfers, ms: Long) {
        val asked = requests.last()
        now += ms
        val end = (asked.offset + asked.length).toInt()
        files.onPart(
            TakLxmf.Inbound(inbox, TakFileParts.encodePart(bigHash, asked.offset, big.size.toLong(), big.copyOfRange(asked.offset.toInt(), end))),
        )
    }

    @Test
    fun `a fast path fetches every part and the file is whole`() =
        runTest {
            val files = transfers()
            offered(files)
            assertEquals(TakFileParts.FIRST_PART_BYTES, requests.single().length)
            while (files.waiting() > 0) partArrives(files, 300)
            assertArrayEquals(big, store.read(bigHash))
            assertEquals(0L, store.partialSize(bigHash))
            assertTrue(toAtak.any { "b-f-t-r" in it })
        }

    @Test
    fun `a short slow path pauses after the sample and says why`() =
        runTest {
            val files = transfers()
            offered(files)
            partArrives(files, 20_000)
            assertEquals("no second part asked for", 1, requests.size)
            assertEquals(TakFileParts.FIRST_PART_BYTES.toLong(), store.partialSize(bigHash))
            assertTrue(toAtak.any { "would take about" in it })
        }

    @Test
    fun `a paused transfer resumes where it stopped`() =
        runTest {
            val files = transfers()
            offered(files)
            partArrives(files, 20_000)
            now += TakFileParts.FETCH_BUDGET_MS + TakFileTransfers.MAX_RETRY_MS
            files.retryDue()
            assertEquals(TakFileParts.FIRST_PART_BYTES.toLong(), requests.last().offset)
        }

    @Test
    fun `a part from someone other than the sender is not kept`() =
        runTest {
            val files = transfers()
            offered(files)
            coEvery { rnsCore.createDestination(any(), any(), any(), any(), any()) } returns
                Result.success(
                    Destination(ByteArray(16) { 0x66 }, "", Identity(ByteArray(16), ByteArray(64), null), Direction.OUT, DestinationType.SINGLE, "rnstransport", listOf("tak", "node")),
                )
            partArrives(files, 100)
            assertEquals(0L, store.partialSize(bigHash))
        }
}
