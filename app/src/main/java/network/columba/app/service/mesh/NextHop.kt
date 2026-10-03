package network.columba.app.service.mesh

/**
 * Which configured interface a path leaves through, from the next-hop name the
 * backend reports. Pure, so it is tested without a stack.
 *
 * Both backends report the interface's configured name ("TCP", "Board TCP"),
 * and fall back to its string form ("TCPInterface[Board TCP/10.0.0.2:4242]")
 * when the name is empty. An AutoInterface path sits on a per-peer object
 * whose string form names the network device ("AutoInterfacePeer[wlan0/fe80::1]"),
 * not the configured interface -- it resolves to the one configured
 * AutoInterface, when there is exactly one.
 */
object NextHop {
    class Configured(
        val name: String,
        val type: String,
    )

    fun configured(
        nextHop: String?,
        interfaces: List<Configured>,
    ): Configured? {
        if (nextHop.isNullOrBlank()) return null
        interfaces.firstOrNull { it.name == nextHop }?.let { return it }
        if ('[' in nextHop) {
            val inner = nextHop.substringAfter('[').substringBefore(']').substringBefore('/')
            interfaces.firstOrNull { it.name == inner }?.let { return it }
        }
        if (nextHop.startsWith("AutoInterface")) {
            return interfaces.filter { it.type.startsWith("Auto") }.singleOrNull()
        }
        return null
    }

    /** The configured interface's carrier when it resolves; otherwise read from the name. */
    fun carrier(
        nextHop: String?,
        interfaces: List<Configured>,
    ): String? =
        configured(nextHop, interfaces)?.let { MeshCarrier.ofConfigType(it.type) }
            ?: MeshCarrier.of(nextHop)
}
