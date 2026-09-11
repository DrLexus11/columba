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
import org.junit.Assert.assertTrue
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

    /**
     * PLAIN carries no identity to derive an address from and nothing creates
     * one, so it is refused by name rather than silently re-derived as
     * something else -- which used to surface as "Destination hash mismatch",
     * pointing at the hash rather than at why it was never going to match.
     *
     * GROUP was refused here too until the TAK endpoint needed to address its
     * team; it is now sent with its registered key, covered above.
     */
    @Test
    fun `a PLAIN destination is refused by type rather than by hash`() =
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
                val wrongType = destination.copy(direction = Direction.OUT, type = DestinationType.PLAIN)

                val error = backend.sendPacket(wrongType, byteArrayOf(1, 2, 3)).exceptionOrNull()

                assertNotNull("sending to a PLAIN destination must fail", error)
                val message = error!!.message.orEmpty()
                assertTrue(
                    "the error should name the type, but was: $message",
                    message.contains(DestinationType.PLAIN.name),
                )
                assertFalse(
                    "the type failure must not masquerade as a hash mismatch: $message",
                    message.contains("hash mismatch"),
                )
            } finally {
                Transport.findDestination(destination.hash)?.let(Transport::deregisterDestination)
            }
        }

    /**
     * The TAK endpoint addresses its team with a GROUP destination, so refusing
     * every non-SINGLE send made the endpoint receive-only: each event ATAK
     * produced was logged as a send failure while the map kept drawing everyone
     * else. The model alone cannot carry the group, since the symmetric key is
     * not part of it -- the send has to recover the key it was registered with.
     */
    @Test
    fun `a group destination is sent with the key it was registered with`() =
        runTest {
            val native =
                network.reticulum.identity.Identity
                    .create()
            val identity = Identity(native.hash, native.getPublicKey(), native.getPrivateKey())
            val backend = NativeRnsBackendImpl()
            val groupKey = ByteArray(64) { (it + 1).toByte() }
            val outbound =
                backend
                    .createGroupDestination(
                        identity,
                        Direction.OUT,
                        "rnstransport",
                        listOf("tak", "group", "abcd"),
                        groupKey,
                    ).getOrThrow()
            assertEquals(DestinationType.GROUP, outbound.type)

            var emitted: network.reticulum.packet.Packet? = null
            mockkObject(Transport)
            every { Transport.outbound(any()) } answers {
                emitted = firstArg()
                false
            }
            try {
                val receipt = backend.sendPacket(outbound, byteArrayOf(1, 2, 3)).getOrThrow()

                assertNotNull("a group send must reach the transport", emitted)
                assertArrayEquals(outbound.hash, emitted!!.destinationHash)
                assertArrayEquals(emitted!!.getHash(), receipt.hash)
            } finally {
                unmockkObject(Transport)
                Transport.findDestination(outbound.hash)?.let(Transport::deregisterDestination)
            }
        }

    /**
     * Sending to a group this backend never registered would encrypt to
     * something no member can read, which on the mesh is indistinguishable from
     * sending nothing. It has to say so instead.
     */
    @Test
    fun `a group with no registered key is refused by name`() =
        runTest {
            val native =
                network.reticulum.identity.Identity
                    .create()
            val identity = Identity(native.hash, native.getPublicKey(), native.getPrivateKey())
            val backend = NativeRnsBackendImpl()
            val stranger =
                network.columba.app.rns.api.model.Destination(
                    hash = ByteArray(16) { 0x5a },
                    hexHash = "5a".repeat(16),
                    identity = identity,
                    direction = Direction.OUT,
                    type = DestinationType.GROUP,
                    appName = "rnstransport",
                    aspects = listOf("tak", "group", "ffff"),
                )

            val error = backend.sendPacket(stranger, byteArrayOf(1)).exceptionOrNull()

            assertNotNull(error)
            assertTrue(
                "the error should name the missing key, but was: ${error!!.message}",
                error.message.orEmpty().contains("group key"),
            )
        }

    /**
     * The Python backend and the interface contract both refuse this. A group
     * registered without its key registers, announces, receives and decrypts
     * nothing -- which reads as a peer gone quiet rather than a wrong call.
     */
    @Test
    fun `createDestination refuses a group and points at the keyed call`() =
        runTest {
            val native =
                network.reticulum.identity.Identity
                    .create()
            val identity = Identity(native.hash, native.getPublicKey(), native.getPrivateKey())
            val backend = NativeRnsBackendImpl()

            val error =
                backend
                    .createDestination(
                        identity,
                        Direction.IN,
                        DestinationType.GROUP,
                        "rnstransport",
                        listOf("tak", "group", "dead"),
                    ).exceptionOrNull()

            assertNotNull("a keyless group must be refused", error)
            assertTrue(
                "the error should name the right call, but was: ${error!!.message}",
                error.message.orEmpty().contains("createGroupDestination"),
            )
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
