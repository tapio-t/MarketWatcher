package fi.marketwatcher.engine

import kotlin.math.max
import kotlin.math.min

/**
 * MarketWatcher engine v4 — line-by-line port of python/engine_v4.py (keep in sync;
 * SignalEngineGoldenTest checks every day of 2008–2026 against the Python output).
 *
 * No buy signals: every state is shown as "Suggested: <State>" and describes the market.
 * The context layer (credit stress, recession, rate shock, valuation) is reported next to
 * the state but does not change it, because it has not been backtested yet.
 */

data class Day(
    val date: String,
    val spx: Double,
    val vix: Double,
    val vix3m: Double,              // NaN allowed only in the 252-day warm-up
    val nfci: Double? = null,       // latest published NFCI (week ending Friday + 5 days)
    val nfciPrev: Double? = null,   // the published value one week earlier
    val nfci4w: Double? = null,     // the published value 4 weeks earlier (credit-stress rule)
    val nfci8w: Double? = null,     // the published value 8 weeks earlier (credit-stress rule)
    val sahm: Double? = null,       // real-time Sahm rule, as published
    val dgs2: Double? = null,       // 2-year Treasury yield
    val dgs2SixMonthsAgo: Double? = null,
    val buffett: Double? = null,    // nonfinancial corporate equities / GDP (ratio, 2.56 = 256%)
    val buffettPct: Double? = null, // percentile of the ratio in its own published history
    // FRED observation dates of the slower series (the daily ones share [date])
    val nfciDate: String? = null,   // week-ending Friday of [nfci]
    val nfci4wDate: String? = null, // week-ending Friday of [nfci4w]
    val nfci8wDate: String? = null, // week-ending Friday of [nfci8w]
    val sahmDate: String? = null,   // month of [sahm], dated its first day
    val buffettDate: String? = null, // quarter of [buffett], dated its first day
    val dgs2Date: String? = null,   // FRED date of [dgs2]
    val dgs2SixMonthsAgoDate: String? = null,
    val t10y2y: Double? = null,     // 10-year minus 2-year Treasury yield, percentage points
    val t10y2yDate: String? = null,
    val t10y2yInvertedSince: String? = null,   // first day of the current inversion (when inverted)
    val t10y2yLastInverted: String? = null,    // last inverted day before (when not inverted)
    val cape: Double? = null,       // Shiller total return CAPE (official monthly value or daily estimate)
    val capeDate: String? = null,   // month of the official value used, dated its first day
    val capeEstimated: Boolean = false, // true: official value scaled by the S&P 500 change since that month
    val capePct: Double? = null,    // percentile within Shiller's history since 1881
)

enum class State(val title: String, val message: String) {
    CALM("No panic", "No dip. The S&P 500 is within 7% of its all-time high."),
    PULLBACK("Pullback", "A routine pullback. Declines of this size happen in most years."),
    PANIC("Panic", "Panic selling: the volatility curve is inverted or VIX is extreme. " +
        "The low may be days away or much further down."),
    FIRST_WAVE_FADING("This wave fading", "This wave of fear is fading and the index has " +
        "stopped making new lows. In 2008-2026 about 4 in 10 of these were followed by another leg down."),
    NEW_WAVE("New wave", "Fear has returned after it faded: a new VIX high or a new low " +
        "in a downtrend. The earlier fading did not hold."),
    BEAR_GRIND("Bear grind", "A slow decline without panic, below a falling 200-day average. " +
        "Panic-based readings do not apply here."),
    UPTREND_RESTORED("Uptrend restored", "The index has closed above a rising 200-day average for 10 " +
        "days after a fall of 10% or more. Since 1950 this was followed by less than 5% further " +
        "decline in 18 of 22 dips.");

    val label: String get() = "Suggested: $title"
}

data class Context(
    val creditStress: Boolean?,
    val recession: Boolean?,
    val rateShock: Boolean?,
    val valuationHigh: Boolean?,
    val notes: List<String>,
    /** Display-only flags (amber tiles; no notifications, not backtested). */
    val yieldInverted: Boolean? = null,
    val capeHigh: Boolean? = null,
)

