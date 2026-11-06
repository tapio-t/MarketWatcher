package fi.marketwatcher.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant

/**
 * VOO closes and dividends from Alpha Vantage in the app's private storage (its terms allow keeping
 * the data for personal use). Each download is merged into what is saved, like the FRED cache.
 * Kept when the key is removed, so saved days still give estimates.
 */
class VooCache(context: Context) {
    private val dir = File(context.filesDir, "alphavantage").apply { mkdirs() }
    private val closesFile = File(dir, "${AlphaVantageClient.SYMBOL}.json")
    private val dividendsFile = File(dir, "${AlphaVantageClient.SYMBOL}_dividends.json")

    fun closes(): Map<String, Double> = read(closesFile).first
    fun dividends(): Map<String, Double> = read(dividendsFile).first
    fun dividendsFetchedAt(): Instant? = read(dividendsFile).second

    fun addCloses(new: Map<String, Double>) = write(closesFile, closes() + new)
    fun setDividends(all: Map<String, Double>) = write(dividendsFile, all)

    private fun read(f: File): Pair<Map<String, Double>, Instant?> {
        if (!f.exists()) return emptyMap<String, Double>() to null
        return runCatching {
            val o = JSONObject(f.readText())
            val v = o.getJSONObject("values")
            val map = sortedMapOf<String, Double>()
            for (k in v.keys()) map[k] = if (v.isNull(k)) Double.NaN else v.getDouble(k)
            map to (if (o.has("fetchedAt")) Instant.parse(o.getString("fetchedAt")) else null)
        }.getOrDefault(emptyMap<String, Double>() to null)
    }

    private fun write(f: File, values: Map<String, Double>) {
        val v = JSONObject()
        for ((k, x) in values.toSortedMap()) v.put(k, if (x.isFinite()) x else JSONObject.NULL)   // NaN = amount unknown
        f.writeText(JSONObject().put("fetchedAt", Instant.now().toString()).put("values", v).toString())
    }
}
