package network.columba.app.service.tak

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A file fetched a part at a time, each timed: a setup part, then a sample
 * that is judged. The Kotlin half of `PartsTests` in
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
        assertEquals(TakFileParts.SETUP_PART_BYTES, v.getInt("setup_part_bytes"))
        assertEquals(TakFileParts.SAMPLE_PART_BYTES, v.getInt("sample_part_bytes"))
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

    @Test
    fun `status lines match the deck word for word`() {
        val cases = v.getJSONArray("status_lines")
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val line =
                when (case.getString("kind")) {
                    "held" ->
                        TakStatusLines.heldLine(
                            case.getString("filename"), case.getLong("size"), case.getString("from"),
                            case.getString("reason"), case.getBoolean("preview"),
                        )
                    "slow_reason" -> TakStatusLines.slowReason(case.getLong("seconds_left"))
                    else -> TakStatusLines.unfetchedLine(case.getString("filename"), case.getLong("size"), case.getString("by"))
                }
            assertEquals(case.getString("line"), line)
        }
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
        coEvery { rnsCore.hasPath(any()) } returns true
        coEvery { rnsCore.requestPath(any()) } returns Result.success(Unit)
        coEvery { rnsCore.getHopCount(any()) } returns 1
        coEvery { rnsCore.getNextHopInterfaceName(any()) } returns "BLEPeerInterface[DECK]"
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
            assertEquals(TakFileParts.SETUP_PART_BYTES, requests.single().length)
            partArrives(files, 100)
            assertEquals(TakFileParts.SAMPLE_PART_BYTES, requests.last().length)
            while (files.waiting() > 0) partArrives(files, 300)
            assertArrayEquals(big, store.read(bigHash))
            assertEquals(0L, store.partialSize(bigHash))
            assertTrue(toAtak.any { "b-f-t-r" in it })
        }

    /**
     * Measured 2026-09-26: 64 KB in 16.4 s over one BLE hop, link setup
     * included, read as ~32 kbit/s and the file deferred.
     */
    @Test
    fun `the setup part is not judged`() =
        runTest {
            val files = transfers()
            offered(files)
            partArrives(files, 30_000)
            assertEquals("the sample is asked for regardless", 2, requests.size)
        }

    private suspend fun sampled(files: TakFileTransfers, sampleMs: Long) {
        partArrives(files, 3_000)
        partArrives(files, sampleMs)
    }

    private val sampledBytes = (TakFileParts.SETUP_PART_BYTES + TakFileParts.SAMPLE_PART_BYTES).toLong()

    @Test
    fun `a slow sample pauses and says why`() =
        runTest {
            val files = transfers()
            offered(files)
            sampled(files, 20_000)
            assertEquals("no part after the sample", 2, requests.size)
            assertEquals(sampledBytes, store.partialSize(bigHash))
            assertTrue(toAtak.any { "slow path ~" in it })
        }

    @Test
    fun `a route measured slow is not sampled again`() =
        runTest {
            val files = transfers()
            offered(files)
            sampled(files, 20_000)
            now += TakFileParts.FETCH_BUDGET_MS + TakFileTransfers.MAX_RETRY_MS
            files.retryDue()
            assertEquals("nothing spent on air", 2, requests.size)
        }

    @Test
    fun `a changed route is sampled and resumes where it stopped`() =
        runTest {
            val files = transfers()
            offered(files)
            sampled(files, 20_000)
            coEvery { rnsCore.getNextHopInterfaceName(any()) } returns "TCPInterface[Columba LAN]"
            now += TakFileParts.FETCH_BUDGET_MS + TakFileTransfers.MAX_RETRY_MS
            files.retryDue()
            assertEquals(sampledBytes, requests.last().offset)
            assertEquals(TakFileParts.SETUP_PART_BYTES, requests.last().length)
        }

    @Test
    fun `the same route is sampled again after a while`() =
        runTest {
            val files = transfers()
            offered(files)
            sampled(files, 20_000)
            now += TakFileParts.RESAMPLE_MS + 1
            files.retryDue()
            assertEquals(3, requests.size)
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

    // Review note 4: only the part asked for, while it is still waited for.

    @Test
    fun `a part of another length, or claiming another size, is not kept`() =
        runTest {
            val files = transfers()
            offered(files)
            files.onPart(TakLxmf.Inbound(inbox, TakFileParts.encodePart(bigHash, 0, big.size.toLong(), big.copyOfRange(0, 1000))))
            files.onPart(
                TakLxmf.Inbound(
                    inbox,
                    TakFileParts.encodePart(bigHash, 0, big.size * 4L, big.copyOfRange(0, TakFileParts.SETUP_PART_BYTES)),
                ),
            )
            assertEquals(0L, store.partialSize(bigHash))
        }

    @Test
    fun `a late part after the transfer paused is not kept`() =
        runTest {
            val files = transfers()
            offered(files)
            sampled(files, 20_000)
            val had = store.partialSize(bigHash)
            files.onPart(
                TakLxmf.Inbound(
                    inbox,
                    TakFileParts.encodePart(bigHash, had, big.size.toLong(), big.copyOfRange(had.toInt(), had.toInt() + TakFileParts.PART_BYTES)),
                ),
            )
            assertEquals("paused: nothing outstanding to accept", had, store.partialSize(bigHash))
        }

    // Review note 12: the retry tick and an arriving part must not both ask.

    /**
     * An arriving part asks for the next one while holding the file's lock,
     * and the send suspends. The retry tick fires in that window, due by the
     * clock. Unserialised, both reached requestPart and asked for the same
     * offset -- one answer then thrown away by appendPartial, a whole direct
     * LXMF transfer wasted. Now the tick waits for the lock, finds the part
     * already asked for, and asks nothing.
     */
    @Test
    fun `a retry tick during a part request does not ask for the same part again`() =
        runTest {
            val files = transfers()
            offered(files)
            val gate = CompletableDeferred<Unit>()
            coEvery { carrier.send(any(), any(), any(), any(), any()) } coAnswers {
                TakFileParts.decodeRequest(secondArg())?.let { requests += it }
                gate.await()
                "sent"
            }
            val first = requests.single()
            now += 300
            val arriving =
                launch {
                    files.onPart(
                        TakLxmf.Inbound(
                            inbox,
                            TakFileParts.encodePart(bigHash, 0, big.size.toLong(), big.copyOfRange(0, first.length)),
                        ),
                    )
                }
            runCurrent()
            assertEquals("the second part is being asked for", 2, requests.size)

            // Past the first request's budget, so the tick is due by the clock.
            now += TakFileParts.FETCH_BUDGET_MS + 1_000
            val tick = launch { files.retryDue() }
            runCurrent()
            gate.complete(Unit)
            arriving.join()
            tick.join()

            assertEquals(
                "the same part was asked for twice",
                1,
                requests.count { it.offset == TakFileParts.SETUP_PART_BYTES.toLong() },
            )
        }
}
