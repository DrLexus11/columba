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

    /** Version byte plus the two declared lengths; the role's is implied. */
    private const val ANNOUNCE_HEADER = 3

    /** Lower-case hex only, so one destination has exactly one UID. */
    private const val CANONICAL_HEX = "0123456789abcdef"
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
        val body = canonicalUidBody(uid) ?: return null
        // Every character is canonical hex and the length is exact, so the
        // conversion below cannot fail -- the catch that used to be here could
        // never fire and only hid which check was doing the work.
        return ByteArray(DESTINATION_HASH_LENGTH) {
            body.substring(it * 2, it * 2 + 2).toInt(16).toByte()
        }
    }

    /**
     * The hex body of one of our UIDs, or null for anything else.
     *
     * Checked character by character rather than left to toInt(16), which
     * accepts more than canonical hex -- upper case, and a leading sign -- so
     * "AB".repeat(16) and bodies containing '+' converted happily and gave an
     * address for a UID this function reports as invalid. A UID is emitted
     * lower case; accepting other spellings means one destination has several
     * UIDs and a peer shows up as two tracks.
     *
     * Every reason a UID is not ours is one null from here, so adding another
     * does not add a branch to the conversion.
     */
    private fun canonicalUidBody(uid: String?): String? {
        if (uid == null || !uid.startsWith(UID_PREFIX)) return null
        val body = uid.removePrefix(UID_PREFIX)
        val canonical =
            body.length == DESTINATION_HASH_LENGTH * 2 &&
                body.all { it in CANONICAL_HEX }
        return body.takeIf { canonical }
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
        if (payload == null || payload.size < ANNOUNCE_HEADER + 3 || payload[0] != ANNOUNCE_VERSION) return null
        val lengths = announceLengths(payload) ?: return null
        val body = payload.copyOfRange(ANNOUNCE_HEADER, payload.size)
        return try {
            Claims(
                callsign = decodeStrict(body, 0, lengths.callsign),
                team = decodeStrict(body, lengths.callsign, lengths.team),
                role = decodeStrict(body, lengths.callsign + lengths.team, lengths.role),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** The three field lengths an announce declares, or null if they disagree. */
    private class Lengths(val callsign: Int, val team: Int, val role: Int)

    /**
     * Read the declared lengths and check all three at once.
     *
     * Together rather than in sequence: the role's length is whatever the
     * payload has left, so it is only meaningful once the other two are known,
     * and a fourth field later is one more line here rather than one more
     * early return in the caller.
     */
    private fun announceLengths(payload: ByteArray): Lengths? {
        val callsign = payload[1].toInt() and 0xFF
        val team = payload[2].toInt() and 0xFF
        val role = payload.size - ANNOUNCE_HEADER - callsign - team
        val declared = Lengths(callsign, team, role)
        val fits =
            callsign in 1..MAX_CALLSIGN &&
                team in 1..MAX_TEAM &&
                role in 1..MAX_TEAM
        return declared.takeIf { fits }
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
