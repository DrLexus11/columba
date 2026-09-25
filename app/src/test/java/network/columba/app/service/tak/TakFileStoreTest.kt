package network.columba.app.service.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** What the retention page lists, and that deleting lets a file go entirely. */
class TakFileStoreTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `held files are listed as sent, received or preview`() {
        val store = TakFileStore(folder.newFolder())
        val sent = store.put("sent".toByteArray(), "Recon1.zip")!!
        store.grant(sent, listOf(ByteArray(16) { 1 }))
        store.put("received".toByteArray(), "RECON2.zip")
        store.put("preview".toByteArray(), "20260922_182231_preview.zip")

        val kinds = store.list().associate { it.name to it.kind }
        assertEquals(TakFileStore.Kind.SENT, kinds["Recon1.zip"])
        assertEquals(TakFileStore.Kind.RECEIVED, kinds["RECON2.zip"])
        assertEquals(TakFileStore.Kind.PREVIEW, kinds["20260922_182231_preview.zip"])
        assertEquals(3, store.list().size)
    }

    @Test
    fun `deleting a file removes it and what was kept beside it`() {
        val root = folder.newFolder()
        val store = TakFileStore(root)
        val hash = store.put("sent".toByteArray(), "Recon1.zip")!!
        store.grant(hash, null)

        assertTrue(store.delete(hash))
        assertFalse(store.has(hash))
        assertEquals(0, root.listFiles()!!.size)
        assertFalse("a deleted file can no longer be fetched", store.mayFetch(hash, ByteArray(16) { 1 }) { true })
    }

    // ---- review notes 8 and 10 ----

    private fun lockOf(store: TakFileStore): Any =
        TakFileStore::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(store)

    /**
     * Note 8. The endpoint and the retention page each build a store over the
     * same directory, and each instance's @Synchronized locked only itself --
     * so a delete from one could run while the other was writing. The fix is
     * that every store over one directory shares one lock, so that is what is
     * asserted: the race itself cannot be made to happen on demand.
     */
    @Test
    fun `two stores over one directory share one lock, however the path is spelled`() {
        val dir = folder.newFolder("tak_files")
        val endpoint = TakFileStore(dir)
        java.io.File(dir.parentFile, "x").mkdirs()
        val retention = TakFileStore(java.io.File(dir.parentFile, "x/../tak_files"))

        assertTrue(lockOf(endpoint) === lockOf(retention))
        // Not one global lock: another directory does not wait on this one.
        assertFalse(lockOf(endpoint) === lockOf(TakFileStore(folder.newFolder("elsewhere"))))
    }

    /**
     * Note 10. Grants are written beside the file and renamed over it, so a
     * write cut off part way -- a crash, a battery pull -- leaves a torn temp
     * file and the real grants untouched. Rewritten in place, the same cut
     * truncated the grants and silently revoked everyone already offered.
     */
    @Test
    fun `a grant write cut off part way leaves the grants that were there`() {
        val dir = folder.newFolder()
        val store = TakFileStore(dir)
        val hash = store.put("sent".toByteArray(), "Recon1.zip")!!
        val alice = ByteArray(16) { 1 }
        store.grant(hash, listOf(alice))

        // What an interrupted rewrite leaves behind.
        java.io.File(dir, "$hash.grants.tmp").writeText("0101")

        assertTrue("the torn write must not revoke anyone", store.mayFetch(hash, alice) { false })
    }

    /** The next grant replaces a torn temp file rather than tripping over it. */
    @Test
    fun `a grant after a torn write completes and leaves no temp file`() {
        val dir = folder.newFolder()
        val store = TakFileStore(dir)
        val hash = store.put("sent".toByteArray(), "Recon1.zip")!!
        val alice = ByteArray(16) { 1 }
        val bob = ByteArray(16) { 2 }
        store.grant(hash, listOf(alice))
        java.io.File(dir, "$hash.grants.tmp").writeText("garbage")

        store.grant(hash, listOf(bob))

        assertTrue(store.mayFetch(hash, alice) { false })
        assertTrue(store.mayFetch(hash, bob) { false })
        assertFalse(java.io.File(dir, "$hash.grants.tmp").exists())
    }
}
