package network.columba.app.service.tak

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agreement with tools/tak_membership.py, which is what makes a team a team.
 *
 * Every value here is a pure function of the team name and the fleet secret. A
 * side that computes one differently joins a team of one: it announces, hears
 * announces back, matches none of them, and reports no error at all.
 */
class TakMembershipTest {
    private val vectors: JSONObject =
        JSONObject(
            checkNotNull(javaClass.classLoader.getResourceAsStream("tak_native_v1.json"))
                .bufferedReader().use { it.readText() },
        )
    private val membership = vectors.getJSONObject("membership")

    private val secret get() = membership.getString("secret").toByteArray(Charsets.UTF_8)
    private val team get() = membership.getString("team")
    private val other = "a different fleet secret entirely!".toByteArray(Charsets.UTF_8)

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun `the team tag matches the python side`() {
        assertEquals(membership.getString("team_tag"),
                     TakMembership.teamTag(team, secret).hex())
    }

    @Test
    fun `the announce payload matches the python side`() {
        assertEquals(
            membership.getString("payload"),
            TakMembership.memberPayload(
                team, secret,
                membership.getString("callsign"),
                membership.getString("role"),
            ).hex(),
        )
    }

    @Test
    fun `the version and the tag width match the python side`() {
        assertEquals(membership.getInt("version"), TakMembership.VERSION.toInt())
        assertEquals(membership.getInt("team_tag_bytes"), TakMembership.TEAM_TAG_BYTES)
    }

    @Test
    fun `the team name never reaches the air`() {
        // TakGroups goes to some trouble to keep team names off the air. An
        // announce saying team="Cyan" would hand a listener exactly that.
        val payload = TakMembership.memberPayload(team, secret, "LEXUS")
        for (spelling in listOf("Cyan", "cyan", "CYAN")) {
            assertTrue(
                "found $spelling in the payload",
                !payload.toString(Charsets.ISO_8859_1).contains(spelling),
            )
        }
    }

    @Test
    fun `case and spacing do not fork a team`() {
        assertArrayEquals(TakMembership.teamTag(team, secret),
                          TakMembership.teamTag(" CYAN ", secret))
    }

    @Test
    fun `a different team or secret gives a different tag`() {
        assertNotEquals(TakMembership.teamTag(team, secret).hex(),
                        TakMembership.teamTag("Magenta", secret).hex())
        assertNotEquals(TakMembership.teamTag(team, secret).hex(),
                        TakMembership.teamTag(team, other).hex())
    }

    @Test
    fun `a secret too weak for a group key cannot claim membership`() {
        assertTrue(runCatching { TakMembership.teamTag(team, "short".toByteArray()) }.isFailure)
    }

    @Test
    fun `a payload round trips`() {
        val claims = TakMembership.parseMember(
            TakMembership.memberPayload(team, secret, "LEXUS", "Team Lead"))
        assertEquals("LEXUS", claims?.callsign)
        assertEquals("Team Lead", claims?.role)
        assertArrayEquals(TakMembership.teamTag(team, secret), claims?.tag)
    }

    @Test
    fun `a non ascii callsign survives`() {
        // The operators are Turkish; a callsign is the first place that shows.
        val claims = TakMembership.parseMember(
            TakMembership.memberPayload(team, secret, "Göktürk"))
        assertEquals("Göktürk", claims?.callsign)
    }

    @Test
    fun `an announce that is not ours is not an error`() {
        for (foreign in listOf(
            null,
            ByteArray(0),
            ByteArray(TakMembership.HEADER_BYTES),
            byteArrayOf(99) + ByteArray(TakMembership.HEADER_BYTES),
            ByteArray(200) { 0x41 },
        )) {
            assertNull(TakMembership.parseMember(foreign))
        }
    }

    @Test
    fun `a truncated or padded payload is refused`() {
        val payload = TakMembership.memberPayload(team, secret, "LEXUS")
        for (cut in 1 until payload.size) {
            assertNull("cut at $cut", TakMembership.parseMember(payload.copyOfRange(0, cut)))
        }
        assertNull(TakMembership.parseMember(payload + "extra".toByteArray()))
    }

    @Test
    fun `invalid utf8 is refused, not substituted`() {
        val payload = TakMembership.memberPayload(team, secret, "LEXUS").copyOf()
        payload[TakMembership.HEADER_BYTES] = 0xFF.toByte()
        assertNull(TakMembership.parseMember(payload))
    }

    @Test
    fun `an announce from our team makes a member`() {
        val registry = TakMembership.Registry(team, secret)
        val peer = ByteArray(16) { 1 }
        assertEquals(
            TakMembership.Arrival.NEW,
            registry.remember(peer, TakMembership.memberPayload(team, secret, "PEER"), 100),
        )
        assertEquals(1, registry.members(100).size)
    }