data class Reading(
    val date: String,
    val state: State,
    val rawState: State,
    val spx: Double,
    val vix: Double,
    val vix3m: Double,
    val drawdown: Double,
    val ath: Double,
    val vixRatio: Double,
    val vixPct: Double,
    val sma200: Double?,
    val sma200Rising: Boolean?,
    val distSma200: Double?,
    val episodeActive: Boolean,
    val episodeLow: Double?,
    val episodeMaxDd: Double?,
    val episodeVixPeak: Double?,
    val daysSinceLow: Int?,
    val uptrendRun: Int,
    val context: Context,
    val reasons: List<String>,
    /** The inputs for this day (Sahm, NFCI, Buffett ratio etc. as published). */
    val day: Day,
    /** Date of the all-time high [ath]. */
    val athDate: String,
    /** What the engine checked today, for the "why" dialog (see [Explain]). */
    val trace: Trace,
    /** The 200-day average 20 trading days earlier (the "rising/falling" comparison). */
    val sma200Prev: Double? = null,
    /** The previous trading day's close and date (null on the first day), for the day change. */
    val prevClose: Double? = null,
    val prevDate: String? = null,
) {
    val label: String get() = state.label
    /** Close at or below the 200-day average, as the "vs average" tile shows it (rounded to 0.1%). */
    val atOrBelowAverageShown: Boolean get() = distSma200?.let { Math.round(it * 1000) <= 0 } ?: false
    /** The 200-day average is lower than 20 trading days ago (the engine's "falling" test). */
    val averageFalling: Boolean get() = sma200Rising == false
    /** The VIX part of the Panic rule: VIX ≥ 30 and in the top 5% of its past year. */
    val vixPanicLevel: Boolean get() = vix >= Thresholds.PANIC_VIX && vixPct >= Thresholds.PANIC_VIX_PCT
    /** VIX at or above VIX3M: the volatility curve is in backwardation (the Panic threshold). */
    val backwardation: Boolean get() = vixRatio >= Thresholds.PANIC_RATIO
}

/** Which rule decided today's raw state, in the engine's checking order. */
enum class Branch { CALM, NEW_WAVE, RESTORED, FADING, PANIC, FADE_HOLDS, PANIC_HOLDS, BEAR, PULLBACK }

/** The engine's working values for one day, recorded so the decision can be explained exactly. */
data class Trace(
    val branch: Branch,
    val hadFadeBefore: Boolean,   // a fade happened earlier in this dip (before today's checks)
    val hadPanic: Boolean,        // panic seen in this dip, as used by today's checks
    val hadFade: Boolean,         // fading still on record, as used by today's checks
    val relapsed: Boolean,        // a new wave happened earlier: panic is shown as New wave
    val newLow: Boolean,
    val belowFalling: Boolean,
    val panicNow: Boolean,
    val panicBranch: Boolean,     // the panic rule applies today (stricter after a fade)
    val faded: Boolean,           // today's fading conditions are met
    val curveOk: Boolean,
    val newVixHigh: Boolean,
    val relapse: Boolean,
    val restored: Boolean,
    val peakAtFade: Double?,
    val epPeakRatio: Double?,
)

object Thresholds {
    const val DIP_START = -0.07
    const val DIP_END = -0.03
    const val BEAR_DD = -0.15
    const val PANIC_RATIO = 1.00
    const val PANIC_VIX = 30.0
    const val PANIC_VIX_PCT = 0.95
    const val FADE_RATIO = 1.00
    const val FADE_RATIO_DROP = 0.15
    const val FADE_VIX_FRACTION = 0.80
    const val LOW_HELD_DAYS = 3
    const val BOUNCE_OFF_LOW = 0.02
    const val RELAPSE_RATIO = 1.05
    const val RELAPSE_VIX_FRACTION = 0.90
    const val UPTREND_MIN_DD = -0.10
    const val UPTREND_DAYS = 10
    const val ENTER_DAYS = 2
    const val LOOKBACK = 252
    const val SMA_LEN = 200
    const val SMA_SLOPE_DAYS = 20
    // context layer (not backtested)
    const val SAHM_ON = 0.50
    // Credit stress (v0.15): NFCI above 0, or at/above the floor with a fast rise in any window.
    const val CREDIT_FLOOR = -0.300
    const val CREDIT_RISE_1W = 0.050
    const val CREDIT_RISE_4W = 0.115
    const val CREDIT_RISE_8W = 0.200
    const val RATE_SHOCK_PP = 1.00
    const val VALUATION_PCT = 0.90
    const val CAPE_PCT = 0.90         // total return CAPE in the top 10% of its history since 1881
    const val YIELD_INVERTED = 0.0    // 10y−2y below this = inverted curve
}

object SignalEngine {
    private val IMMEDIATE = setOf(State.PANIC, State.NEW_WAVE)

