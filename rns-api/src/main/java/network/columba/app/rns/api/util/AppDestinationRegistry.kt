package network.columba.app.rns.api.util

import network.columba.app.rns.api.model.Destination

/**
 * Registration intent retained across an in-process backend restart. Live
 * transport objects and callbacks belong to each new runtime and are rebuilt
 * before READY. An account change discards the previous account's listeners.
 * Callers serialize access with their backend lifecycle lock.
 */
class AppDestinationRegistry {
    private var owner: String? = null
    private val destinations = linkedMapOf<String, Destination>()

    fun activateOwner(identityHash: String) {
        if (owner != identityHash) destinations.clear()
        owner = identityHash
    }

    fun remember(destination: Destination) {
        val key = "${destination.direction}:${destination.type}:${destination.hexHash}"
        destinations[key] = destination.snapshot()
    }

    fun registrations(): List<Destination> = destinations.values.map { it.snapshot() }

    private fun Destination.snapshot(): Destination =
        copy(
            hash = hash.copyOf(),
            identity =
                identity.copy(
                    hash = identity.hash.copyOf(),
                    publicKey = identity.publicKey.copyOf(),
                    privateKey = identity.privateKey?.copyOf(),
                ),
            aspects = aspects.toList(),
        )
}
