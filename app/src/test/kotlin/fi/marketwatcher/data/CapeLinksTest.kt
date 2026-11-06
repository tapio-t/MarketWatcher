package fi.marketwatcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URL

/** Finding ie_data.xls on shillerdata.com in each link form (made-up HTML, shaped like the real page). */
class CapeLinksTest {
    private val page = URL("https://shillerdata.com/")
    private fun find(html: String) = CapeLinks.find(html, page)?.toString()

    /** The form the site uses since autumn 2026: protocol-relative, next to other spreadsheets. */
    @Test fun protocolRelativeLink() {
        val html = """
            <a href="//img1.example.com/blobby/go/abc/downloads/111/Fig3-1%20(1).xls?ver=17" data-aid="DOWNLOAD_DOCUMENT_LINK_RENDERED">Download</a>
            <span data-aid="DOWNLOAD_FILE_NAME_RENDERED">ie_data.xls</span>
            <a href="//img1.example.com/blobby/go/abc/downloads/222/ie_data.xls?ver=17" data-aid="DOWNLOAD_DOCUMENT_LINK_RENDERED">Download</a>
        """.trimIndent()
        assertEquals("https://img1.example.com/blobby/go/abc/downloads/222/ie_data.xls?ver=17", find(html))
    }

    @Test fun absoluteLink() {
        assertEquals("https://files.example.com/x/ie_data.xls?v=2&w=3",
            find("""<a href='https://files.example.com/x/ie_data.xls?v=2&amp;w=3'>ie_data.xls</a>"""))
    }

    @Test fun siteRelativeLink() {
        assertEquals("https://shillerdata.com/s/ie_data.xls", find("""<a href="/s/ie_data.xls">Data</a>"""))
    }

    @Test fun escapedLinkInsideAScript() {
        val html = """<script>window.data = {"url":"\/\/img1.example.com\/d\/333\/ie_data.xls?ver=9","n":"x/y"};</script>"""
        assertEquals("https://img1.example.com/d/333/ie_data.xls?ver=9", find(html))
        assertEquals("https://img1.example.com/d/4/ie_data.xls",
            find("""{"u":"https://img1.example.com/d/4/ie_data.xls"}"""))
    }

    @Test fun onlyHttpsAndOnlyTheExactFileName() {
        assertNull("http only", find("""<a href="http://files.example.com/ie_data.xls">x</a>"""))
        assertNull("other file names", find("""<a href="/a/old_ie_data.xls">x</a> <a href="/a/ie_data.xlsx">y</a>"""))
        assertEquals("https://shillerdata.com/a/IE_DATA.XLS", find("""<a href="/a/old_ie_data.xls">x</a> <a href="/a/IE_DATA.XLS">y</a>"""))
    }

    @Test fun noLink() {
        assertNull(find("<p>Download ie_data.xls from the Data page.</p>"))
        assertNull(find(""))
    }

    @Test fun excelSignature() {
        val xls = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0)
        assertTrue(CapeLinks.isXls(xls))
        assertFalse(CapeLinks.isXls("<!DOCTYPE html><html>".toByteArray()))
        assertFalse(CapeLinks.isXls(ByteArray(3)))
    }
}
