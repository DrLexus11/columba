package network.columba.app.rns.api

import network.columba.app.rns.api.model.Destination
import network.columba.app.rns.api.model.DestinationType
import network.columba.app.rns.api.model.Direction
import network.columba.app.rns.api.model.Identity
import network.columba.app.rns.api.util.AppDestinationRegistry
import org.junit.Assert.assertEquals
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
}
