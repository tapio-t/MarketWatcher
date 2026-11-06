package fi.marketwatcher.engine

/** An S&P 500 close estimated from VOO, for a day FRED has not published yet. */
data class Estimate(val close: Double, val exDividend: Boolean)

/**
 * Estimated S&P 500 closes from VOO (Vanguard S&P 500 ETF) for the days after FRED's last close.
 * Display only: they are used for the price tiles, the day change and the price notifications,
 * never by [SignalEngine] or History. FRED's close replaces each one when it is published.
 */
object VooEstimate {
    /**
     * The last FRED close ([spx]'s newest day) scaled by VOO's change since that day, one trading day
     * at a time. On an ex-dividend day VOO's price drops by the dividend and the index does not, so the
     * dividend is added back to that day's VOO close. [dividends]: ex-dividend date → amount per share;
     * NaN when the amount is unknown (the day is still marked, but not corrected).
     * Empty when VOO has no close for FRED's last day.
     */
    fun after(spx: Map<String, Double>, voo: Map<String, Double>, dividends: Map<String, Double>): Map<String, Estimate> {
        val anchor = spx.keys.maxOrNull() ?: return emptyMap()
        var prev = voo[anchor]?.takeIf { it > 0 && it.isFinite() } ?: return emptyMap()
        var est = spx.getValue(anchor)
        val out = java.util.TreeMap<String, Estimate>()
        for ((d, c) in voo.toSortedMap().tailMap(anchor)) {
            if (d == anchor || !(c > 0 && c.isFinite())) continue
            val div = dividends[d]
            val added = div?.takeIf { it.isFinite() && it > 0 } ?: 0.0
            est *= (c + added) / prev
            prev = c
            out[d] = Estimate(est, exDividend = div != null)
        }
        return out
    }
}
