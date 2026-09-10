package network.columba.app.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsLxmf
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The trust setting decides whose orders this phone will act on, so what
 * matters is that a save actually reaches disk and that the card never shows a
 * key the store does not hold.
 *
 * Writing it is a commit() deliberately -- the dangerous direction is a
 * revocation that does not survive a restart -- so these also pin down that it
 * is a real durable write, not an apply() that reports success it cannot know.
 */
@RunWith(RobolectricTestRunner::class)
class TaskManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = context.getSharedPreferences("tak_task_authorities", Context.MODE_PRIVATE)

    /** An authority key is a 64-byte public key, so 128 hex characters. */
    private val authorityKey = "ab".repeat(64)
    private val ownerHash = ByteArray(16) { 0x11 }
    private val ownerHex = "11".repeat(16)

    private lateinit var core: RnsCore
    private lateinit var lxmf: RnsLxmf
    private lateinit var scope: CoroutineScope
    private lateinit var manager: TaskManager

    @Before
    fun setUp() {
        context.deleteDatabase("tak_tasks.db")
        preferences.edit().clear().commit()

        val identity = Identity(ownerHash, ByteArray(64) { 0x22 }, ByteArray(64) { 0x33 })
        val destination =
            Destination(
                hash = ownerHash,
                hexHash = ownerHex,
                identity = identity,
                direction = Direction.IN,
                type = DestinationType.SINGLE,
                appName = TaskCodec.APP,
                aspects = TaskCodec.ASPECTS,
            )

        // Strict mocks: every call the receiver makes is stubbed below, so an
        // unstubbed one is a change in what TaskManager does, not a silent
        // default.
        core = mockk()
        lxmf = mockk()
        coEvery { lxmf.getLxmfIdentity() } returns Result.success(identity)
        coEvery { core.createDestination(any(), any(), any(), any(), any()) } returns Result.success(destination)
        coEvery { core.announceDestination(any(), any()) } returns Result.success(Unit)
        every { core.observePackets() } returns emptyFlow()

        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        manager = TaskManager(context, core, lxmf, scope)
    }

    @After
    fun tearDown() {
        manager.stop()
        scope.cancel()
    }

    /**
     * The receiver has to be up before a trust setting means anything: owner is
     * derived from the destination, and the key is stored per owner.
     */
    private fun startAndAwaitOwner() {
        manager.start()
        assertTrue("the receiver never produced an owner", await { manager.state.value.owner == ownerHex })
    }

    /** Polls rather than sleeps: the write lands on the IO dispatcher. */
    private fun await(condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun `a trusted authority reaches the store and the state`() {
        startAndAwaitOwner()

        manager.setAuthority(authorityKey)

        assertTrue(
            "the key never reached SharedPreferences",
            await { preferences.getString("authority.$ownerHex", "") == authorityKey },
        )
        assertTrue(
            "the card never caught up with the stored key",
            await { manager.state.value.authority == authorityKey },
        )
    }

    /**
     * The harness reads [TaskManager.state] on the line after it saves, so the
     * returned Job has to be joinable and has to mean the write is done.
     */
    @Test
    fun `joining the returned job makes the write observable`() =
        runBlocking {
            startAndAwaitOwner()

            manager.setAuthority(authorityKey).join()

            assertEquals(authorityKey, preferences.getString("authority.$ownerHex", ""))
            assertEquals(authorityKey, manager.state.value.authority)
        }

    /**
     * Revocation is the direction that has to be durable. A key left behind
     * here is a phone still taking orders from someone the operator believes
     * they removed.
     */
    @Test
    fun `revoking an authority clears it from the store`() {
        startAndAwaitOwner()
        manager.setAuthority(authorityKey)
        assertTrue(await { preferences.getString("authority.$ownerHex", "") == authorityKey })

        manager.setAuthority("")

        assertTrue(
            "the revoked key was left in the store",
            await { preferences.getString("authority.$ownerHex", "") == "" },
        )
        assertTrue(await { manager.state.value.authority == "" })
    }

    /** Uppercase is accepted and normalised, so the same key is one key. */
    @Test
    fun `an authority key is normalised before it is stored`() {
        startAndAwaitOwner()

        manager.setAuthority("  ${authorityKey.uppercase()}  ")

        assertTrue(
            await { preferences.getString("authority.$ownerHex", "") == authorityKey },
        )
    }

    /**
     * Validation stays on the calling thread. Were it inside the coroutine the
     * throw would surface somewhere off-thread, long after the button returned.
     */
    @Test
    fun `a malformed key is refused at the call site and stores nothing`() {
        startAndAwaitOwner()

        val error = runCatching { manager.setAuthority("not-a-key") }.exceptionOrNull()

        assertTrue("expected a synchronous rejection, got $error", error is IllegalArgumentException)
        assertEquals("", preferences.getString("authority.$ownerHex", ""))
    }

    @Test
    fun `a key of the wrong length is refused`() {
        startAndAwaitOwner()

        val error = runCatching { manager.setAuthority("ab".repeat(16)) }.exceptionOrNull()

        assertTrue("expected a synchronous rejection, got $error", error is IllegalArgumentException)
        assertEquals("", preferences.getString("authority.$ownerHex", ""))
    }

    /** Before the receiver names an owner there is nothing to key the trust on. */
    @Test
    fun `nothing is stored before the receiver has an owner`() {
        manager.setAuthority(authorityKey)

        Thread.sleep(200)
        assertEquals("", preferences.getString("authority.$ownerHex", ""))
        assertEquals("", manager.state.value.authority)
    }
}
