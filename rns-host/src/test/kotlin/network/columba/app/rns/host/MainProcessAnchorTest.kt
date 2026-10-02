package network.columba.app.rns.host

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MainProcessAnchorTest {
    private val flags = slot<Int>()
    private val intent = slot<Intent>()
    private val context =
        mockk<Context>(relaxed = true) {
            every { packageName } returns "network.columba.app.debug"
            every { bindService(capture(intent), any<ServiceConnection>(), capture(flags)) } returns true
        }

    @Test
    fun `the anchor binds with the foreground level and brings the main process back`() {
        MainProcessAnchor(context).hold()
        // BIND_IMPORTANT carries :reticulum's foreground level to the main
        // process; BIND_AUTO_CREATE restarts it if it is reclaimed anyway.
        assertEquals(Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT, flags.captured)
        assertEquals(MainProcessAnchor.ACTION, intent.captured.action)
        // Within this app only: the debug build's own package, not a fixed one.
        assertEquals("network.columba.app.debug", intent.captured.`package`)
    }

    @Test
    fun `holding twice binds once, releasing unbinds once`() {
        val anchor = MainProcessAnchor(context)
        anchor.hold()
        anchor.hold()
        verify(exactly = 1) { context.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) }
        anchor.release()
        anchor.release()
        verify(exactly = 1) { context.unbindService(any()) }
    }

    @Test
    fun `a missing anchor service is released, not left half-bound`() {
        every { context.bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } returns false
        MainProcessAnchor(context).hold()
        // A failed bind still holds the connection until released.
        verify(exactly = 1) { context.unbindService(any()) }
        assertTrue(true)
    }
}
