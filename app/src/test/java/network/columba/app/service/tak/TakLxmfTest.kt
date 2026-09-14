package network.columba.app.service.tak

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.DeliveryMethod
import network.columba.app.rns.api.model.MessageReceipt
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.Identity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The LXMF carrier: what a direct message rides, and what it must not claim. */
class TakLxmfTest {
    @Test
    fun `chat uses acknowledged packets with propagation fallback`() = runTest {
        val core = mockk<RnsCore>()
        val router = mockk<RnsLxmf>()
        val identity = mockk<Identity>()
        val destination = mockk<Destination>()
        val memberHash = ByteArray(16) { 1 }
        val inboxHash = ByteArray(16) { 2 }
        val chat = byteArrayOf(3, 1, 2)
        every { destination.hash } returns inboxHash
        coEvery { core.recallIdentity(memberHash) } returns identity
        coEvery { core.createDestination(identity, any(), any(), "lxmf", listOf("delivery")) } returns
            Result.success(destination)
        coEvery {
            router.sendLxmfMessageWithMethod(
                destinationHash = inboxHash,
                content = "probe",
                sourceIdentity = identity,
                deliveryMethod = DeliveryMethod.OPPORTUNISTIC,
                tryPropagationOnFail = true,
                extraFields = TakLxmf.extraFields(chat),
            )
        } returns Result.success(
            MessageReceipt(
                messageHash = byteArrayOf(0xab.toByte(), 0xcd.toByte()),
                timestamp = 0,
                destinationHash = inboxHash,
            ),
        )

        // The hash, not a boolean: it is what a later delivery proof refers
        // to, and how a sender draws its own tick instead of waiting for the
        // far ATAK to send a receipt back across the mesh.
        assertEquals(
            "abcd",
            TakLxmf.Carrier(core, router, identity).send(memberHash, chat, "probe"),
        )
        coVerify(exactly = 1) {
            router.sendLxmfMessageWithMethod(
                destinationHash = inboxHash,
                content = "probe",
                sourceIdentity = identity,
                deliveryMethod = DeliveryMethod.OPPORTUNISTIC,
                tryPropagationOnFail = true,
                extraFields = TakLxmf.extraFields(chat),
            )
        }
    }

    private val vectors: org.json.JSONObject =
        org.json.JSONObject(
            checkNotNull(javaClass.classLoader.getResourceAsStream("tak_native_v1.json"))
                .bufferedReader().use { it.readText() },
        )
    private val lxmf = vectors.getJSONObject("lxmf")

    private val frame = byteArrayOf(3, 0, 0x11, 0x22, 0x33.toByte(), 0xFF.toByte())

    private fun fieldsJson(vararg pairs: Pair<String, String>) =
        pairs.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    // ---- agreement with the Python side ----

    @Test
    fun `the carrier constants match the python side`() {
        // 0xFB/0xFC are LXMF's CUSTOM_TYPE and CUSTOM_DATA: upstream's own
        // pair for an application's payload. Both flat, which is what lets the
        // same shape cross both backends -- and CUSTOM_META (0xFD) is the
        // wrong field besides, being metadata about a message rather than an
        // app's data, and already occupied by Columba's telemetry extras.
        //
        // Asserted against the shared fixture rather than against literals:
        // these are hand-written in two languages, and the fixture is the only
        // thing stopping them drifting apart silently.
        assertEquals(lxmf.getInt("field_custom_type"), TakLxmf.FIELD_CUSTOM_TYPE)
        assertEquals(lxmf.getInt("field_custom_data"), TakLxmf.FIELD_CUSTOM_DATA)
        assertEquals(lxmf.getString("custom_type_tag"), TakLxmf.TAK_CUSTOM_TYPE)
        assertEquals(0xFB, TakLxmf.FIELD_CUSTOM_TYPE)
        assertEquals(0xFC, TakLxmf.FIELD_CUSTOM_DATA)
    }

    @Test
    fun `the type tag is versioned`() {
        // A client that does not know us skips the payload rather than
        // guessing, and a future frame layout gets a new tag instead of
        // pretending to be this one.
        assertTrue(TakLxmf.TAK_CUSTOM_TYPE.endsWith(".v1"))
    }

    @Test
    fun `the inbox aspects are the ones any lxmf client answers on`() {
        assertEquals(lxmf.getString("app_name"), TakLxmf.LXMF_APP_NAME)
        assertEquals(listOf(lxmf.getString("delivery_aspect")), TakLxmf.LXMF_DELIVERY_ASPECTS)
    }

