package network.columba.app.rns.api

import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.util.AppDestinationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDestinationRegistryTest {
    @Test
    fun `registration snapshots survive restart but not an account change`() {
        val registry = AppDestinationRegistry()
        registry.activateOwner("alice")
        val aspects = mutableListOf("tak", "task")
        val destination =
            Destination(
                ByteArray(16),
                "00".repeat(16),
                Identity(ByteArray(16), ByteArray(64), ByteArray(64)),
                Direction.IN,
                DestinationType.SINGLE,
                "rnstransport",
                aspects,
            )
        registry.remember(destination)
        registry.remember(destination)
        destination.hash[0] = 1
        destination.identity.privateKey!![0] = 1
        aspects.clear()
        registry.activateOwner("alice")
        val saved = registry.registrations().single()
        assertEquals(listOf("tak", "task"), saved.aspects)
        assertEquals(0.toByte(), saved.hash[0])
        assertEquals(0.toByte(), saved.identity.privateKey!![0])
        saved.hash[0] = 2
        assertEquals(0.toByte(), registry.registrations().single().hash[0])
        registry.activateOwner("bob")
        assertTrue(registry.registrations().isEmpty())
    }

    private fun destination(hexHash: String, type: DestinationType) =
        Destination(
            ByteArray(16),
            hexHash.repeat(16),
            Identity(ByteArray(16), ByteArray(64), ByteArray(64)),
            Direction.IN,
            type,
            "rnstransport",
            mutableListOf("tak", "group"),
        )

    @Test
    fun `a group registration keeps the key it was made with`() {
        // Replaying a GROUP destination without its symmetric key produces one
        // that registers, announces, receives packets and decrypts none of
        // them -- a peer that has apparently gone quiet. The registry is the
        // only thing that survives a backend restart, so the key lives here.
        val registry = AppDestinationRegistry()
        registry.activateOwner("owner-a")
        val key = ByteArray(64) { it.toByte() }
        val group = destination("aa", DestinationType.GROUP)
        val single = destination("bb", DestinationType.SINGLE)
        registry.remember(group, key)
        registry.remember(single)

        assertArrayEquals(key, registry.groupKeyFor(group))
        assertNull("a single destination has no group key", registry.groupKeyFor(single))
        assertEquals(2, registry.registrations().size)
    }

    @Test
    fun `the stored key is a copy, so a caller cannot mutate it later`() {
        val registry = AppDestinationRegistry()
        registry.activateOwner("owner-a")
        val key = ByteArray(64) { 3 }
        val group = destination("aa", DestinationType.GROUP)
        registry.remember(group, key)
        key[0] = 99
        assertEquals(3.toByte(), registry.groupKeyFor(group)!![0])
    }

    @Test
    fun `an account change discards the previous account's group keys`() {
        val registry = AppDestinationRegistry()
        registry.activateOwner("owner-a")
        val group = destination("aa", DestinationType.GROUP)
        registry.remember(group, ByteArray(64) { 7 })
        registry.activateOwner("owner-b")
        assertNull(registry.groupKeyFor(group))
        assertTrue(registry.registrations().isEmpty())
    }
}
