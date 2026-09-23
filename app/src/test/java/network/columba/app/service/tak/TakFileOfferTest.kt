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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * The offer that crosses the mesh in place of ATAK's notice. Frames asserted
 * against `tak_native_v1.json`, which the deck's Python codec produced.
 */
class TakFileOfferTest {
    @get:Rule val folder = TemporaryFolder()

    private val v =
        JSONObject(
            checkNotNull(javaClass.classLoader?.getResourceAsStream("tak_native_v1.json")).bufferedReader().readText(),
        ).getJSONObject("file")

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun `frames match the deck byte for byte`() {
        val hash = v.getString("hash")
        assertEquals(TakPayload.FILE_OFFER_V1, v.getInt("offer_kind"))
        assertEquals(TakFileOffer.MAX_OFFER_BYTES, v.getInt("max_offer_bytes"))
        assertEquals(v.getString("request"), TakFiles.encodeRequest(hash).hex())
        assertEquals(
            v.getString("file"),
            TakFiles.encodeFile(hash, v.getString("file_name"), hex(v.getString("file_data"))).hex(),
        )
        val plain = v.getJSONObject("offer_plain")
        val encodedPlain =
            TakFileOffer.encode(
                TakFileOffer.Offer(plain.getInt("sender_id"), hash, plain.getLong("size"), plain.getString("filename")),
            )
        assertEquals(plain.getString("frame"), encodedPlain.hex())
        val quick = v.getJSONObject("offer_quickpic")
        val point =
            TakFileOffer.Point(quick.getInt("lat_e7"), quick.getInt("lon_e7"), UUID.fromString(quick.getString("marker_uid")))
        val offer =
            TakFileOffer.Offer(
                quick.getInt("sender_id"), hash, quick.getLong("size"), quick.getString("filename"), point,
                hex(quick.getString("thumbnail")),
            )
        assertEquals(quick.getString("frame"), TakFileOffer.encode(offer).hex())

        val back = TakFileOffer.decode(hex(quick.getString("frame")))!!
        assertEquals(point, back.point)
        assertArrayEquals(hex(quick.getString("thumbnail")), back.thumbnail)
        assertEquals(quick.getLong("size"), back.size)
    }

    @Test
    fun `malformed offers are refused`() {
        val frame = hex(v.getJSONObject("offer_quickpic").getString("frame"))
        assertNull(TakFileOffer.decode(frame.copyOf(frame.size - 1)))
        assertNull(TakFileOffer.decode(frame + byteArrayOf(1)))
    }

