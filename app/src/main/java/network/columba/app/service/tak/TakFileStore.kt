package network.columba.app.service.tak

import java.io.File

/**
 * Files by SHA-256, in app-private storage.
 *
 * A file is only ever stored under the hash of its own bytes, so one that
 * arrived corrupted, or is not the one asked for, is refused rather than kept
 * under a name it does not deserve. The name ATAK gave it is kept beside it.
 */
class TakFileStore(private val root: File) {
    init {
        root.mkdirs()
    }

    private fun fileFor(hash: String): File {
        require(TakFiles.isHash(hash)) { "not a file hash" }
        return File(root, hash)
    }

    fun has(hash: String): Boolean = TakFiles.isHash(hash) && fileFor(hash).isFile

    /** Store bytes; their hash, or null if they are not the file expected. */
    @Synchronized
    fun put(data: ByteArray, name: String, expectedHash: String? = null): String? {
        val hash = TakFiles.sha256Hex(data)
        if (data.size > TakFiles.MAX_FILE_BYTES || (expectedHash != null && expectedHash != hash)) return null
        val target = fileFor(hash)
        if (!target.isFile) {
            val part = File(root, "$hash.part")
            part.writeBytes(data)
            if (!part.renameTo(target)) {
                part.delete()
                return null
            }
        }
        File(root, "$hash.name").writeText(name)
        return hash
    }

    fun read(hash: String): ByteArray? = if (has(hash)) fileFor(hash).readBytes() else null

    /**
     * Record who a notice for this file went to: member hashes, or null for
     * the whole team. Grants accumulate -- one file shared with A and then B
     * may be fetched by both.
     */
    @Synchronized
    fun grant(hash: String, members: List<ByteArray>?) {
        if (!TakFiles.isHash(hash)) return
        val grants = File(root, "$hash.grants")
        val lines = if (grants.isFile) grants.readLines().toMutableSet() else mutableSetOf()
        if (members == null) lines += TEAM else members.forEach { lines += it.toHex() }
        grants.writeText(lines.joinToString("\n"))
    }

    /** Whether [member] was sent a notice for this file. */
    fun mayFetch(hash: String, member: ByteArray, isMember: (ByteArray) -> Boolean): Boolean {
        val grants = File(root, "$hash.grants").takeIf { TakFiles.isHash(hash) && it.isFile }?.readLines() ?: return false
        return member.toHex() in grants || (TEAM in grants && isMember(member))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TEAM = "team"
    }

    fun nameOf(hash: String): String =
        File(root, "$hash.name").takeIf { TakFiles.isHash(hash) && it.isFile }?.readText() ?: hash
}
