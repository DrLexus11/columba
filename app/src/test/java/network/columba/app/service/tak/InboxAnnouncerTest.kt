package network.columba.app.service.tak

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.rns.api.RnsCore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Telling an arriving peer where our inbox is.
 *
 * A peer that has only now appeared holds a path to our TAK node and none to
 * our inbox. Markers and positions ride the node destination and work; chat
 * rides the inbox and goes nowhere. Reported from the field 2026-09-20 as a
 * cold start: "markers always arrive, messaging needs a cold start period".
 */
class InboxAnnouncerTest {
    private fun announcer(displayName: String? = "LEXUS"): Pair<InboxAnnouncer, RnsCore> {
        val identity = mockk<LocalIdentityEntity>()
        every { identity.displayName } returns (displayName ?: "")
        val repo = mockk<IdentityRepository>()
        coEvery { repo.getActiveIdentitySync() } returns
            (if (displayName == null) null else identity)
        val core = mockk<RnsCore>()
        coEvery { core.triggerAutoAnnounce(any()) } returns Result.success(Unit)
        return InboxAnnouncer(repo, core) to core
    }

    @Test
    fun `the first arrival is answered immediately`() = runTest {
        val (announcer, core) = announcer()
        assertTrue(announcer.announceIfDue(now = 1_000))
        coVerify(exactly = 1) { core.triggerAutoAnnounce("LEXUS") }
    }

    @Test
    fun `a second arrival inside the floor is not`() = runTest {
        // Ten nodes powering up together must not mean ten announces.
        val (announcer, core) = announcer()
        announcer.announceIfDue(1_000)
        assertFalse(announcer.announceIfDue(1_000 + InboxAnnouncer.FLOOR_MS - 1))
        coVerify(exactly = 1) { core.triggerAutoAnnounce(any()) }
    }

    @Test
    fun `once the floor has passed it answers again`() = runTest {
        val (announcer, _) = announcer()
        announcer.announceIfDue(1_000)
        assertTrue(announcer.announceIfDue(1_000 + InboxAnnouncer.FLOOR_MS))
    }

    @Test
    fun `it announces under the identity's own name, never the TAK callsign`() = runTest {
        // Announcing under a different name would rename this operator in
        // every contact list on the network.
        val (announcer, core) = announcer(displayName = "Yavuz")
        assertTrue(announcer.announceIfDue(1_000))
        coVerify(exactly = 1) { core.triggerAutoAnnounce("Yavuz") }
    }

    @Test
    fun `with no active identity there is nothing to announce`() = runTest {
        val (announcer, core) = announcer(displayName = null)
        assertFalse(announcer.announceIfDue(1_000))
        coVerify(exactly = 0) { core.triggerAutoAnnounce(any()) }
    }

    @Test
    fun `the floor is far shorter than the app's own announce interval`() {
        // The app announces every one to twelve hours, randomised, and can be
        // switched off. That is right for a personal messenger and far too
        // slow for a mesh node whose peers come and go.
        assertTrue(InboxAnnouncer.FLOOR_MS < 60L * 60 * 1000)
    }
}
