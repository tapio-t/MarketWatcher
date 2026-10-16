package fi.marketwatcher.data

import android.content.Context
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Keeps the Shiller total return CAPE current. Shiller updates ie_data.xls about monthly and the
 * site changes the file's link with each update, so the app finds the link on shillerdata.com
 * (weekly, from the background check), downloads the file only when the link changed, reads it with
 * [ShillerXls] and keeps it only if it is complete and not older than what the app has.
 * Fallback: import a downloaded ie_data.xls from the phone (Settings).
 */
object CapeUpdater {
    private const val PAGE = "https://shillerdata.com/"
    private const val MAX_BYTES = 12 * 1024 * 1024
    private val LINK = Regex("""https://[^"'\s<>]+?ie_data\.xls(?:\?[^"'\s<>]*)?""", RegexOption.IGNORE_CASE)
    val CHECK_EVERY_MS = TimeUnit.DAYS.toMillis(7)

    /** Runs the check if a week has passed (or [force]). Blocking: call off the main thread. Returns the result text. */
    @Synchronized   // "Check now" and the weekly background check never overlap
    fun checkOnline(ctx: Context, force: Boolean = false): String {
        val s = Settings(ctx)
        if (!force && System.currentTimeMillis() - s.capeCheckedAt < CHECK_EVERY_MS) return s.capeCheckResult.orEmpty()
        val result = try {
            val html = String(get(PAGE, 2 * 1024 * 1024), Charsets.UTF_8)
            val link = LINK.find(html.replace("&amp;", "&"))?.value
                ?: throw IllegalStateException("Couldn't find ie_data.xls on shillerdata.com. Use Import file instead.")
            if (link == s.capeLastLink && !force) {
                "No new file."
            } else {
                val h = CapeHistory.fromShiller(ShillerXls.parse(get(link, MAX_BYTES)), "shillerdata.com ie_data.xls")
                if (CapeHistory.saveIfNewer(ctx, h)) {
                    s.capeLastLink = link
                    "Updated: official values to ${h.lastOfficial?.month}."
                } else "No newer data in the file."
            }
        } catch (e: Exception) {
            "Check failed: " + Redact.text(e.message ?: e.javaClass.simpleName)
        }
        s.capeCheckedAt = System.currentTimeMillis()
        s.capeCheckResult = result
        return result
    }

    /** Reads an ie_data.xls picked by the user. Blocking. Returns the result text. */
    @Synchronized
    fun import(ctx: Context, uri: Uri): String {
        val result = try {
            val bytes = ctx.contentResolver.openInputStream(uri)?.use { readCapped(it, MAX_BYTES) }
                ?: throw IllegalStateException("Couldn't open the file.")
            val h = CapeHistory.fromShiller(ShillerXls.parse(bytes), "Imported ie_data.xls")
            if (CapeHistory.saveIfNewer(ctx, h)) "Imported: official values to ${h.lastOfficial?.month}."
            else "Not imported: the file is incomplete or older than the data in the app."
        } catch (e: ShillerXls.FormatException) {
            "Not imported: ${e.message} Pick Shiller's ie_data.xls."
        } catch (e: Exception) {
            "Not imported: " + (e.message ?: e.javaClass.simpleName)
        }
        Settings(ctx).capeCheckResult = result
        return result
    }

    private fun get(url: String, max: Int): ByteArray {
        var u = URL(url)
        repeat(5) {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 30_000
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "MarketWatcher (personal Android app)")
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val next = URL(u, c.getHeaderField("Location") ?: throw IllegalStateException("Bad redirect."))
                    if (next.protocol != "https") throw IllegalStateException("Refused a non-HTTPS redirect.")
                    u = next
                    return@repeat
                }
                if (code != 200) throw IllegalStateException("HTTP $code from ${u.host}.")
                return c.inputStream.use { readCapped(it, max) }
            } finally { c.disconnect() }
        }
        throw IllegalStateException("Too many redirects.")
    }

    private fun readCapped(input: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) throw IllegalStateException("File is too large.")
        }
        return out.toByteArray()
    }
}
