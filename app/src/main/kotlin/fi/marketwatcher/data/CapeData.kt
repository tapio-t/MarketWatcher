package fi.marketwatcher.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/**
 * Robert Shiller's monthly total return CAPE (shillerdata.com, ie_data.xls).
 * Bundled: 1881-01 to 2026-09 (the app shows it from 2008; earlier months are used only to rank
 * values for the top-10% amber rule). A newer file downloaded or imported in Settings replaces it.
 *
 * Value for a day:
 *  - months before the latest official month: that month's official value;
 *  - the latest official month and later: an estimate, the latest official value scaled by the
 *    S&P 500 change since that month's price (the 10-year earnings average barely moves in a month).
 */
class CapeHistory(val months: List<Entry>, val source: String) {
    data class Entry(val month: YearMonth, val price: Double, val value: Double)
    data class Value(val value: Double, val month: YearMonth, val estimated: Boolean, val percentile: Double)

    private val sortedValues = months.map { it.value }.sorted()
    /** Percentile of each month within the history up to and including it. */
    private val pct = DoubleArray(months.size).also { arr ->
        val seen = ArrayList<Double>(months.size)
        months.forEachIndexed { i, e ->
            val at = seen.binarySearch(e.value).let { if (it < 0) -it - 1 else it }
            seen.add(at, e.value)
            arr[i] = seen.count { it <= e.value }.toDouble() / seen.size
        }
    }
    private val index = months.withIndex().associate { (i, e) -> e.month to i }

    val lastOfficial: Entry? get() = months.lastOrNull()

    fun valueOn(date: LocalDate, spx: Double): Value? {
        val last = months.lastOrNull() ?: return null
        val m = YearMonth.from(date)
        if (m < last.month) {
            val i = index[m] ?: return null
            return Value(months[i].value, m, estimated = false, percentile = pct[i])
        }
        val est = last.value * spx / last.price
        val rank = sortedValues.count { it <= est }.toDouble() / sortedValues.size
        return Value(est, last.month, estimated = true, percentile = rank)
    }

    fun toJson(): String = JSONObject().apply {
        put("source", source)
        put("months", JSONArray().apply {
            months.forEach { put(JSONArray().put(it.month.toString()).put(it.price).put(it.value)) }
        })
    }.toString()

    companion object {
        private const val ASSET = "cape_tr_shiller.json"
        private const val FILE = "cape_tr_shiller.json"     // newer copy in the app's private files

        fun parse(json: String): CapeHistory {
            val o = JSONObject(json)
            val a = o.getJSONArray("months")
            val list = (0 until a.length()).map { i ->
                val r = a.getJSONArray(i)
                Entry(YearMonth.parse(r.getString(0)), r.getDouble(1), r.getDouble(2))
            }
            return CapeHistory(list, o.optString("source"))
        }

        fun fromShiller(rows: List<ShillerXls.Month>, source: String) =
            CapeHistory(rows.map { Entry(YearMonth.of(it.year, it.month), it.price, it.trCape) }, source)

        @Volatile private var cached: Pair<Long, CapeHistory>? = null

        /** The newer of the downloaded/imported copy and the bundled one; kept in memory until the file changes. */
        fun load(ctx: Context): CapeHistory {
            val f = File(ctx.filesDir, FILE)
            val stamp = if (f.exists()) f.lastModified() else 0L
            cached?.let { (s, h) -> if (s == stamp) return h }
            // The bundled file is missing when built without the private MarketWatcher-data repository.
            val bundled = runCatching { parse(ctx.assets.open(ASSET).bufferedReader().use { it.readText() }) }.getOrNull()
                ?: CapeHistory(emptyList(), "none")
            val stored = if (f.exists()) runCatching { parse(f.readText()) }.getOrNull() else null
            val h = if (stored != null && (stored.lastOfficial?.month ?: YearMonth.of(1, 1)) >=
                (bundled.lastOfficial?.month ?: YearMonth.of(1, 1))) stored else bundled
            cached = stamp to h
            return h
        }

        /** Saves [h] if it is a sane, complete Shiller file not older than what the app has. Returns true if saved. */
        fun saveIfNewer(ctx: Context, h: CapeHistory): Boolean {
            val cur = load(ctx)
            val newLast = h.lastOfficial ?: return false
            if (h.months.size < 1500 || h.months.any { it.value !in 3.0..100.0 }) return false
            if (newLast.month < (cur.lastOfficial?.month ?: YearMonth.of(1, 1))) return false
            val f = File(ctx.filesDir, FILE)
            val tmp = File(ctx.filesDir, "$FILE.tmp")
            tmp.writeText(h.toJson())
            return tmp.renameTo(f)
        }
    }
}