    private fun quickpic(): ByteArray {
        val marker =
            "<event version='2.0' uid='28b63bd7-7802-4802-a724-1adfef6e94e1' type='b-i-x-i'>" +
                "<point lat='40.9547417' lon='29.0934833' hae='95' ce='9999999.0' le='9999999.0'/><detail/></event>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("MANIFEST/manifest.xml"))
            zip.write("<MissionPackageManifest version='2'/>".toByteArray())
            zip.putNextEntry(ZipEntry("28b63bd7/28b63bd7.cot"))
            zip.write(marker.toByteArray())
            zip.putNextEntry(ZipEntry("7e5a/20260922_182231.jpg"))
            zip.write(ByteArray(5000) { 7 })
        }
        return out.toByteArray()
    }

    private val fakeThumb = "RIFF-fake-webp".toByteArray() + ByteArray(500)
    private val fakeThumbnailer: (ByteArray, Int) -> ByteArray? = { _, budget -> fakeThumb.takeIf { it.size <= budget } }

    @Test
    fun `a quickpic offer carries its point and thumbnail within three fragments`() {
        val pkg = quickpic()
        val offer = TakFileOffer.offerFor(7, TakFiles.sha256Hex(pkg), "20260922_182231.jpg.zip", pkg, fakeThumbnailer)
        assertEquals(409547417, offer.point!!.latE7)
        assertArrayEquals(fakeThumb, offer.thumbnail)
        assertTrue(TakFileOffer.encode(offer).size <= TakFileOffer.MAX_OFFER_BYTES)
    }

    @Test
    fun `a preview is the marker under its own uid with the thumbnail`() {
        val pkg = quickpic()
        val offer = TakFileOffer.offerFor(7, TakFiles.sha256Hex(pkg), "20260922_182231.jpg.zip", pkg, fakeThumbnailer)
        val (preview, name) = TakFileOffer.preview(offer, "NEXUS", 1_790_000_000_000)
        assertEquals("20260922_182231_preview.zip", name)
        val entries =
            ZipInputStream(ByteArrayInputStream(preview)).use { zip -> generateSequence { zip.nextEntry }.map { it.name }.toList() }
        assertTrue(entries.contains("28b63bd7-7802-4802-a724-1adfef6e94e1/28b63bd7-7802-4802-a724-1adfef6e94e1.cot"))
        assertTrue(entries.any { it.endsWith("_preview.webp") })
    }

    // ---- receiving an offer ----

    private val deck = ByteArray(16) { 0x33 }
    private val inboxOfDeck = ByteArray(16) { 0x55 }
    private val rnsCore = mockk<RnsCore>()
    private val carrier = mockk<TakLxmf.Carrier>()
    private val toAtak = mutableListOf<String>()
    private val requests = mutableListOf<ByteArray>()

    private fun transfers(rtt: Double): TakFileTransfers {
        val identity = Identity(ByteArray(16) { 0x01 }, ByteArray(64) { 0x02 }, null)
        coEvery { rnsCore.recallIdentity(any()) } returns identity
        coEvery { rnsCore.createDestination(any(), any(), any(), any(), any()) } returns
            Result.success(Destination(deck, "", identity, Direction.OUT, DestinationType.SINGLE, "rnstransport", listOf("tak", "node")))
        coEvery { carrier.inboxFor(any()) } returns inboxOfDeck
        coEvery { carrier.send(any(), any(), any(), any(), any()) } answers {
            requests += secondArg<ByteArray>()
            "sent"
        }
        coEvery { rnsCore.probeLinkSpeed(any(), any(), any()) } returns
            LinkSpeedProbeResult("success", 40_000, null, rtt, 2, false)
        val store = TakFileStore(folder.newFolder())
        return TakFileTransfers(
            store, TakFileServer(store), rnsCore, carrier, { toAtak += String(it, Charsets.UTF_8) },
            TakFileTransfers.Team(ourUid = "urtn-" + "44".repeat(16), isMember = { true }, nameOf = { "DECK" }),
        )
    }

    private fun offerFrame(senderId: Int = TakMembership.senderIdFor(deck)): Pair<ByteArray, String> {
        val pkg = quickpic()
        val hash = TakFiles.sha256Hex(pkg)
        return TakFileOffer.encode(TakFileOffer.offerFor(senderId, hash, "20260922_182231.jpg.zip", pkg, fakeThumbnailer)) to hash
    }

    @Test
    fun `over a fast path the full file is asked for`() =
        runTest {
            val (frame, hash) = offerFrame()
            transfers(rtt = 0.04).onOffer(frame, inboxOfDeck)
            assertEquals(listOf(TakFiles.encodeRequest(hash).hex()), requests.map { it.hex() })
            assertTrue(toAtak.isEmpty())
        }

    @Test
    fun `over a slow path a quickpic is previewed on the map`() =
        runTest {
            transfers(rtt = 1.8).onOffer(offerFrame().first, inboxOfDeck)
            assertTrue(requests.isEmpty())
            val notices = toAtak.filter { "b-f-t-r" in it }
            assertEquals(1, notices.size)
            assertTrue(TakFiles.parseNotice(notices.single())!!.filename.endsWith("_preview.zip"))
            assertTrue(toAtak.any { "A preview is on the map" in it })
        }

    @Test
    fun `an offer claiming someone else is refused`() =
        runTest {
            transfers(rtt = 0.04).onOffer(offerFrame(senderId = 0x0BADF00D).first, inboxOfDeck)
            assertTrue(requests.isEmpty())
            assertTrue(toAtak.isEmpty())
        }
}
