package network.columba.app.service.tak

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * PR D2 review notes, the Kotlin half of `tests/test_tak_files_review.py`:
 * the same crafted frames refused, the same way, on both sides.
 */
class TakFilesReviewTest {
    @get:Rule val folder = TemporaryFolder()

    private val hash = "8096a5dc401a94e6166c0fb65a518d1078028a5a5b91e7e324fa2940584c35f7"

    private fun notice(size: String = "33503", extra: String = "", filename: String = "Recon1.zip") =
        "<event version=\"2.0\" uid=\"u\" type=\"b-f-t-r\" time=\"2026-09-22T10:39:32.087Z\" " +
            "start=\"2026-09-22T10:39:32.087Z\" stale=\"2026-09-22T10:39:42.087Z\" how=\"h-e\">" +
            "<point lat=\"41\" lon=\"29\" hae=\"0\" ce=\"0\" le=\"0\"/><detail>$extra<fileshare filename=\"$filename\" " +
            "senderUrl=\"https://192.168.240.1:8443/x\" sizeInBytes=\"$size\" sha256=\"$hash\" senderUid=\"S\" " +
            "senderCallsign=\"S\" name=\"Recon1\"/></detail></event>"

    private fun parse(xml: String) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray()))

    private fun shareAttribute(xml: String, name: String) =
        (parse(xml).getElementsByTagName("fileshare").item(0) as org.w3c.dom.Element).getAttribute(name)

    // Note 1
    @Test
    fun `a filename cannot start another response header`() {
        val header = TakFileServer(TakFileStore(folder.newFolder())).contentDisposition("evil.zip\"\r\nSet-Cookie: x=1")
        assertFalse(header.contains('\r') || header.contains('\n'))
        assertEquals(2, header.count { it == '"' })
        assertTrue(
            TakFileServer(TakFileStore(folder.newFolder())).contentDisposition("keşif.zip")
                .contains("filename*=UTF-8''ke%C5%9Fif.zip"),
        )
    }

    // Note 2
    @Test
    fun `only fileshare's senderUrl is rewritten, escaped and literal`() {
        val url = "http://127.0.0.1:8080/c?a=1&b=\$1"
        val decoyed = TakFiles.rewriteNotice(notice(extra = "<link senderUrl=\"https://decoy/\"/>"), url)
        assertTrue(decoyed.contains("<link senderUrl=\"https://decoy/\"/>"))
        assertEquals(url, shareAttribute(decoyed, "senderUrl"))

        val hidden = TakFiles.rewriteNotice(notice(filename = "a senderUrl='x'.zip"), "http://h/y")
        assertEquals("a senderUrl='x'.zip", shareAttribute(hidden, "filename"))
        assertEquals("http://h/y", shareAttribute(hidden, "senderUrl"))
    }

    // Note 6
    @Test
    fun `a size nothing here would store is not an offer`() {
        for (size in listOf("-1", "0", (TakFiles.MAX_FILE_BYTES + 1L).toString(), "lots")) {
            assertNull(size, TakFiles.parseNotice(notice(size = size)))
        }
        assertEquals(33503L, TakFiles.parseNotice(notice())!!.size)
    }

    // Note 7
    @Test
    fun `offers over the bound, with unknown flags, or impossible sizes are refused`() {
        val big = TakFileOffer.Offer(1, hash, 10, "a.zip", thumbnail = ByteArray(TakFileOffer.MAX_OFFER_BYTES))
        assertNull(TakFileOffer.decode(TakFileOffer.encode(big)))
        val flagged = TakFileOffer.encode(TakFileOffer.Offer(1, hash, 10, "a.zip"))
        flagged[41] = (flagged[41].toInt() or 0x80).toByte()
        assertNull(TakFileOffer.decode(flagged))
        assertNull(TakFileOffer.decode(TakFileOffer.encode(TakFileOffer.Offer(1, hash, TakFiles.MAX_FILE_BYTES + 1L, "a.zip"))))
    }

    // Note 8
    @Test
    fun `a callsign with XML metacharacters still makes a valid preview`() {
        val offer =
            TakFileOffer.Offer(
                1, hash, 10, "R&D <1>.jpg.zip",
                TakFileOffer.Point(409547417, 290934833, UUID.fromString("28b63bd7-7802-4802-a724-1adfef6e94e1")),
                ByteArray(20),
            )
        val (preview, _) = TakFileOffer.preview(offer, "A&B <x> 'y'", 1_790_000_000_000)
        ZipInputStream(ByteArrayInputStream(preview)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                val bytes = zip.readBytes()
                if (entry.name.endsWith(".cot")) {
                    val contact = parse(String(bytes)).getElementsByTagName("contact").item(0) as org.w3c.dom.Element
                    assertEquals("A&B <x> 'y' preview", contact.getAttribute("callsign"))
                }
                if (entry.name.endsWith(".xml")) parse(String(bytes))
            }
        }
    }
}
