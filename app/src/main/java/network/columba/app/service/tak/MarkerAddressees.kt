package network.columba.app.service.tak

import org.w3c.dom.Element

/**
 * Who a marker was sent to.
 *
 * The Kotlin half of `_marker_recipients` in `tools/cot_bridge.py`.
 *
 * ATAK's "Send" to a contact puts the recipient in `detail/marti/dest`, the same
 * mechanism it uses to route anything through a server; a broadcast carries no
 * marti at all. Before this, that was ignored and every marker was fanned out
 * to the whole team -- a pin dropped for one person shown to everybody, the
 * confidentiality problem the chat path was built to avoid, on another codec.
 *
 * No wire change: markers already go out one unicast per member, so an
 * addressed marker is simply sent only to its addressee.
 */
object MarkerAddressees {
    /**
     * The members this marker is for, or null when it is a broadcast.
     *
     * A `dest` names a peer by UID or by callsign. A UID is resolved exactly; a
     * callsign is matched against what members announced, and every member
     * announcing it is a recipient -- the operator chose a name on the map. An
     * empty list means addressed to nobody on this team, and the caller must
     * refuse rather than broadcast.
     */
    fun of(cotXml: String, registry: TakMembership.Registry, now: Long): List<ByteArray>? {
        val dests = destinations(cotXml) ?: return null
        val members = registry.members(now)
        return dests.flatMap { dest ->
            val exact = dest.getAttribute("uid").takeIf { it.isNotEmpty() }
                ?.let { registry.memberDestination(it, now) }
            if (exact != null) {
                listOf(exact)
            } else {
                val callsign = dest.getAttribute("callsign")
                members.filter { callsign.isNotEmpty() && registry.describe(it)?.callsign == callsign }
            }
        }.distinctBy { it.toList() }
    }

    /** The `marti/dest` elements, or null when there is no marti at all. */
    private fun destinations(cotXml: String): List<Element>? {
        val event =
            try {
                CotEvent.parse(cotXml)
            } catch (_: IllegalArgumentException) {
                null
            }
        val marti = event?.getElementsByTagName("marti")?.takeIf { it.length > 0 }?.item(0) as? Element
        val dests = marti?.getElementsByTagName("dest")
        return dests?.takeIf { it.length > 0 }?.let { list -> (0 until list.length).map { list.item(it) as Element } }
    }
}
