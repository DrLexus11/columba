package network.columba.app.service.tak

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

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

    /**
     * The lock every store over this directory shares.
     *
     * Per directory rather than per instance, because there are two: the
     * endpoint's transfers and the retention page each build one over the same
     * `tak_files`. `@Synchronized` locked only the instance it was called on,
     * so a delete from the retention page could run while a transfer was
     * writing -- recreating the file just deleted, leaving an orphan `.name`,
     * or pulling a file out from under the server mid-read.
     */
    private val lock: Any = LOCKS.computeIfAbsent(root.canonicalPath) { Any() }

    private fun fileFor(hash: String): File {
        require(TakFiles.isHash(hash)) { "not a file hash" }
        return File(root, hash)
    }

    fun has(hash: String): Boolean = TakFiles.isHash(hash) && fileFor(hash).isFile

    /** Store bytes; their hash, or null if they are not the file expected. */
    fun put(data: ByteArray, name: String, expectedHash: String? = null): String? {
        val hash = TakFiles.sha256Hex(data)
        if (data.size > TakFiles.MAX_FILE_BYTES || (expectedHash != null && expectedHash != hash)) return null
        return synchronized(lock) {
            val target = fileFor(hash)
            val stored =
                target.isFile ||
                    File(root, "$hash.part").let { part ->
                        part.writeBytes(data)
                        part.renameTo(target).also { moved -> if (!moved) part.delete() }
                    }
            if (stored) File(root, "$hash.name").writeText(name)
            hash.takeIf { stored }
        }
    }

    /** Under the lock, so a delete cannot land between the check and the read. */
    fun read(hash: String): ByteArray? = synchronized(lock) { if (has(hash)) fileFor(hash).readBytes() else null }

    /**
     * Record who a notice for this file went to: member hashes, or null for
     * the whole team. Grants accumulate -- one file shared with A and then B
     * may be fetched by both.
     */
    fun grant(hash: String, members: List<ByteArray>?) {
        if (!TakFiles.isHash(hash)) return
        synchronized(lock) {
            val grants = File(root, "$hash.grants")
            val lines = if (grants.isFile) grants.readLines().toMutableSet() else mutableSetOf()
            if (members == null) lines += TEAM else members.forEach { lines += it.toHex() }
            replaceAtomically(grants, lines.joinToString("\n"))
        }
    }

    /**
     * Write [text] to [target] so a reader sees the old file or the new one,
     * never a torn one.
     *
     * The grants file used to be rewritten in place. A process death or power
     * loss mid-write left it truncated -- silently revoking recipients already
     * offered a file whose payload was still on disk -- and a concurrent
     * [mayFetch] could read the same half-written state. A rename within one
     * directory is atomic, so the file is written beside its target and moved
     * over it.
     */
    private fun replaceAtomically(target: File, text: String) {
        val temp = File(root, "${target.name}.tmp")
        temp.writeText(text)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /** Whether [member] was sent a notice for this file. */
    fun mayFetch(hash: String, member: ByteArray, isMember: (ByteArray) -> Boolean): Boolean {
        val grants =
            synchronized(lock) {
                File(root, "$hash.grants").takeIf { TakFiles.isHash(hash) && it.isFile }?.readLines()
            } ?: return false
        return member.toHex() in grants || (TEAM in grants && isMember(member))
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private companion object {
        const val TEAM = "team"

        /** One lock per directory, shared by every store over it. See [lock]. */
        val LOCKS = ConcurrentHashMap<String, Any>()
    }

    /** How much of a file being fetched is here already. */
    fun partialSize(hash: String): Long = File(root, "$hash.partial").takeIf { TakFiles.isHash(hash) }?.length() ?: 0L

    /** Add a part where it belongs. False if it is not the next part. */
    fun appendPartial(hash: String, offset: Long, data: ByteArray): Boolean =
        synchronized(lock) {
            val next = TakFiles.isHash(hash) && offset == partialSize(hash)
            if (next) File(root, "$hash.partial").appendBytes(data)
            next
        }

    /** The whole of a fetched file, removed from the partial store. */
    fun takePartial(hash: String): ByteArray? =
        synchronized(lock) {
            val file = File(root, "$hash.partial").takeIf { TakFiles.isHash(hash) && it.isFile }
            file?.readBytes()?.also { file.delete() }
        }

    /** One file held here, for the retention page. */
    data class Held(val hash: String, val name: String, val size: Long, val storedAtMs: Long, val kind: Kind)

    enum class Kind {
        /** This ATAK offered it; teammates it was offered to may still fetch it. */
        SENT,

        /** A teammate's file, fetched here. */
        RECEIVED,

        /** A QuickPic preview made here from a thumbnail over LoRa. */
        PREVIEW,
    }

    /** Every file held, newest first. */
    fun list(): List<Held> =
        root.listFiles().orEmpty()
            .filter { it.isFile && TakFiles.isHash(it.name) }
            .map { file ->
                val name = nameOf(file.name)
                val kind =
                    when {
                        File(root, "${file.name}.grants").isFile -> Kind.SENT
                        name.endsWith("_preview.zip") -> Kind.PREVIEW
                        else -> Kind.RECEIVED
                    }
                Held(file.name, name, file.length(), file.lastModified(), kind)
            }.sortedByDescending { it.storedAtMs }

    /**
     * Forget a file and everything kept beside it. A file this node offered
     * can then no longer be fetched by a teammate who has not yet done so.
     */
    fun delete(hash: String): Boolean {
        if (!TakFiles.isHash(hash)) return false
        return synchronized(lock) {
            listOf("", ".name", ".grants", ".grants.tmp", ".part", ".partial").forEach { File(root, hash + it).delete() }
            !has(hash)
        }
    }

    fun nameOf(hash: String): String =
        File(root, "$hash.name").takeIf { TakFiles.isHash(hash) && it.isFile }?.readText() ?: hash
}
