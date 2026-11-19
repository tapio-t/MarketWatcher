package fi.marketwatcher.engine

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Display-only facts of one close from [CloseStats] (never used by the engine's states). */
data class CloseFacts(
    /** Closes in a row below the 200-day average up to this one (0 when at or above it); null before the average exists. */
    val belowRun: Int?,
    /** The first close of that run, or null when [belowRun] is 0 or null. */
    val belowRunStart: String?,
    /** The last −3.00% or worse day (as shown) before this one, and its change. */
    val prevVerySharp: String?,
    val prevVerySharpChange: Double?,
)

/**
 * Display-only statistics of the S&P 500 closes: runs of closes below the 200-day average and the
 * sharp-fall counts and gaps for History (0.25). Never used by the engine's states, not backtested.
 *
 * [dates] and [closes] are the engine's input days followed by the price-only days ([PriceDays.after]),
 * so the average is the same as the engine's. Counted from the first bundled close (03.01.2007): a run
 * or a gap that started before the History readings (2008) is counted in full. [estimated] days (VOO)
 * get their [CloseFacts] but are left out of the yearly counts and the gaps.
 */
class CloseStats(val dates: List<String>, closes: DoubleArray, estimated: BooleanArray = BooleanArray(dates.size)) {
    private val n = dates.size
    private val index = HashMap<String, Int>(n * 2).also { m -> dates.forEachIndexed { i, d -> m[d] = i } }
    private val run = IntArray(n) { -1 }                 // -1: no 200-day average yet
    private val prevVerySharp = IntArray(n) { -1 }
    private val bp = LongArray(n)                         // day change in basis points (0 on the first day)

    init {
        val t = Thresholds
        val pref = DoubleArray(n + 1)
        for (k in 0 until n) pref[k + 1] = pref[k] + closes[k]
        var lastVerySharp = -1
        for (i in 0 until n) {
            prevVerySharp[i] = lastVerySharp
            if (i > 0) bp[i] = SignalEngine.dayChangeBp(closes[i], closes[i - 1])
            if (i > 0 && bp[i] <= Fall.VERY_SHARP.bp) lastVerySharp = i
            if (i < t.SMA_LEN - 1) continue
            val sma = (pref[i + 1] - pref[i + 1 - t.SMA_LEN]) / t.SMA_LEN
            if (closes[i] < sma) {
                run[i] = if (i > 0 && run[i - 1] > 0) run[i - 1] + 1 else 1
            } else {
                run[i] = 0
            }
        }
    }

    // Yearly counts and medians use FRED closes only.
    private val counted = (0 until n).filter { it > 0 && !estimated[it] }

    /** The facts of the close on [date], or null when it is not in the data. */
    fun factsFor(date: String): CloseFacts? {
        val i = index[date] ?: return null
        val r = run[i]
        val p = prevVerySharp[i]
        return CloseFacts(
            belowRun = r.takeIf { it >= 0 },
            belowRunStart = if (r > 0) dates[i - r + 1] else null,
            prevVerySharp = if (p >= 0) dates[p] else null,
            prevVerySharpChange = if (p >= 0) bp[p] / 10_000.0 else null,
        )
    }

    private fun year(i: Int) = dates[i].substring(0, 4).toInt()

    /** Days of [fall] or worse (as shown) in [year]. */
    fun fallDays(year: Int, fall: Fall): Int = counted.count { year(it) == year && bp[it] <= fall.bp }

    /** The years with a counted day change: from the first year of closes to the newest FRED close. */
    val countedYears: IntRange = if (counted.isEmpty()) IntRange.EMPTY else year(counted.first())..year(counted.last())

    /** Each year of [countedYears] with its days of −2.00% or worse (very sharp and extreme included). */
    fun fallDaysPerYear(): List<Pair<Int, Int>> = countedYears.map { it to fallDays(it, Fall.SHARP) }

    /**
     * The dates of every very sharp fall (−3.00% or worse as shown, extreme falls included) from the
     * first close to the newest FRED close: the falls both gaps are measured between.
     */
    val verySharpDates: List<String> = counted.filter { bp[it] <= Fall.VERY_SHARP.bp }.map { dates[it] }

    private val gaps: List<Long> = verySharpDates.zipWithNext { a, b -> ChronoUnit.DAYS.between(LocalDate.parse(a), LocalDate.parse(b)) }

    /** Plain gap: median calendar days from one very sharp fall to the next, gaps inside a burst included. */
    val medianGapDays: Double = median(gaps.map { it.toDouble() })

    /** Burst gap: median calendar days between bursts, counting only gaps longer than [BURST_DAYS]. */
    val medianBurstGapDays: Double = median(gaps.filter { it > BURST_DAYS }.map { it.toDouble() })

    /** Number of bursts: very sharp falls less than [BURST_DAYS] + 1 days apart count as one. */
    val bursts: Int = if (verySharpDates.isEmpty()) 0 else 1 + gaps.count { it > BURST_DAYS }

    companion object {
        /** Very sharp falls at most this many calendar days apart belong to the same burst. */
        const val BURST_DAYS = 30L

        /** Notify (and turn the tile amber) at this many closes in a row below the 200-day average. */
        const val BELOW_RUN_ALERT = 10

        fun median(xs: List<Double>): Double {
            if (xs.isEmpty()) return Double.NaN
            val s = xs.sorted()
            val m = s.size / 2
            return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
        }

        /** From the engine's input [days] and the price-only days after them. */
        fun of(days: List<Day>, prices: List<PriceView>): CloseStats {
            val dates = days.map { it.date } + prices.map { it.date }
            val closes = DoubleArray(dates.size) { if (it < days.size) days[it].spx else prices[it - days.size].close }
            val est = BooleanArray(dates.size) { it >= days.size && prices[it - days.size].estimated }
            return CloseStats(dates, closes, est)
        }
    }
}
