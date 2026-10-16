package fi.marketwatcher.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Minimal FRED client (API v1, fred/series/observations; the v2 API only serves whole releases).
 * FRED requires the key as the api_key query parameter; requests go only to api.stlouisfed.org
 * over HTTPS, and error texts never include the URL (see [Redact] for the safety net).
 */
class FredClient(private val apiKey: String) {

    fun observations(seriesId: String, start: LocalDate?): Map<String, Double> {
        val params = mutableListOf("series_id" to seriesId)
        if (start != null) params += "observation_start" to start.toString()
        val obs = JSONObject(get("series/observations", params, seriesId)).getJSONArray("observations")
        val out = LinkedHashMap<String, Double>(obs.length())
        for (i in 0 until obs.length()) {
            val o = obs.optJSONObject(i) ?: continue
            // "." = holiday / missing; anything else unparseable is skipped instead of failing the refresh
            val v = o.optString("value").toDoubleOrNull() ?: continue
            if (!v.isFinite()) continue
            val d = o.optString("date")
            if (d.length == 10) out[d] = v
        }
        return out
    }

    /** One small request to check the key before it is saved. Throws [FredException] if FRED rejects it. */
    fun verifyKey() { get("series", listOf("series_id" to "GDP"), "GDP") }

    private fun get(path: String, params: List<Pair<String, String>>, what: String): String {
        require(apiKey.isNotBlank()) { "Add your FRED API key in Settings." }
        val all = params + listOf("api_key" to apiKey, "file_type" to "json")
        val q = all.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        val conn = URL("https://api.stlouisfed.org/fred/$path?$q").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false   // never send the key on to another host
        try {
            val code = conn.responseCode
            if (code != 200) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code == 400 && "api_key" in err)
                    throw FredException("FRED rejected the API key. Check it at fredaccount.stlouisfed.org/apikeys.", keyRejected = true)
                throw FredException("FRED $what returned HTTP $code.")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}

class FredException(msg: String, val keyRejected: Boolean = false) : Exception(msg)
