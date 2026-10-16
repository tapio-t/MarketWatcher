package fi.marketwatcher.data

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate

/**
 * The nine FRED series the app uses. [historyFrom]: daily series whose full history back to that
 * date is fetched (small, and needed for the History tiles); others fetch the last 11 years.
 */
enum class Series(val id: String, val daily: Boolean, val label: String, val historyFrom: LocalDate? = null) {
    SP500("SP500", true, "S&P 500"),
    VIX("VIXCLS", true, "VIX"),
    VIX3M("VXVCLS", true, "VIX3M"),
    DGS2("DGS2", true, "2-year Treasury yield", LocalDate.of(2007, 1, 1)),
    T10Y2Y("T10Y2Y", true, "10-year minus 2-year Treasury yield", LocalDate.of(2007, 1, 1)),
    NFCI("NFCI", false, "Chicago Fed NFCI"),
    SAHM("SAHMREALTIME", false, "Real-time Sahm rule"),
    EQUITIES("NCBEILQ027S", false, "Nonfinancial corporate equities"),
    GDP("GDP", false, "GDP"),
}

data class CachedSeries(val values: Map<String, Double>, val fetchedAt: Instant?) {
    val lastDate: String? get() = values.keys.maxOrNull()
    val firstDate: String? get() = values.keys.minOrNull()
}

/**
 * One JSON file per series in the app's private storage, so the app works offline and
 * shows how old each series is. Daily series are fetched incrementally (last date − 60 days,
 * which also picks up late corrections); weekly, monthly and quarterly series are small and
 * are re-fetched in full, because they are revised.
 */
class SeriesCache(context: Context) {
    private val dir = File(context.filesDir, "fred").apply { mkdirs() }

    fun read(s: Series): CachedSeries {
        val f = File(dir, "${s.id}.json")
        if (!f.exists()) return CachedSeries(emptyMap(), null)
        val o = JSONObject(f.readText())
        val v = o.getJSONObject("values")
        val map = sortedMapOf<String, Double>()
        for (k in v.keys()) map[k] = v.getDouble(k)
        return CachedSeries(map, if (o.has("fetchedAt")) Instant.parse(o.getString("fetchedAt")) else null)
    }

    private fun write(s: Series, values: Map<String, Double>) {
        val o = JSONObject()
        o.put("fetchedAt", Instant.now().toString())
        o.put("values", JSONObject(values.toSortedMap() as Map<*, *>))
        File(dir, "${s.id}.json").writeText(o.toString())
    }

    /** Fetches what is missing and returns the merged series. */
    fun update(s: Series, fred: FredClient, dailyStart: LocalDate): CachedSeries {
        val cached = read(s)
        val merged: Map<String, Double> = if (s.daily) {
            val start = s.historyFrom ?: dailyStart
            // Full fetch when the cache is empty, or (one-time backfill) when a series with a fixed
            // history start isn't cached back that far. FRED itself keeps only ~10 years of SP500,
            // so other series are never "backfilled" against the rolling 11-year start.
            val first = cached.firstDate
            val histFrom = s.historyFrom
            val backfill = first == null ||
                (histFrom != null && LocalDate.parse(first).isAfter(histFrom.plusDays(14)))
            val from = if (backfill) start else LocalDate.parse(cached.lastDate!!).minusDays(60)
            cached.values.toMutableMap().apply { putAll(fred.observations(s.id, from)) }
        } else {
            fred.observations(s.id, null)
        }
        write(s, merged)
        return CachedSeries(merged.toSortedMap(), Instant.now())
    }
}
