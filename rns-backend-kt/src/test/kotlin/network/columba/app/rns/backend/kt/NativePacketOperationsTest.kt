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
    fun `restart restores inbound callback before ready and account switch discards it`() =
        runTest {
            val native =
                network.reticulum.identity.Identity
                    .create()
            val identity = Identity(native.hash, native.getPublicKey(), native.getPrivateKey())
            val directory =
                java.nio.file.Files
                    .createTempDirectory("destination-restart")
                    .toFile()
            val backend = NativeRnsBackendImpl()
            val config =
                network.columba.app.rns.api.model.ReticulumConfig(
                    storagePath = directory.absolutePath,
                    enabledInterfaces = emptyList(),
                    deliveryIdentityKey = native.getPrivateKey(),
                    preferOwnInstance = true,
                    enableTransport = false,
                )
            try {
                backend.initialize(config).getOrThrow()
                val destination =
                    backend
                        .createDestination(
                            identity,
                            Direction.IN,
                            DestinationType.SINGLE,
                            "rnstransport",
                            listOf("tak", "task"),
                        ).getOrThrow()
                val original = Transport.findDestination(destination.hash)!!
                repeat(2) {
                    backend.shutdown().getOrThrow()
                    org.junit.Assert.assertNull(Transport.findDestination(destination.hash))
                    backend.initialize(config).getOrThrow()
                    assertEquals(network.columba.app.rns.api.model.NetworkStatus.READY, backend.networkStatus.value)
                    val restored = Transport.findDestination(destination.hash)!!
                    org.junit.Assert.assertNotSame(original, restored)
                    val received = async(start = CoroutineStart.UNDISPATCHED) { backend.observePackets().first() }
                    restored.packetCallback!!.invoke(byteArrayOf(4, 5), Unit)
                    assertArrayEquals(byteArrayOf(4, 5), received.await().data)
                    assertEquals(destination, received.await().destination)
                }
                backend.shutdown().getOrThrow()
                backend
                    .initialize(
                        config.copy(
                            deliveryIdentityKey =
                                network.reticulum.identity.Identity
                                    .create()
                                    .getPrivateKey(),
                        ),
                    ).getOrThrow()
                org.junit.Assert.assertNull(Transport.findDestination(destination.hash))
            } finally {
                backend.shutdown().getOrThrow()
                directory.deleteRecursively()
            }
        }

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
