package network.columba.app.service.tak

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agreement with tools/tak_identity.py, against the shared golden vectors. */
class TakIdentityTest {
    private val vectors: JSONObject =
        JSONObject(
            checkNotNull(javaClass.classLoader.getResourceAsStream("tak_native_v1.json"))
                .bufferedReader().use { it.readText() },
        )
    private val identity = vectors.getJSONObject("identity")

    private fun unhex(value: String) =
        ByteArray(value.length / 2) { value.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun `the uid matches the python side`() {
        assertEquals(
            identity.getString("uid"),
            TakIdentity.uidFor(unhex(identity.getString("destination_hash"))),
        )
    }

    @Test
    fun `a uid round trips back to its destination`() {
        val hash = unhex(identity.getString("destination_hash"))
        assertArrayEquals(hash, TakIdentity.destinationFor(TakIdentity.uidFor(hash)))
    }

    @Test
    fun `the uid carries the whole hash, not a truncation of it`() {
        // Pivot 1 of TAKIntegrationPivots.md: the gateway used to build a UID
        // from a 32-bit truncation of a 128-bit hash, which could not be
        // verified and could not be reversed to address the peer. Two
        // destinations differing only in their last byte must not share a UID.
        val hash = unhex(identity.getString("destination_hash"))
        val neighbour = hash.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertTrue(TakIdentity.uidFor(hash) != TakIdentity.uidFor(neighbour))
    }

    @Test
    fun `a foreign uid is not mistaken for ours`() {
        // A real TAK network is full of UIDs this project never issued.
        // Guessing rather than returning null is what makes a stranger's
        // marker look like a peer we can address.
        for (foreign in listOf(
            "ANDROID-7819dadfcf858641",
            "66d6de40-bd62-4a5e-92ef-d4ee14b0194a",
            "urtn-",
            "urtn-00",
            "urtn-" + "zz".repeat(16),
            "urtn-" + "00".repeat(15),
            "urtn-" + "00".repeat(17),
        )) {
            assertNull(foreign, TakIdentity.destinationFor(foreign))
        }
        assertNull(TakIdentity.destinationFor(null))
    }

    @Test
    fun `a hash of the wrong length is refused rather than padded`() {
        assertTrue(runCatching { TakIdentity.uidFor(ByteArray(8)) }.isFailure)
        assertTrue(runCatching { TakIdentity.uidFor(ByteArray(0)) }.isFailure)
    }

    @Test
    fun `the announce payload matches the python side`() {
        assertEquals(
            identity.getString("announce"),
            TakIdentity.announcePayload("RAD-BE13B3", "Cyan", "Team Member")
                .joinToString("") { "%02x".format(it) },
        )
    }

    @Test
    fun `an announce round trips`() {
        val claims = TakIdentity.parseAnnounce(
            TakIdentity.announcePayload("RAD-BE13B3", "Cyan", "Team Member"),
        )
        assertEquals("RAD-BE13B3", claims?.callsign)
        assertEquals("Cyan", claims?.team)
        assertEquals("Team Member", claims?.role)
    }

    @Test
    fun `a callsign that is not ascii survives the announce`() {
        // The operators are Turkish; a callsign is the first place that shows.
        val claims = TakIdentity.parseAnnounce(
            TakIdentity.announcePayload("Göktürk", "Cyan", "Team Member"),
        )
        assertEquals("Göktürk", claims?.callsign)
    }

    @Test
    fun `an announce that is not ours is not an error`() {
        // Announces arrive from every node on the mesh, including ones running
        // other software. A payload that is not ours is ordinary.
        for (foreign in listOf(
            null,
            ByteArray(0),
            ByteArray(5),
            byteArrayOf(99, 1, 1, 65, 66, 67),
            byteArrayOf(1, 0, 1, 65, 66, 67),
            byteArrayOf(1, 1, 1, 65),
        )) {
            assertNull(TakIdentity.parseAnnounce(foreign))
        }
    }

    @Test
    fun `an announce carrying invalid utf8 is refused, not substituted`() {
        // Kotlin substitutes U+FFFD for invalid UTF-8 rather than throwing, so
        // a malformed announce would otherwise arrive as plausible text and be
        // shown to an operator as somebody's callsign.
        val bad = byteArrayOf(1, 2, 4) + byteArrayOf(-1, -2) + "Cyan".toByteArray() +
            "Team Member".toByteArray()
        assertNull(TakIdentity.parseAnnounce(bad))
    }
}
