package network.columba.app.service.tak

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agreement with tools/tak_groups.py, which is the only thing that matters.
 *
 * Every derivation here is a pure function of the team name and the fleet
 * secret, and a side that computes any of them differently joins a team of
 * one: it broadcasts happily, hears nothing, and reports no error at all.
 */
class TakGroupsTest {
    private val vectors: JSONObject =
        JSONObject(
            checkNotNull(javaClass.classLoader.getResourceAsStream("tak_native_v1.json"))
                .bufferedReader().use { it.readText() },
        )
    private val groups = vectors.getJSONObject("groups")

    private val secret get() = groups.getString("secret").toByteArray(Charsets.UTF_8)
    private val team get() = groups.getString("team")

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun `the group key matches the python side`() {
        assertEquals(groups.getString("group_key"), TakGroups.groupKey(team, secret).hex())
    }

    @Test
    fun `the identity key matches the python side`() {
        assertEquals(
            groups.getString("identity_key"),
            TakGroups.groupIdentityKey(team, secret).hex(),
        )
    }

    @Test
    fun `the aspect label matches the python side`() {
        // SHA-256 here where the keys use SHA-512, because that is what the
        // Python side does. The wrong hash yields a perfectly plausible aspect
        // that simply addresses a different destination.
        assertEquals(groups.getString("aspect"), TakGroups.groupAspects(team, secret).last())
    }

    @Test
    fun `the group key is 64 bytes, not 32`() {
        // RNS's Token picks its cipher from key length and accepts 32 without
        // complaint, taking AES-128-CBC. Team traffic should not be weakened
        // by omission.
        assertEquals(64, TakGroups.groupKey(team, secret).size)
    }

    @Test
    fun `the team name is not in the aspects in the clear`() {
        // A GROUP destination's address is derived from app name and aspects
        // alone and is visible to anyone who can hear an announce. A literal
        // team name would let a passive listener enumerate the teams on the
        // mesh and count each one's traffic.
        val aspects = TakGroups.groupAspects(team, secret)
        assertTrue(aspects.none { it.contains(team, ignoreCase = true) })
    }

    @Test
    fun `case and surrounding space do not fork a team in half`() {
        assertEquals(groups.getString("normalised"), TakGroups.normaliseTeam(" CYAN "))
        for (spelling in listOf("Cyan", "cyan", " Cyan ", "CYAN", "cYaN")) {
            assertArrayEquals(
                "spelled $spelling",
                TakGroups.groupKey(team, secret),
                TakGroups.groupKey(spelling, secret),
            )
        }
    }

    @Test
    fun `different teams and different secrets do not collide`() {
        assertNotEquals(
            TakGroups.groupKey(team, secret).hex(),
            TakGroups.groupKey("Magenta", secret).hex(),
        )
        assertNotEquals(
            TakGroups.groupKey(team, secret).hex(),
            TakGroups.groupKey(team, ByteArray(32) { 9 }).hex(),
        )
    }

    @Test
    fun `the group key and the identity key are not the same key`() {
        // Both are HMACs of the same secret over the same team, separated only
        // by the domain info string. If that separation were dropped the
        // symmetric traffic key and the identity's private key would be one
        // value, and anyone in the team could sign as the team.
        assertNotEquals(
            TakGroups.groupKey(team, secret).hex(),
            TakGroups.groupIdentityKey(team, secret).hex(),
        )
    }

    @Test
    fun `whitespace around the secret does not change the key`() {
        // The Python side strips ASCII whitespace however the secret arrived:
        // a file written with `echo` carries a trailing newline, and an
        // operator pasting the same characters into a phone may add a space.
        val plain = groups.getString("secret")
        for (padded in listOf(plain, " $plain", "$plain\n", "\t $plain \r\n")) {
            assertArrayEquals(
                "padded as ${padded.replace("\n", "\\n")}",
                TakGroups.groupKey(team, secret),
                TakGroups.groupKey(team, TakGroups.secretBytes(padded)),
            )
        }
    }

    @Test
    fun `a non breaking space is not stripped from the secret`() {
        // String.trim() is Unicode-aware and would remove it; bytes.strip() on
        // the Python side would not. Stripping it here would derive a key the
        // rest of the fleet does not have.
        val padded = groups.getString("secret") + "\u00A0"
        assertNotEquals(
            TakGroups.groupKey(team, secret).hex(),
            TakGroups.groupKey(team, TakGroups.secretBytes(padded)).hex(),
        )
    }

    @Test
    fun `a short secret and an empty team are refused`() {
        assertTrue(runCatching { TakGroups.secretBytes("too short") }.isFailure)
        assertTrue(runCatching { TakGroups.normaliseTeam("   ") }.isFailure)
        assertTrue(runCatching { TakGroups.groupKey(team, ByteArray(15)) }.isFailure)
    }

    /**
     * The card gates Save on this, and the derivation gates the key on
     * secretBytes. Counting trimmed characters instead disabled Save on a
     * multibyte secret that was comfortably long enough in bytes.
     */
    @Test
    fun `secretIsUsable agrees with secretBytes on a multibyte secret`() {
        // Ten characters, thirty bytes: short by character count, fine by bytes.
        val multibyte = "\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9\u00e9"
        assertTrue("ten two-byte characters is twenty bytes", multibyte.length < TakGroups.MIN_SECRET_BYTES)

        assertTrue(TakGroups.secretIsUsable(multibyte))
        assertEquals(20, TakGroups.secretBytes(multibyte).size)
    }

    @Test
    fun `secretIsUsable refuses what secretBytes refuses`() {
        val tooShort = "short"

        assertFalse(TakGroups.secretIsUsable(tooShort))
        assertTrue(
            runCatching { TakGroups.secretBytes(tooShort) }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    /**
     * Unicode whitespace is not stripped by the derivation, so it must not be
     * stripped by the check either -- otherwise the card and the key disagree
     * about what the operator typed.
     */
    @Test
    fun `a non-breaking space counts toward the secret on both paths`() {
        // Fifteen ASCII characters plus a non-breaking space: String.trim()
        // would remove it and call this too short.
        val padded = "abcdefghijklmno\u00a0"

        assertTrue(TakGroups.secretIsUsable(padded))
        assertEquals(TakGroups.secretBytes(padded).size, padded.toByteArray(Charsets.UTF_8).size)
    }
}
