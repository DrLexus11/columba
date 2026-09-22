package network.columba.app.service.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A marker sent to one person resolves to that person, and to nobody else.
 *
 * The Kotlin half of `tests/test_cot_addressed_marker.py`.
 */
class MarkerAddresseesTest {
    private val team = "Cyan"
    private val secret = "a fleet secret long enough to be used".toByteArray(Charsets.UTF_8)
    private val lexus = ByteArray(16) { 0x11 }
    private val bravo = ByteArray(16) { 0x22 }

    private val registry =
        TakMembership.Registry(team, secret).also {
            it.remember(lexus, TakMembership.memberPayload(team, secret, "LEXUS"), 0)
            it.remember(bravo, TakMembership.memberPayload(team, secret, "BRAVO"), 0)
        }

    private fun marker(dest: String? = null) =
        """<event version="2.0" uid="c4f2e1a0-1111-2222-3333-444455556666" type="a-h-G" """ +
            """time="2026-09-21T10:30:00Z" start="2026-09-21T10:30:00Z" stale="2026-09-22T10:30:00Z">""" +
            """<point lat="41.0151234" lon="28.9791234" hae="12" ce="9999999" le="9999999"/>""" +
            """<detail><contact callsign="Rubble 3"/>""" +
            (dest?.let { "<marti><dest $it/></marti>" } ?: "") +
            "</detail></event>"

    @Test
    fun `a broadcast marker has no addressees`() {
        assertNull(MarkerAddressees.of(marker(), registry, 1))
    }

    @Test
    fun `a marker sent by callsign resolves to that member alone`() {
        val found = MarkerAddressees.of(marker("""callsign="LEXUS""""), registry, 1)

        assertNotNull(found)
        assertEquals(1, found!!.size)
        assertTrue(found[0].contentEquals(lexus))
    }

    @Test
    fun `a marker sent by uid resolves to that member alone`() {
        val uid = TakIdentity.uidFor(bravo)
        val found = MarkerAddressees.of(marker("""uid="$uid" callsign="BRAVO""""), registry, 1)

        assertTrue(found!!.single().contentEquals(bravo))
    }

    @Test
    fun `an addressee nobody on the team carries resolves to nobody, not to everyone`() {
        // Empty, not null: the caller must refuse rather than broadcast, which
        // would deliver a private pin to the whole team.
        assertEquals(emptyList<ByteArray>(), MarkerAddressees.of(marker("""callsign="SOMEONE-ELSE""""), registry, 1))
    }
}
