package fi.marketwatcher.data

import java.net.URL

/**
 * Finds the ie_data.xls link on shillerdata.com. Pure Kotlin, so unit tests cover each link form.
 * The site has written the link in different forms: absolute (https://…), protocol-relative
 * (//img1.wsimg.com/…, since autumn 2026) or relative to the site, and pages built with scripts can
 * escape the slashes (\/ or /). Only a file named exactly ie_data.xls, reached over HTTPS, is taken.
 */
object CapeLinks {
    const val FILE_NAME = "ie_data.xls"

    /** A path-like token ending in ie_data.xls (not .xlsx), with an optional query (e.g. ?ver=…). */
    private val CANDIDATE = Regex("""[^"'\s<>()\\]*?ie_data\.xls(?=[?#"'\s<>()\\]|$)(?:\?[^"'\s<>()\\]*)?""", RegexOption.IGNORE_CASE)

    fun find(html: String, page: URL): URL? {
        val text = html.replace("&amp;", "&").replace("\\/", "/").replace("\\u002F", "/", ignoreCase = true)
        for (m in CANDIDATE.findAll(text)) {
            val token = m.value
            if ('/' !in token) continue   // the bare file name in the page text, not a link
            val url = runCatching { URL(page, token) }.getOrNull() ?: continue
            if (url.protocol != "https") continue
            val name = url.path.substringAfterLast('/')
            if (java.net.URLDecoder.decode(name, "UTF-8").equals(FILE_NAME, ignoreCase = true)) return url
        }
        return null
    }

    /** The start of every Excel 97–2003 file (an OLE compound file). */
    private val XLS_SIGNATURE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

    fun isXls(bytes: ByteArray): Boolean =
        bytes.size >= XLS_SIGNATURE.size && XLS_SIGNATURE.indices.all { bytes[it] == XLS_SIGNATURE[it] }
}
