package fi.marketwatcher.engine

/**
 * The S&P 500 part of a day. FRED publishes the S&P 500 close about 03:00 Helsinki time, but VIX and
 * VIX3M only about 16:40 the next day; the engine (states) needs all three. A [PriceView] needs only
 * the close, so the price tiles, the day change and the sharp-fall check can use a day ~13 hours
 * before its full reading exists. [complete] tells which kind it is.
 */
data class PriceView(
    val date: String,
    val close: Double,
    val prevClose: Double?,
    val prevDate: String?,
    val ath: Double,
    val athDate: String,
    val sma200: Double?,
    /** The 200-day average 20 trading days earlier. */
    val sma200Prev: Double?,
    val sma200Rising: Boolean?,
    /** true: the day has a full engine reading; false: price only (VIX/VIX3M not published yet). */
    val complete: Boolean,
) {
    val drawdown: Double get() = close / ath - 1
    val distSma200: Double? get() = sma200?.let { close / it - 1 }
    /** Close vs the previous close, or null on the first day. */
    val change: Double? get() = prevClose?.let { close / it - 1 }
    val fall: Fall? get() = change?.let { Fall.of(it) }
    /** Same rules as [Reading.atOrBelowAverageShown] and [Reading.averageFalling]. */
    val atOrBelowAverageShown: Boolean get() = distSma200?.let { Math.round(it * 1000) <= 0 } ?: false
    val averageFalling: Boolean get() = sma200Rising == false
}

/** The price part of a full reading. */
fun Reading.price(): PriceView =
    PriceView(date, spx, prevClose, prevDate, ath, athDate, sma200, sma200Prev, sma200Rising, complete = true)

/** Sharp daily falls, compared in basis points (0.01%), so −2.00% as shown is a Sharp fall and −1.99% is not. */
enum class Fall(val title: String, val bp: Int) {
    EXTREME("Extreme fall", -500),
    VERY_SHARP("Very sharp fall", -300),
    SHARP("Sharp fall", -200);

    companion object {
        /** The largest tier [change] reaches, or null when it is not a sharp fall. */
        fun of(change: Double): Fall? {
            val b = Math.round(change * 10_000)
            return entries.firstOrNull { b <= it.bp }
        }
    }
}

object PriceDays {
    /**
     * Price-only days after the last engine day. [days] are the engine's input (each with VIX and
     * VIX3M), [closes] the FRED S&P 500 closes by date. The closes after the last engine day continue
     * the same sequence with the engine's own rules: a new high only when strictly above, the 200-day
     * average of the last 200 closes, "rising" against its value 20 trading days earlier.
     */
    fun after(days: List<Day>, closes: Map<String, Double>): List<PriceView> {
        val lastFull = days.lastOrNull()?.date ?: return emptyList()
        val extra = closes.filterKeys { it > lastFull }.toSortedMap()
        if (extra.isEmpty()) return emptyList()
        val dates = days.map { it.date } + extra.keys
        val px = days.map { it.spx } + extra.values
        val t = Thresholds
        val pref = DoubleArray(px.size + 1)
        for (k in px.indices) pref[k + 1] = pref[k] + px[k]
        fun sma(i: Int): Double? = if (i < t.SMA_LEN - 1) null else (pref[i + 1] - pref[i + 1 - t.SMA_LEN]) / t.SMA_LEN
        var ath = 0.0
        var athDate = ""
        val out = ArrayList<PriceView>(extra.size)
        for (i in px.indices) {
            if (px[i] > ath) { ath = px[i]; athDate = dates[i] }
            if (i < days.size) continue
            val s = sma(i)
            val sPrev = if (i >= t.SMA_SLOPE_DAYS) sma(i - t.SMA_SLOPE_DAYS) else null
            out += PriceView(dates[i], px[i], px[i - 1], dates[i - 1], ath, athDate, s, sPrev,
                if (s != null && sPrev != null) s > sPrev else null, complete = false)
        }
        return out
    }
}
