package network.columba.app.service

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.msgpack.core.MessagePack

/**
 * The assertion is produced here and verified on an ESP32 running
 * TimeBeacon.h. Nothing checks that the two agree at runtime: a single byte of
 * disagreement makes every signature fail to verify, and a failed signature is
 * reported as a refused assertion, which looks exactly like an attack. So the
 * agreement is pinned here instead.
 *
 * The golden vector below was produced by the third implementation --
 * tools/time_authority.py in the firmware repository -- so this test compares
 * against something written independently, not against itself.
 */
class TimeAuthorityManagerTest {
    @Test
    fun `signed bytes match the golden vector from the python authority`() {
        val expected =
            ("7572746e2d74696d652d626561636f6e2d7631" + // "urtn-time-beacon-v1"
                "000001a0706f9c3b" + // unix_ms  1788592757819, big-endian
                "00001c20" + // valid_for 7200, big-endian
                "01" + // stratum
                "02" // source: NTP
            ).hexToBytes()

        val actual =
            TimeAuthorityManager.signedBytes(
                unixMillis = 1788592757819L,
                validForSeconds = 7200,
                stratum = 1,
                source = 2,
            )

        assertEquals("signed message must be 33 bytes", 33, actual.size)
        assertArrayEquals(expected, actual)
    }

    @Test
    fun `the domain prefix separates this from the nonce-challenged reply`() {
        val signed = TimeAuthorityManager.signedBytes(1L, 1, 1, 1)
        val domain = signed.copyOfRange(0, 19).toString(Charsets.US_ASCII)
        assertEquals("urtn-time-beacon-v1", domain)
    }

    @Test
    fun `app data carries every field the firmware reads by name`() {
        val signature = ByteArray(64) { it.toByte() }
        val packed =
            TimeAuthorityManager.assertionAppData(
                unixMillis = 1788592757819L,
                validForSeconds = 7200,
                stratum = 1,
                source = 2,
                signature = signature,
            )

        val fields = mutableMapOf<String, Any>()
        MessagePack.newDefaultUnpacker(packed).use { unpacker ->
            val entries = unpacker.unpackMapHeader()
            repeat(entries) {
                when (val key = unpacker.unpackString()) {
                    "g" -> {
                        val length = unpacker.unpackBinaryHeader()
                        fields[key] = unpacker.readPayload(length)
                    }
                    "t" -> fields[key] = unpacker.unpackLong()
                    else -> fields[key] = unpacker.unpackInt()
                }
            }
        }

        assertEquals(setOf("v", "t", "f", "s", "o", "g"), fields.keys)
        assertEquals(1, fields["v"])
        assertEquals(1788592757819L, fields["t"])
        assertEquals(7200, fields["f"])
        assertEquals(1, fields["s"])
        assertEquals(2, fields["o"])
        assertArrayEquals(signature, fields["g"] as ByteArray)
    }

    @Test
    fun `unix millis needs the full 64 bits`() {
        // Packing the timestamp as an int would silently truncate: current
        // epoch milliseconds passed the 32-bit ceiling in 1970 plus 49 days.
        val packed = TimeAuthorityManager.assertionAppData(1788592757819L, 7200, 1, 2, ByteArray(64))
        MessagePack.newDefaultUnpacker(packed).use { unpacker ->
            val entries = unpacker.unpackMapHeader()
            repeat(entries) {
                val key = unpacker.unpackString()
                if (key == "t") {
                    assertEquals(1788592757819L, unpacker.unpackLong())
                    return
                }
                unpacker.skipValue()
            }
        }
        assertTrue("no timestamp field found", false)
    }

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
