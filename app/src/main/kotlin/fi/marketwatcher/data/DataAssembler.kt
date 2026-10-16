package fi.marketwatcher.data

import fi.marketwatcher.engine.Day
import java.time.LocalDate

/**
 * Joins the eight series onto the S&P 500's trading days, using each value only from
 * the date it was actually published (no look-ahead):
 *   daily series (SP500, VIX, VIX3M, DGS2)  same observation date
 *   NFCI      week ending Friday F          usable from F + 5 days (Wednesday release)
 *   Sahm      month M (dated the 1st)       usable from the 1st + 1 month + 7 days (after the jobs report)
 *   Buffett   quarter Q (dated its 1st day) usable from quarter end + 75 days (Z.1 release)
 * Every slow value keeps its FRED observation date, which the app shows under the value.
 */
object DataAssembler {

    /** A value usable from [usable], observed (dated by FRED) on [observed]. */
    data class Asof(val usable: LocalDate, val observed: String, val value: Double)

    private fun lagged(values: Map<String, Double>, lag: (LocalDate) -> LocalDate): List<Asof> =
        values.map { (d, v) -> Asof(lag(LocalDate.parse(d)), d, v) }.sortedBy { it.usable }

    /** Index of the last entry usable on or before t, or -1. */
    private fun asofIndex(list: List<Asof>, t: LocalDate): Int {
        var lo = 0; var hi = list.size - 1; var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (!list[mid].usable.isAfter(t)) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }

    fun buffettRatios(equitiesMillions: Map<String, Double>, gdpBillions: Map<String, Double>): Map<String, Double> =
        equitiesMillions.mapNotNull { (d, eq) -> gdpBillions[d]?.let { d to eq / (it * 1000.0) } }.toMap()

    /** Point-in-time lookups for the slower series. */
    class Lookups(
        dgs2: Map<String, Double>, nfci: Map<String, Double>, sahm: Map<String, Double>,
        equities: Map<String, Double>, gdp: Map<String, Double>,
        t10y2y: Map<String, Double> = emptyMap(),
        private val cape: CapeHistory? = null,
    ) {
        private val yc = lagged(t10y2y) { it }
        /** For each yield-curve day: start of the current inversion, or the last inverted day so far (only past data). */
        private val ycRunStart = arrayOfNulls<String>(yc.size)
        private val ycLastNeg = arrayOfNulls<String>(yc.size)
        init {
            var last: String? = null
            for (i in yc.indices) {
                if (yc[i].value < 0) {
                    ycRunStart[i] = if (i > 0 && yc[i - 1].value < 0) ycRunStart[i - 1] else yc[i].observed
                    last = yc[i].observed
                }
                ycLastNeg[i] = last
            }
        }
        private val nf = lagged(nfci) { it.plusDays(5) }
        private val sa = lagged(sahm) { it.plusMonths(1).plusDays(7) }
        private val bu = lagged(buffettRatios(equities, gdp)) { it.plusMonths(3).plusDays(75) }
        private val y2 = lagged(dgs2) { it }
        /** Percentile of each quarter's ratio within the history published up to it. */
        private val buPct = DoubleArray(bu.size) { b -> (0..b).count { bu[it].value <= bu[b].value }.toDouble() / (b + 1) }

        /** Fills NFCI, Sahm, 2-year yield, yield curve, Buffett ratio and CAPE as they were known on [d]'s date. */
        fun fill(d: Day, keepNfci: Boolean = false): Day {
            val t = LocalDate.parse(d.date)
            val n = asofIndex(nf, t)
            val s = asofIndex(sa, t)
            val b = asofIndex(bu, t)
            val y = asofIndex(y2, t)
            val y6 = asofIndex(y2, t.minusDays(182))
            val c = asofIndex(yc, t)
            val cv = cape?.valueOn(t, d.spx)
            val nfciPart = if (keepNfci) d else d.copy(
                nfci = if (n >= 1) nf[n].value else null,
                nfciPrev = if (n >= 1) nf[n - 1].value else null,
                nfciDate = if (n >= 1) nf[n].observed else null,
                nfci4w = if (n >= 4) nf[n - 4].value else null,
                nfci4wDate = if (n >= 4) nf[n - 4].observed else null,
                nfci8w = if (n >= 8) nf[n - 8].value else null,
                nfci8wDate = if (n >= 8) nf[n - 8].observed else null,
            )
            return nfciPart.copy(
                sahm = if (s >= 0) sa[s].value else null,
                sahmDate = if (s >= 0) sa[s].observed else null,
                dgs2 = if (y >= 0) y2[y].value else null,
                dgs2SixMonthsAgo = if (y6 >= 0) y2[y6].value else null,
                dgs2Date = if (y >= 0) y2[y].observed else null,
                dgs2SixMonthsAgoDate = if (y6 >= 0) y2[y6].observed else null,
                t10y2y = if (c >= 0) yc[c].value else null,
                t10y2yDate = if (c >= 0) yc[c].observed else null,
                t10y2yInvertedSince = if (c >= 0) ycRunStart[c] else null,
                t10y2yLastInverted = if (c >= 0 && yc[c].value >= 0) ycLastNeg[c] else null,
                cape = cv?.value,
                capeDate = cv?.month?.atDay(1)?.toString(),
                capeEstimated = cv?.estimated ?: false,
                capePct = cv?.percentile,
                buffett = if (b >= 0) bu[b].value else null,
                buffettPct = if (b >= 0) buPct[b] else null,
                buffettDate = if (b >= 0) bu[b].observed else null,
            )
        }
    }

    fun assemble(
        spx: Map<String, Double>, vix: Map<String, Double>, vix3m: Map<String, Double>,
        lookups: Lookups,
    ): List<Day> =
        spx.keys.filter { it in vix && it in vix3m }.sorted().map { ds ->
            lookups.fill(Day(date = ds, spx = spx.getValue(ds), vix = vix.getValue(ds), vix3m = vix3m.getValue(ds)))
        }
}
