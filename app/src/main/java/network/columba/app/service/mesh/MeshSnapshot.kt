package network.columba.app.service.mesh

import org.json.JSONArray
import org.json.JSONObject

/**
 * The mesh as this phone sees it, for the ATAK plugin: version 1 of the snapshot
 * in reticulum-atak's `docs/ColumbaInterface.md`.
 *
 * Pinned by `columba_mesh_v1.json`, which both repositories carry and test
 * against. A field Columba does not know is JSON `null` -- never omitted, never a
 * guess -- so the plugin can tell "unknown" from "none".
 */
data class MeshSnapshot(
    val at: Long,
    val node: Node,
    val propagation: Propagation?,
    val peers: List<Peer>,
) {
    data class Node(
        val uid: String?,
        val callsign: String?,
        /** Whether "allow ATAK control" is on: commands are refused when it is not. */
        val control: Boolean,
        /** Whether the TAK endpoint has a session -- members and a node address. */
        val running: Boolean,
    )

    data class Propagation(
        val hash: String,
        val name: String?,
        val path: Boolean,
        val hops: Int?,
        val carrier: String?,
        /** Whether this node belongs to a command post; null while Columba cannot tell. */
        val isCommandPost: Boolean?,
        val lastSync: Long?,
    )

    data class Peer(
        val uid: String,
        val callsign: String?,
        val role: String?,
        val heard: Long,
        val path: Boolean,
        val hops: Int?,
        val carrier: String?,
        val iface: String?,
    )

    fun toJson(): JSONObject =
        JSONObject()
            .put("v", VERSION)
            .put("at", at)
            .put(
                "node",
                JSONObject()
                    .put("uid", node.uid.orNull())
                    .put("callsign", node.callsign.orNull())
                    .put("control", node.control)
                    .put("running", node.running),
            ).put("propagation", propagation?.toJson() ?: JSONObject.NULL)
            .put("peers", JSONArray().also { array -> peers.forEach { array.put(it.toJson()) } })

    private fun Propagation.toJson(): JSONObject =
        JSONObject()
            .put("hash", hash)
            .put("name", name.orNull())
            .put("path", path)
            .put("hops", hops.orNull())
            .put("carrier", carrier.orNull())
            .put("is_command_post", isCommandPost.orNull())
            .put("last_sync", lastSync.orNull())

    private fun Peer.toJson(): JSONObject =
        JSONObject()
            .put("uid", uid)
            .put("callsign", callsign.orNull())
            .put("role", role.orNull())
            .put("heard", heard)
            .put("path", path)
            .put("hops", hops.orNull())
            .put("carrier", carrier.orNull())
            .put("interface", iface.orNull())

    companion object {
        const val VERSION = 1

        /** ATAK's own role for a command post; set on that ATAK, no config here. */
        const val COMMAND_POST_ROLE = "HQ"

        // JSONObject.put(key, null) removes the key; the contract says null.
        private fun Any?.orNull(): Any = this ?: JSONObject.NULL

        /** Parse a version 1 snapshot. Null for anything else. */
        fun fromJson(json: JSONObject): MeshSnapshot? {
            if (json.optInt("v", -1) != VERSION) return null
            val node = json.getJSONObject("node")
            val propagation = json.optJSONObject("propagation")
            val peers = json.getJSONArray("peers")
            return MeshSnapshot(
                at = json.getLong("at"),
                node =
                    Node(
                        uid = node.stringOrNull("uid"),
                        callsign = node.stringOrNull("callsign"),
                        control = node.getBoolean("control"),
                        running = node.getBoolean("running"),
                    ),
                propagation =
                    propagation?.let {
                        Propagation(
                            hash = it.getString("hash"),
                            name = it.stringOrNull("name"),
                            path = it.getBoolean("path"),
                            hops = it.intOrNull("hops"),
                            carrier = it.stringOrNull("carrier"),
                            isCommandPost = if (it.isNull("is_command_post")) null else it.getBoolean("is_command_post"),
                            lastSync = it.longOrNull("last_sync"),
                        )
                    },
                peers =
                    (0 until peers.length()).map { index ->
                        val peer = peers.getJSONObject(index)
                        Peer(
                            uid = peer.getString("uid"),
                            callsign = peer.stringOrNull("callsign"),
                            role = peer.stringOrNull("role"),
                            heard = peer.getLong("heard"),
                            path = peer.getBoolean("path"),
                            hops = peer.intOrNull("hops"),
                            carrier = peer.stringOrNull("carrier"),
                            iface = peer.stringOrNull("interface"),
                        )
                    },
            )
        }

        private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else getString(key)

        private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key)) null else getInt(key)

        private fun JSONObject.longOrNull(key: String): Long? = if (isNull(key)) null else getLong(key)
    }
}

/**
 * The class of carrier behind an interface, from its type -- the class name
 * Reticulum prints before the bracket -- never from its declared bitrate, which
 * is a guess (BLE claims 700 kbit/s, TCP 10 Mbit/s).
 *
 * Wi-Fi is not a class: an interface's type cannot tell Wi-Fi from any other IP
 * link, so it reports as the IP transport it uses.
 */
object MeshCarrier {
    const val LORA = "lora"
    const val BLE = "ble"
    const val TCP = "tcp"
    const val UDP = "udp"
    const val AUTO = "auto"
    const val LOCAL = "local"
    const val UNKNOWN = "unknown"

    /** Null for no interface name at all; [UNKNOWN] for one this does not recognise. */
    fun of(interfaceName: String?): String? {
        if (interfaceName.isNullOrBlank()) return null
        val type = interfaceName.substringBefore('[').trim()
        return when {
            type.contains("RNode") || type == "KISSInterface" -> LORA
            type.contains("BLE") -> BLE
            type.startsWith("TCP") || type.startsWith("Backbone") -> TCP
            type == "UDPInterface" -> UDP
            type == "AutoInterface" -> AUTO
            type.startsWith("Local") -> LOCAL
            else -> UNKNOWN
        }
    }
}