    /** One credit-stress window: its length, the change over it and the minimum rise it needs. */
    data class CreditWindow(val weeks: Int, val change: Double?, val minimum: Double) {
        val met: Boolean get() = change != null && Math.round(change * 1000) >= Math.round(minimum * 1000)
        val text: String get() = "$weeks week${if (weeks > 1) "s" else ""} " +
            String.format(java.util.Locale.US, "%+.3f", change ?: 0.0)
    }

    /** The 1-, 4- and 8-week changes of NFCI with their minimum rises (change is null when unknown). */
    fun creditWindows(d: Day): List<CreditWindow> {
        val t = Thresholds
        fun ch(prev: Double?) = if (d.nfci != null && prev != null) d.nfci - prev else null
        return listOf(CreditWindow(1, ch(d.nfciPrev), t.CREDIT_RISE_1W),
                      CreditWindow(4, ch(d.nfci4w), t.CREDIT_RISE_4W),
                      CreditWindow(8, ch(d.nfci8w), t.CREDIT_RISE_8W))
    }

    /** Windows whose rise meets its minimum. */
    fun creditRises(d: Day): List<CreditWindow> = creditWindows(d).filter { it.met }

    fun contextFor(d: Day): Context {
        val t = Thresholds
        // Credit stress: tighter than average (NFCI > 0), or NFCI at or above -0.300 with a rise of
        // +0.050 in 1 week, +0.115 in 4 weeks or +0.200 in 8 weeks, compared in thousandths (FRED's
        // precision). Before 0.15: any 4-week rise of 0.05, at any level (239 weeks on in 2007-2026).
        val rises = creditRises(d)
        val credit = if (d.nfci == null) null else d.nfci > 0 ||
            (Math.round(d.nfci * 1000) >= Math.round(t.CREDIT_FLOOR * 1000) && rises.isNotEmpty())
        val rec = d.sahm?.let { it >= t.SAHM_ON }
        val rate = if (d.dgs2 == null || d.dgs2SixMonthsAgo == null) null
                   else d.dgs2 - d.dgs2SixMonthsAgo >= t.RATE_SHOCK_PP
        val value = d.buffettPct?.let { it >= t.VALUATION_PCT }
        val notes = mutableListOf<String>()
        if (credit == true) notes += if (d.nfci!! > 0)
            "Financial conditions are tighter than average (NFCI ${String.format(java.util.Locale.US, "%+.3f", d.nfci)})."
        else "Financial stress is rising fast (NFCI ${String.format(java.util.Locale.US, "%+.3f", d.nfci)}; " +
            rises.joinToString(", ") { it.text } + ")."
        if (rec == true) notes += "Recession indicator is on (real-time Sahm rule ${f2(d.sahm!!)}). " +
            "In 2008 every fading panic was followed by further declines."
        if (rate == true) notes += "Rate shock: 2-year yield ${f2(d.dgs2!!)}%, up " +
            "${sgn2(d.dgs2 - d.dgs2SixMonthsAgo!!)} points in about 6 months (2022-type pressure)."
        if (value == true) notes += "Valuation is high: stock market value / GDP " +
            "${(d.buffett!! * 100).toInt()}%, in the top ${100 - Math.round(d.buffettPct!! * 100)}% " +
            "of its history. Context only, not a timing signal."
        return Context(credit, rec, rate, value, notes,
            yieldInverted = d.t10y2y?.let { it < t.YIELD_INVERTED },
            capeHigh = d.capePct?.let { it >= t.CAPE_PCT })
    }