    // ---- outbound ----

    @Test
    fun `the fields carry the tag and the frame, flat`() {
        // Flat values only. Columba pushes raw bytes through this path for
        // voice already, so a ByteArray is known to cross both backends --
        // a nested structure would need backend-private helpers this module
        // cannot reach.
        val fields = TakLxmf.extraFields(frame)
        assertEquals(TakLxmf.TAK_CUSTOM_TYPE, fields[TakLxmf.FIELD_CUSTOM_TYPE])
        assertArrayEquals(frame, fields[TakLxmf.FIELD_CUSTOM_DATA] as ByteArray)
    }

    // ---- inbound ----

    @Test
    fun `a frame is read back from the backends json`() {
        // The backend hex-encodes every byte string and renders each field id
        // as its decimal string, so 0xFC arrives as "252". Both are the
        // backend's convention rather than LXMF's, and reading either wrongly
        // would silently drop every message.
        val json = fieldsJson(
            "251" to TakLxmf.TAK_CUSTOM_TYPE,
            "252" to hex(frame),
        )
        assertArrayEquals(frame, TakLxmf.frameFrom(json))
    }

    @Test
    fun `the field ids are decimal, not hex`() {
        // Hex is the natural guess and is wrong. This is the assertion that
        // catches it: the same payload under hex keys must not resolve.
        val json = fieldsJson("fb" to TakLxmf.TAK_CUSTOM_TYPE, "fc" to hex(frame))
        assertNull(TakLxmf.frameFrom(json))
    }

    @Test
    fun `somebody elses message is not ours`() {
        // The router is shared with the operator's own messaging, so most of
        // what arrives is a real conversation. Claiming one would be worse
        // than missing ours.
        val cases = listOf(
            "" to "no fields at all",
            "{}" to "empty fields",
            "not json" to "malformed",
            fieldsJson("252" to hex(frame)) to "data with no tag",
            fieldsJson("251" to "someone.else.v1", "252" to hex(frame)) to "another app",
            fieldsJson("251" to TakLxmf.TAK_CUSTOM_TYPE) to "tag with no data",
            fieldsJson("251" to TakLxmf.TAK_CUSTOM_TYPE, "252" to "") to "empty data",
            fieldsJson("251" to TakLxmf.TAK_CUSTOM_TYPE, "252" to "zz") to "not hex",
            fieldsJson("251" to TakLxmf.TAK_CUSTOM_TYPE, "252" to "abc") to "odd length",
        )
        for ((json, why) in cases) {
            assertNull(why, TakLxmf.frameFrom(json))
        }
    }

    @Test
    fun `a null fields payload is not an error`() {
        assertNull(TakLxmf.frameFrom(null))
    }

    @Test
    fun `a frame round trips through the fields and back`() {
        // What the send path produces is what the receive path must read. The
        // two are written in different places and this is the only thing
        // holding them together.
        val fields = TakLxmf.extraFields(frame)
        val json = fieldsJson(
            TakLxmf.FIELD_CUSTOM_TYPE.toString() to fields[TakLxmf.FIELD_CUSTOM_TYPE] as String,
            TakLxmf.FIELD_CUSTOM_DATA.toString() to hex(fields[TakLxmf.FIELD_CUSTOM_DATA] as ByteArray),
        )
        assertArrayEquals(frame, TakLxmf.frameFrom(json))
    }

    @Test
    fun `a real chat frame survives the carrier`() {
        // End to end with the codec rather than a synthetic byte string: a
        // carrier that mangles one byte of a frame produces a message that
        // decodes to something else, which is worse than one that fails.
        val chat = CotChat.encode(
            CotChat.KIND_MESSAGE,
            senderId = 0x11223344,
            messageId = "3e80bd07-fdd8-4c2d-aaf4-8119b1f23a56",
            room = "BRAVO",
            text = "meet me at the north gate",
            recipient = "urtn-" + "cd".repeat(16),
            sentUnix = 1789132717L,
        )
        val json = fieldsJson(
            "251" to TakLxmf.TAK_CUSTOM_TYPE,
            "252" to hex(chat),
        )
        val decoded = CotChat.decode(TakLxmf.frameFrom(json))!!
        assertEquals("meet me at the north gate", decoded.text)
        assertEquals("BRAVO", decoded.room)
        assertEquals(1789132717L, decoded.sentUnix)
    }
}
