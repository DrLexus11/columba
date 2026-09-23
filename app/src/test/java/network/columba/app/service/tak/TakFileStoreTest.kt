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
}