    /** Evaluates each day using only data up to that day. Readings start at index 251. */
    fun run(days: List<Day>): List<Reading> {
        val t = Thresholds
        val out = ArrayList<Reading>()
        val closes = days.map { it.spx }
        val vixes = days.map { it.vix }
        val pref = DoubleArray(days.size + 1)
        for (k in closes.indices) pref[k + 1] = pref[k] + closes[k]
        fun sma(i: Int): Double? =
            if (i < t.SMA_LEN - 1) null else (pref[i + 1] - pref[i + 1 - t.SMA_LEN]) / t.SMA_LEN

        var ath = 0.0
        var athDate = ""
        var active = false
        var epLow: Double? = null
        var epLowIdx: Int? = null
        var epPeakVix: Double? = null
        var epMaxDd = 0.0
        var epPeakRatio = 0.0
        var peakAtFade: Double? = null
        var hadPanic = false; var hadFade = false; var relapsed = false
        var brokeSma = false; var restored = false
        var upRun = 0

        var state = State.CALM
        var pending: State? = null
        var pendingCount = 0

        for (i in days.indices) {
            val d = days[i]
            if (d.spx > ath) { ath = d.spx; athDate = d.date }
            val s = sma(i)
            val sPrev = if (i >= t.SMA_SLOPE_DAYS) sma(i - t.SMA_SLOPE_DAYS) else null
            val rising: Boolean? = if (s != null && sPrev != null) s > sPrev else null
            upRun = if (s != null && d.spx > s && rising == true) upRun + 1 else 0
            if (i < t.LOOKBACK - 1) continue

            val dd = d.spx / ath - 1
            val ratio = d.vix / d.vix3m
            val from = i - t.LOOKBACK + 1
            var cnt = 0
            for (k in from..i) if (vixes[k] <= d.vix) cnt++
            val vpct = cnt.toDouble() / t.LOOKBACK
            val dist = s?.let { d.spx / it - 1 }
            val belowFalling = s != null && d.spx < s && rising == false

            // ---- episode bookkeeping
            if (!active && dd <= t.DIP_START) {
                active = true
                epLow = d.spx; epLowIdx = i
                epMaxDd = dd
                var hiIdx = from
                for (k in from..i) if (closes[k] > closes[hiIdx]) hiIdx = k
                var pk = vixes[hiIdx]
                for (k in hiIdx..i) pk = max(pk, vixes[k])
                epPeakVix = pk
                epPeakRatio = ratio
                peakAtFade = null
                hadPanic = false; hadFade = false; relapsed = false; brokeSma = false; restored = false
            } else if (active && dd > t.DIP_END) {
                active = false
                epLow = null; epLowIdx = null; epPeakVix = null
                epMaxDd = 0.0; epPeakRatio = 0.0; peakAtFade = null
                hadPanic = false; hadFade = false; relapsed = false; brokeSma = false; restored = false
            }

            var newLow = false
            if (active) {
                if (d.spx < epLow!!) { epLow = d.spx; epLowIdx = i; newLow = true }
                epMaxDd = min(epMaxDd, dd)
                epPeakVix = max(epPeakVix!!, d.vix)
                epPeakRatio = max(epPeakRatio, ratio)
                if (s != null && d.spx < s) brokeSma = true
            }
            var daysSinceLow: Int? = if (active) i - epLowIdx!! else null

            val reasons = mutableListOf("${pct1(dd)} from the all-time high (${grp(ath)})")
            if (s != null) reasons += "${if (d.spx >= s) "Above" else "Below"} the 200-day average " +
                "(${sgn1(dist!!)}), which is ${if (rising == true) "rising" else "falling"}"
            reasons += "VIX ${f1(d.vix)} (${Math.round(vpct * 100)}% percentile of the past year), " +
                "VIX/VIX3M ${f2(ratio)}"

            val panicNow = ratio >= t.PANIC_RATIO || (d.vix >= t.PANIC_VIX && vpct >= t.PANIC_VIX_PCT)
            val raw: State
            var trace = Trace(Branch.CALM, hadFade, hadPanic, hadFade, relapsed, newLow, belowFalling,
                panicNow, false, false, false, false, false, false, null, null)
            if (!active) {
                raw = State.CALM
            } else {
                val hadFadeBefore = hadFade
                val curveOk = ratio < t.FADE_RATIO || ratio <= epPeakRatio - t.FADE_RATIO_DROP
                val faded = curveOk && d.vix <= t.FADE_VIX_FRACTION * epPeakVix!! &&
                    daysSinceLow!! >= t.LOW_HELD_DAYS && d.spx >= epLow!! * (1 + t.BOUNCE_OFF_LOW)
                val newVixHigh = hadFade && peakAtFade != null && d.vix > peakAtFade!!
                val relapse = hadFade && (newVixHigh || (belowFalling && (newLow ||
                    ratio >= t.RELAPSE_RATIO || d.vix >= t.RELAPSE_VIX_FRACTION * epPeakVix!!)))
                val restoredNow = epMaxDd <= t.UPTREND_MIN_DD && brokeSma &&
                    upRun >= t.UPTREND_DAYS && !panicNow
                if (restoredNow && !restored) {
                    // the dip's panic cycle is over: later turbulence starts a fresh cycle
                    restored = true
                    hadPanic = false; hadFade = false; relapsed = false; brokeSma = false
                    epPeakVix = d.vix; epPeakRatio = ratio; peakAtFade = null
                    epLow = d.spx; epLowIdx = i
                    daysSinceLow = 0
                } else if (restoredNow) {
                    // still restored
                } else if (restored && (s == null || d.spx < s || panicNow)) {
                    restored = false
                }

                val panicBranch = panicNow && (!hadFade || ratio >= t.RELAPSE_RATIO ||
                    (d.vix >= t.PANIC_VIX && vpct >= t.PANIC_VIX_PCT))
                val branch = when {
                    relapse -> Branch.NEW_WAVE
                    restored -> Branch.RESTORED
                    hadPanic && faded -> Branch.FADING
                    panicBranch -> Branch.PANIC
                    hadFade -> Branch.FADE_HOLDS
                    hadPanic -> Branch.PANIC_HOLDS
                    dd <= t.BEAR_DD && belowFalling -> Branch.BEAR
                    else -> Branch.PULLBACK
                }
                trace = Trace(branch, hadFadeBefore, hadPanic, hadFade, relapsed, newLow, belowFalling,
                    panicNow, panicBranch, faded, curveOk, newVixHigh, relapse, restored, peakAtFade, epPeakRatio)

                when {
                    relapse -> {
                        raw = State.NEW_WAVE
                        hadFade = false; relapsed = true
                        reasons += "After fading: " + if (newVixHigh)
                            "VIX ${f1(d.vix)} above its level at the fade (${f1(peakAtFade!!)})"
                        else "new low or renewed fear below a falling 200-day average"
                    }
                    restored -> {
                        raw = State.UPTREND_RESTORED
                        reasons += "$upRun closes above a rising 200-day average after a " +
                            "${pct0(epMaxDd)} fall; ${sgn1(d.spx / epLow!! - 1)} off the low"
                    }
                    hadPanic && faded -> {
                        raw = State.FIRST_WAVE_FADING
                        if (!hadFade) peakAtFade = epPeakVix
                        hadFade = true; relapsed = false
                        reasons += "VIX at ${Math.round(d.vix / epPeakVix!! * 100)}% of its dip peak " +
                            "${f1(epPeakVix!!)}; no new low for $daysSinceLow days; " +
                            "${sgn1(d.spx / epLow!! - 1)} off the low"
                    }
                    panicNow && (!hadFade || ratio >= t.RELAPSE_RATIO ||
                        (d.vix >= t.PANIC_VIX && vpct >= t.PANIC_VIX_PCT)) -> {
                        raw = if (relapsed) State.NEW_WAVE else State.PANIC
                        hadPanic = true; hadFade = false
                        reasons += if (ratio >= 1) "Volatility curve inverted (VIX above VIX3M)"
                                   else "VIX in the top 5% of its past year"
                    }
                    hadFade -> {
                        raw = State.FIRST_WAVE_FADING
                        reasons += "Fear faded earlier and has not returned"
                    }
                    hadPanic -> {
                        raw = if (relapsed) State.NEW_WAVE else State.PANIC
                        reasons += "Panic seen in this dip; fading not confirmed yet"
                    }
                    dd <= t.BEAR_DD && belowFalling -> {
                        raw = State.BEAR_GRIND
                        reasons += "Below a falling 200-day average without panic"
                    }
                    else -> raw = State.PULLBACK
                }
            }

            // ---- hysteresis
            if (raw == state) {
                pending = null; pendingCount = 0
            } else if (raw in IMMEDIATE) {
                state = raw; pending = null; pendingCount = 0
            } else {
                if (raw == pending) pendingCount++ else { pending = raw; pendingCount = 1 }
                if (pendingCount >= t.ENTER_DAYS) { state = raw; pending = null; pendingCount = 0 }
            }

            out += Reading(d.date, state, raw, d.spx, d.vix, d.vix3m, dd, ath, ratio, vpct,
                s, rising, dist, active, epLow, if (active) epMaxDd else null, epPeakVix,
                daysSinceLow, upRun, contextFor(d), reasons, d, athDate, trace, sma200Prev = sPrev,
                prevClose = if (i > 0) days[i - 1].spx else null, prevDate = if (i > 0) days[i - 1].date else null)
        }
        return out
    }

    private fun f1(x: Double) = String.format(java.util.Locale.US, "%.1f", x)
    private fun f2(x: Double) = String.format(java.util.Locale.US, "%.2f", x)
    private fun sgn1(x: Double) = String.format(java.util.Locale.US, "%+.1f%%", x * 100)
    private fun sgn2(x: Double) = String.format(java.util.Locale.US, "%+.2f", x)
    private fun pct1(x: Double) = String.format(java.util.Locale.US, "%.1f%%", x * 100)
    private fun pct0(x: Double) = String.format(java.util.Locale.US, "%.0f%%", x * 100)
    private fun grp(x: Double) = String.format(java.util.Locale.US, "%,.0f", x)
}
