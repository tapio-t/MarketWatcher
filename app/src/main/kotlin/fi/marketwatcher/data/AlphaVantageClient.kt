package fi.marketwatcher.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Minimal Alpha Vantage client for VOO's daily closes and dividends (free key: 25 requests a day,
 * personal non-commercial use). Alpha Vantage takes the key only as the apikey query parameter:
 * requests go only to www.alphavantage.co over HTTPS, redirects are not followed, and error texts
 * never include the URL (see [Redact] for the safety net).
 */
class AlphaVantageClient(private val apiKey: String) {

    /** The latest 100 daily closes (free "compact" size), date → close. */
    fun dailyCloses(symbol: String = SYMBOL): Map<String, Double> =
        AlphaVantageParse.dailyCloses(get(listOf("function" to "TIME_SERIES_DAILY", "symbol" to symbol, "outputsize" to "compact")))

    /** Ex-dividend date → amount (NaN when the amount is not given). Empty if Alpha Vantage has none for [symbol]. */
    fun dividends(symbol: String = SYMBOL): Map<String, Double> =
        AlphaVantageParse.dividends(get(listOf("function" to "DIVIDENDS", "symbol" to symbol)))

    private fun get(params: List<Pair<String, String>>): String {
        require(apiKey.isNotBlank()) { "Add your Alpha Vantage API key in Settings." }
        val q = (params + ("apikey" to apiKey)).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        try {
            val conn = URL("https://www.alphavantage.co/query?$q").openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false   // never send the key on to another host
            try {
                val code = conn.responseCode
                if (code != 200) throw AlphaVantageException("Alpha Vantage returned HTTP $code.")
                return conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        } catch (e: IOException) {
            // The platform's message can contain the URL with the key: never pass it on.
            throw AlphaVantageException("Couldn't reach Alpha Vantage (${e.javaClass.simpleName}).")
        }
    }

    companion object {
        const val SYMBOL = "VOO"
    }
}

class AlphaVantageException(msg: String, val keyRejected: Boolean = false) : Exception(msg)

/** Reads Alpha Vantage replies. Pure JSON, so unit tests cover it. */
object AlphaVantageParse {
    private const val SERIES = "Time Series (Daily)"

    fun dailyCloses(json: String): Map<String, Double> {
        val o = parse(json)
        val ts = o.optJSONObject(SERIES) ?: throw errorOf(o)
        val out = java.util.TreeMap<String, Double>()
        for (d in ts.keys()) {
            val c = ts.optJSONObject(d)?.optString("4. close")?.toDoubleOrNull() ?: continue
            if (d.length == 10 && c.isFinite() && c > 0) out[d] = c
        }
        return out
    }

    /**
     * Expected shape: {"symbol": "VOO", "data": [{"ex_dividend_date": "2026-09-26", "amount": "1.74", …}]}.
     * The docs show no sample, so a few likely names are accepted.
     */
    fun dividends(json: String): Map<String, Double> {
        val o = parse(json)
        val data: JSONArray = o.optJSONArray("data") ?: run {
            if (o.has("Error Message") || o.has("Information") || o.has("Note")) throw errorOf(o)
            return emptyMap()
        }
        val out = java.util.TreeMap<String, Double>()
        for (i in 0 until data.length()) {
            val r = data.optJSONObject(i) ?: continue
            val d = listOf("ex_dividend_date", "exDividendDate", "ex_date").map { r.optString(it) }.firstOrNull { it.length == 10 }
                ?: continue
            out[d] = r.optString("amount").toDoubleOrNull()?.takeIf { it.isFinite() && it > 0 } ?: Double.NaN
        }
        return out
    }

    private fun parse(json: String): JSONObject = try {
        JSONObject(json)
    } catch (e: Exception) {
        throw AlphaVantageException("Alpha Vantage sent an unreadable reply.")
    }

    /**
     * A reply without data. Undocumented; seen in practice: "Error Message" for an invalid key or
     * symbol, "Information" (or the older "Note") when the daily limit is used up.
     */
    private fun errorOf(o: JSONObject): AlphaVantageException {
        val msg = listOf("Error Message", "Information", "Note").map { o.optString(it) }.firstOrNull { it.isNotBlank() }
            ?: return AlphaVantageException("Alpha Vantage sent no data.")
        val lower = msg.lowercase()
        val rejected = "apikey" in lower && ("invalid" in lower || "missing" in lower)
        return AlphaVantageException("Alpha Vantage: " + Redact.text(msg).take(200), keyRejected = rejected)
    }
}

/** How often VOO may be downloaded: at most once an hour and [MAX_PER_DAY] times a New York day. */
object AvBudget {
    const val MAX_PER_DAY = 12
    const val MIN_MINUTES = 60L
    /** Dividends are re-read weekly. */
    const val DIVIDENDS_DAYS = 7L
    val NEW_YORK: java.time.ZoneId = java.time.ZoneId.of("America/New_York")
    /** VOO's daily close is looked for from this time, New York. */
    val CLOSES_AVAILABLE: java.time.LocalTime = java.time.LocalTime.of(16, 30)

    fun allowed(lastAtMs: Long, day: String?, count: Int, nowMs: Long, today: String): Boolean =
        nowMs - lastAtMs >= MIN_MINUTES * 60_000 && (day != today || count < MAX_PER_DAY)

    /**
     * The newest US weekday whose close should exist at [now]: today from 16:30 New York time (half an
     * hour after the close; 23:30 Helsinki), else the weekday before (market holidays are not known;
     * the request limits cover them). Until 0.23 this was 18:00; 0.24 moved it earlier so the
     * sharp-fall Panic can show an estimate on the evening of the fall.
     */
    fun expectedDate(now: java.time.ZonedDateTime): String {
        val ny = now.withZoneSameInstant(NEW_YORK)
        var d = if (ny.toLocalTime() >= CLOSES_AVAILABLE) ny.toLocalDate() else ny.toLocalDate().minusDays(1)
        while (d.dayOfWeek.value > 5) d = d.minusDays(1)
        return d.toString()
    }
}
