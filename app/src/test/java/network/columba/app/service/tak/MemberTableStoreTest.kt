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

    /** A snapshot with the right tag and one bad key, as it would be read back. */
    private fun snapshotWith(extraKey: String): String {
        val saved = registry()
        saved.remember(lexus, TakMembership.memberPayload(team, secret, "LEXUS"), 1_000)
        val json = org.json.JSONObject(saved.snapshot())
        json.getJSONObject("members").put(
            extraKey,
            org.json.JSONObject().put("callsign", "BAD").put("role", "X").put("heard", 1_000),
        )
        return json.toString()
    }

    /**
     * A key that is not a destination hash rejects the whole snapshot.
     *
     * It used to go straight into the table, where the next members() call
     * threw from unHex() and the endpoint could not start.
     */
    @Test
    fun `a non hex key restores nothing and leaves the table usable`() {
        val restored = registry()

        assertEquals(0, restored.restore(snapshotWith("not-a-destination-hash!!!!!!!!!"), 2_000))
        // Would have thrown before.
        assertTrue(restored.members(2_000).isEmpty())
    }

    @Test
    fun `a key of the wrong length restores nothing`() {
        val restored = registry()

        assertEquals(0, restored.restore(snapshotWith("abcd"), 2_000))
        assertTrue(restored.members(2_000).isEmpty())
    }

    /**
     * All or none. Entries were inserted inside the loop, so a bad one partway
     * through left the good ones before it restored while the call reported 0.
     */
    @Test
    fun `a damaged snapshot does not restore the entries before the damage`() {
        val restored = registry()

        restored.restore(snapshotWith("zz".repeat(16)), 2_000)

        assertTrue(
            "a good entry survived a restore reported as nothing",
            restored.members(2_000).none { it.contentEquals(lexus) },
        )
    }
}
