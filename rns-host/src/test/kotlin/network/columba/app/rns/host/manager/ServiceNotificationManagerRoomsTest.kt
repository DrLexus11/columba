package network.columba.app.rns.host.manager

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import network.columba.app.rns.host.state.ServiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Rooms speak through the one foreground notification (docs/EridanusMerge.md,
 * step 4): a line of their own, "Rooms: ...", and never a notification beside it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServiceNotificationManagerRoomsTest {
    private lateinit var notificationManager: NotificationManager
    private lateinit var manager: ServiceNotificationManager

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val state = ServiceState().apply { networkStatus.set("READY") }
        manager = ServiceNotificationManager(context, state)
        manager.createNotificationChannel()
        manager.updateNotification("READY")
        ShadowLooper.idleMainLooper()
    }

    private fun detail(): String {
        val notification = shadowOf(notificationManager).getNotification(ServiceNotificationManager.NOTIFICATION_ID)
        assertNotNull(notification)
        return notification!!.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
    }

    @Test
    fun `a rooms status is a line of its own, state first`() {
        manager.updateRoomsStatus("connected to hub 3 hops")
        ShadowLooper.idleMainLooper()
        assertTrue(detail(), detail().lines().contains("Rooms: connected to hub 3 hops"))
    }

    @Test
    fun `clearing the status removes the line`() {
        manager.updateRoomsStatus("connected")
        ShadowLooper.idleMainLooper()
        manager.updateRoomsStatus(null)
        ShadowLooper.idleMainLooper()
        assertFalse(detail(), detail().contains("Rooms:"))
    }

    @Test
    fun `rooms never post a notification of their own`() {
        val before = shadowOf(notificationManager).allNotifications.size
        manager.updateRoomsStatus("connected")
        ShadowLooper.idleMainLooper()
        assertEquals(before, shadowOf(notificationManager).allNotifications.size)
    }
}