    @Test
    fun `another team is not a member and is not paid for`() {
        // Addressed traffic costs a transmission per member, so a stranger in
        // the list is airtime spent on somebody else's exercise.
        val registry = TakMembership.Registry(team, secret)
        assertNull(
            registry.remember(ByteArray(16) { 1 },
                              TakMembership.memberPayload("Magenta", secret, "THEIRS"), 100),
        )
        assertEquals(0, registry.members(100).size)
    }

    @Test
    fun `a forged tag needs the fleet secret`() {
        val registry = TakMembership.Registry(team, secret)
        assertNull(
            registry.remember(ByteArray(16) { 1 },
                              TakMembership.memberPayload(team, other, "IMPOSTOR"), 100),
        )
    }

    @Test
    fun `our own announce is not a member`() {
        // It comes back through Transport like anyone else's. Addressing
        // ourselves would double every marker and feed our own events back
        // into our own endpoint.
        val own = ByteArray(16) { 0xAA.toByte() }
        val registry = TakMembership.Registry(team, secret, ownHash = own)
        assertNull(registry.remember(own, TakMembership.memberPayload(team, secret, "SELF"), 100))
        assertEquals(0, registry.members(100).size)
    }

    @Test
    fun `a second announce from a known member is not a new arrival`() {
        val registry = TakMembership.Registry(team, secret)
        val peer = ByteArray(16) { 1 }
        val payload = TakMembership.memberPayload(team, secret, "PEER")
        assertEquals(TakMembership.Arrival.NEW, registry.remember(peer, payload, 100))
        assertEquals(TakMembership.Arrival.KNOWN, registry.remember(peer, payload, 200))
        assertEquals(1, registry.size)
    }

    @Test
    fun `a member heard again after expiry is a new arrival`() {
        // From their side we may equally have dropped off, so this is worth
        // greeting rather than treating as a refresh.
        val registry = TakMembership.Registry(team, secret)
        val peer = ByteArray(16) { 1 }
        val payload = TakMembership.memberPayload(team, secret, "PEER")
        assertEquals(TakMembership.Arrival.NEW, registry.remember(peer, payload, 0))
        assertEquals(
            TakMembership.Arrival.NEW,
            registry.remember(peer, payload, TakMembership.DEFAULT_EXPIRY_MS + 1),
        )
    }

    @Test
    fun `a member is kept far longer than an announce interval`() {
        // Forgetting a member means silently declining to send to them, and a
        // responder who has gone quiet is exactly who is still worth addressing.
        val registry = TakMembership.Registry(team, secret)
        val peer = ByteArray(16) { 1 }
        registry.remember(peer, TakMembership.memberPayload(team, secret, "PEER"), 0)
        assertEquals(1, registry.members(TakMembership.DEFAULT_EXPIRY_MS - 1).size)
        assertEquals(0, registry.members(TakMembership.DEFAULT_EXPIRY_MS + 1).size)
    }

    @Test
    fun `a burst of new members does not become a burst of announces`() {
        // Ten nodes powering up together would otherwise each announce nine
        // times, which is a lot of the most expensive packet Reticulum has.
        val registry = TakMembership.Registry(team, secret)
        assertTrue(registry.shouldGreet(1000))
        assertTrue(!registry.shouldGreet(1000 + TakMembership.GREET_MIN_INTERVAL_MS - 1))
        assertTrue(registry.shouldGreet(1000 + TakMembership.GREET_MIN_INTERVAL_MS))
    }

    @Test
    fun `a sender id resolves back to a whole destination hash`() {
        // Four bytes of identity is the truncation pivot 1 objected to. It is
        // tolerable only because membership turns it into a lookup key.
        val registry = TakMembership.Registry(team, secret)
        val peer = byteArrayOf(1, 2, 3, 4) + ByteArray(12)
        registry.remember(peer, TakMembership.memberPayload(team, secret, "PEER"), 100)
        val senderId = TakMembership.senderIdFor(peer)
        assertArrayEquals(peer, registry.resolveSenderId(senderId, 100))
    }

    @Test
    fun `a sender id collision resolves to nobody rather than to a guess`() {
        // Attributing one responder's position to another is not a failure to
        // resolve quietly.
        val registry = TakMembership.Registry(team, secret)
        val first = byteArrayOf(1, 2, 3, 4) + ByteArray(12)
        val second = byteArrayOf(1, 2, 3, 4) + ByteArray(12) { 9 }
        val payload = TakMembership.memberPayload(team, secret, "PEER")
        registry.remember(first, payload, 100)
        registry.remember(second, payload, 100)
        assertNull(registry.resolveSenderId(TakMembership.senderIdFor(first), 100))
    }
}
