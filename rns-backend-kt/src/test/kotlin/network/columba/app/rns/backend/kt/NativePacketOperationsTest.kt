package network.columba.app.rns.backend.kt

import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import network.reticulum.transport.Transport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativePacketOperationsTest {
    @Test
    fun `announce and send use the requested destination and real packet`() =
        runTest {
            val native =
                network.reticulum.identity.Identity
                    .create()
            val identity = Identity(native.hash, native.getPublicKey(), native.getPrivateKey())
            val backend = NativeRnsBackendImpl()
            val destination =
                backend
                    .createDestination(
                        identity,
                        Direction.IN,
                        DestinationType.SINGLE,
                        "rnstransport",
                        listOf("tak", "task"),
                    ).getOrThrow()
            var emitted: network.reticulum.packet.Packet? = null
            mockkObject(Transport)
            every { Transport.outbound(any()) } answers {
                emitted = firstArg()
                false
            }
            try {
                backend.announceDestination(destination, byteArrayOf(7)).getOrThrow()
                assertNotNull(emitted)
                assertArrayEquals(destination.hash, emitted!!.destinationHash)
                assertEquals(network.reticulum.common.PacketType.ANNOUNCE, emitted!!.packetType)
                emitted = null
                val outbound = destination.copy(direction = Direction.OUT, identity = identity.copy(privateKey = null))
                val receipt = backend.sendPacket(outbound, byteArrayOf(1, 2, 3)).getOrThrow()
                assertNotNull(emitted)
                assertArrayEquals(outbound.hash, emitted!!.destinationHash)
                assertArrayEquals(emitted!!.getHash(), receipt.hash)
                assertFalse(receipt.delivered)
            } finally {
                unmockkObject(Transport)
                Transport.findDestination(destination.hash)?.let(Transport::deregisterDestination)
            }
        }

    @Test
    fun `incoming destination emits exact packet and can be reattached`() =
        runTest {
            val native =
                network.reticulum.identity.Identity
                    .create()
            val identity = Identity(native.hash, native.getPublicKey(), native.getPrivateKey())
            val backend = NativeRnsBackendImpl()
            val destination =
                backend
                    .createDestination(
                        identity,
                        Direction.IN,
                        DestinationType.SINGLE,
                        "rnstransport",
                        listOf("tak", "task"),
                    ).getOrThrow()
            try {
                val again =
                    backend
                        .createDestination(
                            identity,
                            Direction.IN,
                            DestinationType.SINGLE,
                            "rnstransport",
                            listOf("tak", "task"),
                        ).getOrThrow()
                assertEquals(destination, again)
                val received = async(start = CoroutineStart.UNDISPATCHED) { backend.observePackets().first() }
                val packet = byteArrayOf(1, 2, 3)
                Transport.findDestination(destination.hash)!!.packetCallback!!.invoke(packet, Unit)
                packet[0] = 99
                assertArrayEquals(byteArrayOf(1, 2, 3), received.await().data)
                assertEquals(destination, received.await().destination)
            } finally {
                Transport.findDestination(destination.hash)?.let(Transport::deregisterDestination)
            }
        }
}
