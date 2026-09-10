package network.columba.app.rns.backend.py

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PythonPacketEventsTest {
    @Test
    fun `packet callback publishes a snapshot with its destination`() =
        runTest {
            val events = PythonEventBridge()
            val destination =
                Destination(
                    ByteArray(16),
                    "00".repeat(16),
                    Identity(ByteArray(16), ByteArray(64), null),
                    Direction.IN,
                    DestinationType.SINGLE,
                    "rnstransport",
                    listOf("tak", "task"),
                )
            val next = async(start = CoroutineStart.UNDISPATCHED) { events.packets.first() }
            val bytes = byteArrayOf(1, 2, 3)
            events.publishPacket(destination, bytes)
            bytes[0] = 99
            assertArrayEquals(byteArrayOf(1, 2, 3), next.await().data)
            assertEquals(destination, next.await().destination)
        }
}
