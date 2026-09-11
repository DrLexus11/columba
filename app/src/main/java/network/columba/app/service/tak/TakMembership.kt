package network.columba.app.service.tak

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Who is on this team, learned from announces rather than configured.
 *
 * The Kotlin half of `tools/tak_membership.py`. Pivot 5 in
 * TAKIntegrationPivots.md: a `GROUP` destination reaches only peers on the same
 * interface as the sender -- measured, not a judgement call -- so a team cannot
 * be an address. It becomes a membership set, and traffic for it is addressed
 * to each member's `SINGLE` destination, which routes.
 *
 * The team name is **not** carried in the clear. [TakGroups.groupAspects] goes
 * to some trouble to keep team names off the air, and an announce saying
 * `team="Cyan"` would hand a passive listener exactly what that avoided. A node
 * announces an HMAC of the team under the fleet secret instead: everyone
 * holding the secret recognises it, nobody else can compute one, and nobody
 * without the secret can forge one.
 *
 * What this does not hide is that some set of nodes share a tag. A listener can
 * count a team and watch it move without ever learning its name -- membership
 * is pseudonymous, not private, which is the exposure `groupAspects` already
 * accepts.
 */
object TakMembership {
    /**
     * Version 2. Version 1 carried the team name in plain text and had no
     * caller on either side; the plaintext team was the reason to replace it
     * before it acquired one.
     */
    const val VERSION: Byte = 2

    const val TEAM_TAG_BYTES = 8
    const val MAX_CALLSIGN = 44
    const val MAX_ROLE = 24
    const val HEADER_BYTES = 1 + TEAM_TAG_BYTES + 1 + 1

    /**
     * How long a member is kept after its last announce.
     *
     * Deliberately long. A responder who has gone quiet is precisely the one
     * still worth addressing, and absence of an announce says nothing about
     * reachability -- announces are rare by design. Forgetting a member means
     * silently declining to send them anything, which is the failure this
     * whole pivot exists to avoid.
     */
    const val DEFAULT_EXPIRY_MS = 6L * 60 * 60 * 1000

    /**
     * The shortest gap between two announces prompted by meeting somebody new.
     *
     * A node that starts late hears everyone who announces after it and nobody
     * who announced before, so meeting a stranger is the moment to say who you
     * are rather than leaving them to wait out a re-announce interval measured
     * in half-hours. One exchange is enough: greeting only happens for a member
     * that was not already known, so the reply it provokes finds a known member
     * and stops there. The floor keeps a team all starting at once from turning
     * that into a storm.
     */
    const val GREET_MIN_INTERVAL_MS = 20_000L

    /** What an announce turned out to be. */
    enum class Arrival { NEW, KNOWN }

