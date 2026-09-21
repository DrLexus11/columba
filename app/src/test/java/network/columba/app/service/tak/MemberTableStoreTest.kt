package network.columba.app.service.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A node that restarts must not be blind to its team.
 *
 * Seen on the bench 2026-09-21: a bridge restarted just after the phone
 * announced knew nobody, dropped the phone's positions as from an unknown node,
 * sent its own to no one, and both ATAKs showed the other offline. The Kotlin
 * half of `SurvivesRestartTests` in the firmware repo.
 */
class MemberTableStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val team = "Cyan"
    private val secret = "a fleet secret long enough to be used".toByteArray(Charsets.UTF_8)
    private val own = ByteArray(16) { 0xAA.toByte() }
    private val lexus = ByteArray(16) { 0x11 }

    private fun registry(team: String = this.team, secret: ByteArray = this.secret) =
        TakMembership.Registry(team, secret, ownHash = own)

    private fun store() = MemberTableStore(File(folder.root, "tak_members.json"))

    private fun heardLexusAt(time: Long) =
        registry().also { it.remember(lexus, TakMembership.memberPayload(team, secret, "LEXUS"), time) }

    @Test
    fun `a restarted node still knows its team`() {
        store().save(heardLexusAt(100))

        val after = registry()
        assertEquals(1, store().load(after, 200))
        assertTrue(after.members(200).single().contentEquals(lexus))
        assertEquals("LEXUS", after.describe(lexus)?.callsign)
    }

    @Test
    fun `a sender id resolves straight after a restart`() {
        // The whole point: the first position after the restart is drawn.
        store().save(heardLexusAt(100))
        val after = registry().also { store().load(it, 200) }

        assertTrue(after.resolveSenderId(TakMembership.senderIdFor(lexus), 200)!!.contentEquals(lexus))
    }

    @Test
    fun `another team or another secret starts empty`() {
        store().save(heardLexusAt(100))

        assertEquals(0, store().load(registry(team = "Red"), 200))
        assertEquals(0, store().load(registry(secret = "a different fleet secret entirely".toByteArray()), 200))
    }

    @Test
    fun `members gone past expiry are not restored`() {
        store().save(heardLexusAt(100))
        assertEquals(0, store().load(registry(), 100 + TakMembership.DEFAULT_EXPIRY_MS + 1))
    }

    @Test
    fun `a first start or a damaged file restores nothing`() {
        assertEquals(0, store().load(registry(), 200))
        File(folder.root, "tak_members.json").writeText("{not json")
        assertEquals(0, store().load(registry(), 200))
    }
}
