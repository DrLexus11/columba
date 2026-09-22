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
        if (expectedHash != null && expectedHash != hash) return null
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

    fun nameOf(hash: String): String =
        File(root, "$hash.name").takeIf { TakFiles.isHash(hash) && it.isFile }?.readText() ?: hash
}
