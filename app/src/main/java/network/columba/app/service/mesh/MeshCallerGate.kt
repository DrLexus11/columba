package network.columba.app.service.mesh

/**
 * Who may use the mesh interface: a package and the SHA-256 of the certificate
 * that signed it, both on the list.
 *
 * Not a permission. An ATAK plugin runs inside ATAK's process, so the binding
 * arrives from ATAK -- signed by TAK, not by us -- and a plugin cannot add a
 * permission to ATAK's manifest (reticulum-atak `OpenDecisions.md`, 1).
 *
 * Checked on every call, not once at bind: a binder handed on to another
 * process must not carry the first caller's standing. Pure, so it is tested
 * without Android; [MeshService] supplies the packages and digests.
 */
class MeshCallerGate(
    private val allowed: Set<Caller> = DEFAULT_ALLOWED,
) {
    data class Caller(val packageName: String, val certSha256: String)

    /**
     * Whether a calling uid's packages may use the interface. Every package
     * under the uid must be allowed: a shared uid admits nothing it does not
     * fully account for.
     */
    fun allows(
        packages: List<String>,
        certDigests: (String) -> List<String>,
    ): Boolean {
        if (packages.isEmpty()) return false
        return packages.all { name ->
            val digests = certDigests(name)
            digests.isNotEmpty() && digests.all { digest ->
                Caller(name, digest.lowercase()) in allowed
            }
        }
    }

    companion object {
        const val ATAK_CIV = "com.atakmap.app.civ"

        /** ATAK-CIV as distributed (Play Store and TAK.gov), read 2026-10-02. */
        const val ATAK_STORE_CERT = "94cf4bac08acfd8a90ddfce88f5772215ae0639833d5dd8bfe3fd6819c8961da"

        /** The developer ATAK that ships with the SDK, on the bench phones. */
        const val ATAK_SDK_CERT = "ccb0994def1e512877a02bf84ef1099ebae3d156dd78e638b77f5c31c9ac7234"

        val DEFAULT_ALLOWED =
            setOf(
                Caller(ATAK_CIV, ATAK_STORE_CERT),
                Caller(ATAK_CIV, ATAK_SDK_CERT),
            )
    }
}

/**
 * The least time between two announces asked for through the mesh interface.
 *
 * Not politeness: relays block a destination that announces faster than their
 * allowance, which costs the node its name downstream -- the opposite of
 * re-meshing fast. The same floor as [network.columba.app.service.tak.InboxAnnouncer].
 */
class MeshAnnounceFloor(
    private val floorMs: Long = FLOOR_MS,
) {
    private var last = 0L

    /** Milliseconds until an announce is allowed; zero when it is now. */
    fun remaining(now: Long): Long = if (last == 0L) 0L else (floorMs - (now - last)).coerceAtLeast(0L)

    /** Record an announce that actually went out. */
    fun sent(now: Long) {
        last = now
    }

    companion object {
        const val FLOOR_MS = 60_000L
    }
}
