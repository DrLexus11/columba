package network.columba.app.service.tak

/**
 * EUD identity derived from the Reticulum destination, not invented beside it.
 *
 * The Kotlin half of `tools/tak_identity.py`. See TAKIntegrationPivots.md,
 * pivot 1: the gateway used to build an ATAK UID from a 32-bit truncation of a
 * 128-bit identity hash, which could not be verified, could not be reversed to
 * address a peer, and did not survive a re-provision.
 *
 * Length costs no airtime. The mesh carries compact binary and CoT is rendered
 * locally, so a UID only ever exists inside a loopback socket.
 */
object TakIdentity {
    const val UID_PREFIX = "urtn-"
    const val DESTINATION_HASH_LENGTH = 16

    const val ANNOUNCE_VERSION: Byte = 1
    const val MAX_CALLSIGN = 44
    const val MAX_TEAM = 24
    const val DEFAULT_TEAM = "Cyan"
    const val DEFAULT_ROLE = "Team Member"

    /**
     * This node's own destination, which is what a UID must name.
     *
     * Not the team's group destination: every member derives that same address
     * by design, so a UID built from it is the same string on every node --
     * one track on the map for the whole team, jumping between everyone's
     * positions. Observed on hardware before this existed.
     *
     * SINGLE, so it routes: unlike a GROUP address it goes through Reticulum's
     * path table and survives more than one hop, which is what makes a UID
     * usable for addressing a peer rather than merely naming one.
     */
    const val NODE_APP = "rnstransport"
    val NODE_ASPECTS = listOf("tak", "node")

    /** The stable ATAK UID for a Reticulum destination. */
    fun uidFor(destinationHash: ByteArray): String {
        require(destinationHash.size == DESTINATION_HASH_LENGTH) {
            "destination hash must be $DESTINATION_HASH_LENGTH bytes"
        }
        return UID_PREFIX + destinationHash.joinToString("") { "%02x".format(it) }
    }

    /**
     * The destination a UID came from, or null when it is not ours.
     *
     * A real ATAK network is full of UIDs this project never issued --
     * ANDROID-xxxx from a phone, a GUID for a dropped marker. Returning null
     * rather than guessing is what stops a caller mistaking somebody else's
     * marker for a peer it can address.
     */
    fun destinationFor(uid: String?): ByteArray? {
        if (uid == null || !uid.startsWith(UID_PREFIX)) return null
        val body = uid.removePrefix(UID_PREFIX)
        if (body.length != DESTINATION_HASH_LENGTH * 2) return null
        return try {
            ByteArray(DESTINATION_HASH_LENGTH) {
                body.substring(it * 2, it * 2 + 2).toInt(16).toByte()
            }
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Encode callsign, team and role for a Reticulum announce's app_data. */
    fun announcePayload(
        callsign: String,
        team: String = DEFAULT_TEAM,
        role: String = DEFAULT_ROLE,
    ): ByteArray {
        val fields = listOf(callsign to MAX_CALLSIGN, team to MAX_TEAM, role to MAX_TEAM)
            .map { (value, limit) ->
                val encoded = value.toByteArray(Charsets.UTF_8)
                require(encoded.isNotEmpty() && encoded.size <= limit) {
                    "field must be 1..$limit UTF-8 bytes"
                }
                require(value.none { it.code < 32 || it.code == 127 }) {
                    "field must not contain control characters"
                }
                encoded
            }
        return byteArrayOf(ANNOUNCE_VERSION, fields[0].size.toByte(), fields[1].size.toByte()) +
            fields[0] + fields[1] + fields[2]
    }

    /**
     * Decode announce app_data, or null for anything not ours.
     *
     * Announces arrive from every node on the mesh, including ones running
     * other software, so a payload that is not ours is ordinary and not an
     * error.
     */
    fun parseAnnounce(payload: ByteArray?): Claims? {
        if (payload == null || payload.size < 6) return null
        if (payload[0] != ANNOUNCE_VERSION) return null
        val callsignLength = payload[1].toInt() and 0xFF
        val teamLength = payload[2].toInt() and 0xFF
        val body = payload.copyOfRange(3, payload.size)
        val roleLength = body.size - callsignLength - teamLength
        if (callsignLength < 1 || teamLength < 1 || roleLength < 1) return null
        if (callsignLength > MAX_CALLSIGN || teamLength > MAX_TEAM || roleLength > MAX_TEAM) return null
        return try {
            Claims(
                callsign = decodeStrict(body, 0, callsignLength),
                team = decodeStrict(body, callsignLength, teamLength),
                role = decodeStrict(body, callsignLength + teamLength, roleLength),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun decodeStrict(source: ByteArray, offset: Int, length: Int): String {
        val slice = source.copyOfRange(offset, offset + length)
        val text = slice.toString(Charsets.UTF_8)
        // Kotlin substitutes U+FFFD for invalid UTF-8 rather than throwing, so
        // a malformed announce would otherwise arrive as plausible-looking text.
        require(text.toByteArray(Charsets.UTF_8).contentEquals(slice)) { "not valid UTF-8" }
        return text
    }

    /** Callsign and team as *claimed* by an announce. Claims, not evidence. */
    data class Claims(val callsign: String, val team: String, val role: String)
}
