package network.columba.app.service.tak

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Teams as Reticulum GROUP destinations, so broadcast needs no central relay.
 *
 * The Kotlin half of `tools/tak_groups.py`. TAK already separates sending to a
 * contact from broadcasting to a team, and Reticulum already has both; mapping
 * one onto the other means a team keeps working with the command post out of
 * range, because command is a member of the group and never a hop in it.
 *
 * Joining a team is a key, not a registration: the key derives from the team
 * name and the fleet secret, so two nodes provisioned alike agree on the
 * destination without being told about each other.
 */
object TakGroups {
    const val APP = "rnstransport"
    val ASPECTS = listOf("tak", "group")

    /**
     * Domain separation, so this secret cannot be reused as something else.
     *
     * The trailing NUL is part of the domain and must stay one: the Python side
     * uses b"urtn-tak-group-v1\\0", and a space here instead would make every
     * key on this side differ from every key on that one, which presents as a
     * team whose members cannot hear each other.
     */
    /**
     * Shared with [TakMembership], which derives the team tag from the same
     * secret and must use the same separator. Copying the literal into a
     * second file is how two derivations of one secret quietly diverge.
     */
    internal val DOMAIN = "urtn-tak-group-v1\u0000".toByteArray(Charsets.US_ASCII)
    private val IDENTITY_INFO = "identity\u0000".toByteArray(Charsets.US_ASCII)
    private val ASPECT_INFO = "aspect\u0000".toByteArray(Charsets.US_ASCII)

    /**
     * 64, not 32. RNS's Token picks its cipher from key length: 32 bytes gives
     * AES-128-CBC, 64 gives AES-256, and 32 is accepted without complaint. Team
     * traffic should not take the weaker cipher by omission.
     */
    const val GROUP_KEY_BYTES = 64
    const val MIN_SECRET_BYTES = 16

    /**
     * Case and surrounding space must not fork a team in half.
     *
     * "cyan" and "Cyan " are one team to an operator, and two members whose
     * keys disagree would each broadcast happily and hear nothing -- the least
     * debuggable failure this design can produce.
     */
    fun normaliseTeam(name: String): String {
        val collapsed = name.trim().split(Regex("\\s+")).joinToString(" ").lowercase()
        require(collapsed.isNotEmpty()) { "team name must not be empty" }
        return collapsed
    }

    fun requireSecret(secret: ByteArray): ByteArray {
        require(secret.size >= MIN_SECRET_BYTES) {
            "fleet secret must be at least $MIN_SECRET_BYTES bytes"
        }
        return secret
    }

    /**
     * ASCII whitespace, matching bytes.strip()'s default on the Python side.
     *
     * Spelled out rather than left to String.trim(), which is Unicode-aware
     * and strips more than this. A non-breaking space in a pasted secret would
     * then be removed here and kept there, deriving two different keys from
     * what an operator typed once.
     */
    private const val ASCII_WHITESPACE = " \t\n\r\u000B\u000C"

    /**
     * The exact bytes the Python side derives from, given the same secret.
     *
     * `tools/tak_groups.py` strips whichever way the secret arrived: a file
     * written with `echo` carries a trailing newline, and an operator pasting
     * the same characters into a phone may add a space. Both must reach the
     * same key, or each member broadcasts happily and hears nothing.
     */
    fun secretBytes(secret: String): ByteArray =
        requireSecret(secret.trim { it in ASCII_WHITESPACE }.toByteArray(Charsets.UTF_8))

    /**
     * Whether the secret as typed would derive a usable key.
     *
     * The same trimming and the same byte count [secretBytes] applies, so the
     * UI cannot disagree with the derivation about what is acceptable. Counting
     * characters instead rejected a short multibyte secret that is perfectly
     * long enough in bytes, and treated Unicode whitespace differently from the
     * path that actually derives the key.
     */
    fun secretIsUsable(secret: String): Boolean =
        secret.trim { it in ASCII_WHITESPACE }.toByteArray(Charsets.UTF_8).size >= MIN_SECRET_BYTES

    /** The shared symmetric key for a team. */
    fun groupKey(team: String, secret: ByteArray): ByteArray =
        hmac(requireSecret(secret), DOMAIN + teamBytes(team)).copyOf(GROUP_KEY_BYTES)

    /** The 64-byte private key every member derives alike for the team identity. */
    fun groupIdentityKey(team: String, secret: ByteArray): ByteArray =
        hmac(requireSecret(secret), DOMAIN + IDENTITY_INFO + teamBytes(team))

    /**
     * Aspects naming this team without naming it in the clear.
     *
     * RNS derives a GROUP destination's hash from app_name and aspects alone,
     * and that address is visible to anyone who can hear an announce. A literal
     * "cyan" would let a passive listener enumerate the teams on the mesh and
     * count each one's traffic. The label is an HMAC instead: stable for
     * everyone holding the secret, opaque to everyone else.
     */
    fun groupAspects(team: String, secret: ByteArray): List<String> {
        // SHA-256 here, SHA-512 for the keys, because that is what the Python
        // side does. The label is truncated to 32 hex characters either way, so
        // using the wrong hash produces a perfectly plausible aspect that
        // simply addresses a different destination -- the two sides would each
        // join a team of one.
        val label = hmac(requireSecret(secret), DOMAIN + ASPECT_INFO + teamBytes(team), "HmacSHA256")
            .joinToString("") { "%02x".format(it) }.take(32)
        return ASPECTS + label
    }

    private fun teamBytes(team: String) = normaliseTeam(team).toByteArray(Charsets.UTF_8)

    private fun hmac(
        key: ByteArray,
        message: ByteArray,
        algorithm: String = "HmacSHA512",
    ): ByteArray {
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key, algorithm))
        return mac.doFinal(message)
    }
}
