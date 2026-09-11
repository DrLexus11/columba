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

    /**
     * Group keys, kept beside the registration they belong to.
     *
     * A GROUP destination cannot be rebuilt from the destination alone: its
     * symmetric key is not part of it, and replaying one without the key
     * produces a destination that registers, announces, receives packets and
     * decrypts none of them. The key is held only in memory, for the life of
     * the process, exactly like the registration intent it accompanies.
     */
    private val groupKeys = mutableMapOf<String, ByteArray>()

    fun activateOwner(identityHash: String) {
        if (owner != identityHash) {
            destinations.clear()
            groupKeys.clear()
        }
        owner = identityHash
    }

    fun remember(destination: Destination, groupKey: ByteArray? = null) {
        val key = "${destination.direction}:${destination.type}:${destination.hexHash}"
        destinations[key] = destination.snapshot()
        if (groupKey != null) groupKeys[key] = groupKey.copyOf()
    }

    /** The key this destination was registered with, if it is a group. */
    fun groupKeyFor(destination: Destination): ByteArray? =
        groupKeys["${destination.direction}:${destination.type}:${destination.hexHash}"]?.copyOf()

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
