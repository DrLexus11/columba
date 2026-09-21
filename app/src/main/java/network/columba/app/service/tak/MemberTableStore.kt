package network.columba.app.service.tak

import android.util.Log
import java.io.File
import java.io.IOException

/**
 * The team table on disk, so a restart does not leave this node blind.
 *
 * The Kotlin half of `MemberRegistry.save`/`load` in `tools/tak_membership.py`.
 * Kept in the app's no-backup directory: the file is a roster -- callsigns,
 * roles and which nodes are one team -- and has no business in a cloud backup.
 */
class MemberTableStore(private val file: File) {
    companion object {
        private const val TAG = "MemberTableStore"
    }

    /** Restore into [registry]. A first start, or a damaged file, restores nothing. */
    fun load(registry: TakMembership.Registry, now: Long): Int =
        try {
            if (file.exists()) registry.restore(file.readText(), now) else 0
        } catch (error: IOException) {
            Log.w(TAG, "Could not read the team table: ${error.message}")
            0
        }

    /**
     * Save [registry], atomically.
     *
     * A half-written file read back after a crash would be worse than none, so
     * it is written beside the real one and renamed over it. A failure is
     * logged, never thrown: a node that cannot write its roster still serves
     * its team today.
     */
    fun save(registry: TakMembership.Registry) {
        try {
            val temporary = File(file.parentFile, "${file.name}.tmp")
            temporary.writeText(registry.snapshot())
            if (!temporary.renameTo(file)) throw IOException("rename failed")
        } catch (error: IOException) {
            Log.w(TAG, "Could not save the team table: ${error.message}")
        }
    }
}