    /** Callsign and role as *claimed*, plus the tag, which is evidence. */
    data class Claims(val tag: ByteArray, val callsign: String, val role: String) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Claims) return false
            return tag.contentEquals(other.tag) && callsign == other.callsign && role == other.role
        }

        override fun hashCode(): Int =
            (tag.contentHashCode() * 31 + callsign.hashCode()) * 31 + role.hashCode()
    }

    /** A stable, unforgeable label for a team that does not name it. */
    fun teamTag(team: String, secret: ByteArray): ByteArray {
        TakGroups.requireSecret(secret)
        // The separator is a NUL, not a space. The Python side uses b"member\\0",
        // and a space here would derive a different tag from the same secret,
        // presenting as a team whose members cannot see each other. That exact
        // substitution has happened once already in this codebase.
        val message = TakGroups.DOMAIN + "member\u0000".toByteArray(Charsets.US_ASCII) +
            TakGroups.normaliseTeam(team).toByteArray(Charsets.UTF_8)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(message).copyOf(TEAM_TAG_BYTES)
    }

    /** Announce app_data claiming membership of a team. */
    fun memberPayload(
        team: String,
        secret: ByteArray,
        callsign: String,
        role: String = "Team Member",
    ): ByteArray {
        val fields = listOf(callsign to MAX_CALLSIGN, role to MAX_ROLE).map { (value, limit) ->
            val encoded = value.toByteArray(Charsets.UTF_8)
            require(encoded.isNotEmpty() && encoded.size <= limit) {
                "field must be 1..$limit UTF-8 bytes"
            }
            require(value.none { it.code < 32 || it.code == 127 }) {
                "field must not contain control characters"
            }
            encoded
        }
        return byteArrayOf(VERSION) + teamTag(team, secret) +
            byteArrayOf(fields[0].size.toByte(), fields[1].size.toByte()) +
            fields[0] + fields[1]
    }

    /**
     * Decode announce app_data, or null for anything that is not ours.
     *
     * Announces arrive from every node on the mesh, including ones running
     * other software entirely. A payload that is not ours is ordinary.
     */
    fun parseMember(payload: ByteArray?): Claims? {
        if (payload == null || payload.size < HEADER_BYTES) return null
        if (payload[0] != VERSION) return null
        val tag = payload.copyOfRange(1, 1 + TEAM_TAG_BYTES)
        val callsignLength = payload[1 + TEAM_TAG_BYTES].toInt() and 0xFF
        val roleLength = payload[2 + TEAM_TAG_BYTES].toInt() and 0xFF
        if (callsignLength < 1 || roleLength < 1) return null
        if (callsignLength > MAX_CALLSIGN || roleLength > MAX_ROLE) return null
        val body = payload.copyOfRange(HEADER_BYTES, payload.size)
        // The lengths describe the whole body. Anything after it is not
        // something this version knows how to read.
        if (body.size != callsignLength + roleLength) return null
        return try {
            Claims(
                tag = tag,
                callsign = decodeStrict(body, 0, callsignLength),
                role = decodeStrict(body, callsignLength, roleLength),
            )
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun decodeStrict(source: ByteArray, offset: Int, length: Int): String {
        val slice = source.copyOfRange(offset, offset + length)
        val text = slice.toString(Charsets.UTF_8)
        // Kotlin substitutes U+FFFD for invalid UTF-8 rather than throwing, so
        // a malformed announce would otherwise arrive as plausible-looking text
        // and be shown to an operator as somebody's callsign.
        require(text.toByteArray(Charsets.UTF_8).contentEquals(slice)) { "not valid UTF-8" }
        return text
    }

    /**
     * The members of one team, as heard on the air.
     *
     * Addressed traffic costs one transmission per member per hop, so this list
     * is what a marker costs. It is built only from announces carrying our
     * team's tag -- an announce from another team, or from software that is not
     * ours, is not a member and is not paid for.
     */
    class Registry(
        team: String,
        secret: ByteArray,
        private val expiryMs: Long = DEFAULT_EXPIRY_MS,
        private val ownHash: ByteArray? = null,
    ) {
        private val tag = teamTag(team, secret)
        private val members = linkedMapOf<String, Entry>()
        private var lastGreet: Long? = null

        data class Entry(val callsign: String, val role: String, val heard: Long)

        /**
         * Record an announce.
         *
         * [Arrival.NEW] for a member we had not got, [Arrival.KNOWN] for one we
         * had, null for an announce that is not this team's -- which is most of
         * them, since this sees every announce the node hears. The new/known
         * distinction is the whole basis of greeting, and keeping it here rather
         * than leaving callers to search the list means one rule applied the
         * same way, including around expiry.
         */
        fun remember(destinationHash: ByteArray, appData: ByteArray?, now: Long): Arrival? {
            val claims = parseMember(appData) ?: return null
            if (!constantTimeEquals(claims.tag, tag)) return null
            if (ownHash != null && destinationHash.contentEquals(ownHash)) {
                // Our own announce comes back through Transport like anyone
                // else's. Addressing ourselves would double every marker and
                // feed our own events back into our own endpoint.
                return null
            }
            val key = destinationHash.toHex()
            val previous = members[key]
            // An entry past its expiry is not a member any more, so hearing
            // from it again is a new arrival rather than a refresh -- and worth
            // greeting, because from their side we may equally have dropped off.
            val known = previous != null && now - previous.heard <= expiryMs
            members[key] = Entry(claims.callsign, claims.role, now)
            return if (known) Arrival.KNOWN else Arrival.NEW
        }

        /** Destination hashes worth addressing, most recently heard first. */
        fun members(now: Long): List<ByteArray> =
            members.entries
                .filter { now - it.value.heard <= expiryMs }
                .sortedByDescending { it.value.heard }
                .map { it.key.unHex() }

        /** Callsign and role as claimed, or null. For display, not for trust. */
        fun describe(destinationHash: ByteArray): Entry? = members[destinationHash.toHex()]

        /**
         * Whether to announce now because we have just met someone new.
         *
         * Rate limited rather than unconditional: ten nodes powering up
         * together would otherwise each announce nine times, which is a lot of
         * the most expensive packet Reticulum has.
         */
        fun shouldGreet(now: Long): Boolean {
            val last = lastGreet
            if (last != null && now - last < GREET_MIN_INTERVAL_MS) return false
            lastGreet = now
            return true
        }

        /**
         * The member a 32-bit sender id belongs to, or null.
         *
         * The position wire format has room for four bytes of identity, not
         * sixteen -- the truncation pivot 1 objected to, except that here it is
         * a *lookup key* rather than an identity, and membership is the table
         * that turns it back into a whole destination hash. A collision returns
         * null rather than a guess: attributing one responder's position to
         * another is not a failure to resolve quietly.
         */
        fun resolveSenderId(senderId: Int, now: Long): ByteArray? =
            members(now).filter { senderIdFor(it) == senderId }.singleOrNull()

        val size: Int get() = members.size
    }

    /** The four bytes a node puts in its own position reports. */
    fun senderIdFor(destinationHash: ByteArray): Int {
        require(destinationHash.size >= 4) { "destination hash is too short" }
        var value = 0
        for (index in 0 until 4) value = (value shl 8) or (destinationHash[index].toInt() and 0xFF)
        return value
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var difference = 0
        for (index in a.indices) difference = difference or (a[index].toInt() xor b[index].toInt())
        return difference == 0
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.unHex() = ByteArray(length / 2) {
        substring(it * 2, it * 2 + 2).toInt(16).toByte()
    }
}
