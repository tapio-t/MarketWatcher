package fi.marketwatcher.work

import fi.marketwatcher.engine.Context
import fi.marketwatcher.engine.Explain
import fi.marketwatcher.engine.Fall
import fi.marketwatcher.engine.PriceView
import fi.marketwatcher.engine.Reading
import fi.marketwatcher.engine.State
import fi.marketwatcher.engine.price
import java.util.Locale

/**
 * Decides which notifications to send. Pure Kotlin (no Android), so the real background check
 * and the Settings test buttons run exactly the same rules, and unit tests can cover them.
 */
object NotificationRules {

    /** What the app remembered from the previous check. */
    data class Previous(
        val state: State?, val flags: Set<String>, val belowSma: Boolean?,
        /** The last trading day whose sharp fall was already seen (notified at most once). */
        val sharpFallDate: String? = null,
        /**
         * The date of the last volatility-curve inversion already seen ("" = none seen yet; null =
         * not known yet, e.g. the first check after installing: nothing is notified then).
         */
        val inversionDate: String? = null,
    )

    /**
     * Today's values that the rules look at. State, flags and [flagNotes] come from the newest full
     * reading; [date], [spx], [sma] and [prevClose] from the newest S&P 500 close, which may be a
     * price-only day (VIX not published yet).
     */
    data class Now(
        val date: String,
        val state: State,
        val flags: Set<String>,
        /** Each economy flag that is on, with its note from the engine (see [flagNotes]). */
        val flagNotes: Map<String, String>,
        val spx: Double,
        val sma: Double?,
        val prevClose: Double? = null,
        val prevDate: String? = null,
        /** [spx] is estimated from VOO (FRED has not published the close yet). */
        val estimated: Boolean = false,
        /** An estimated day that is a VOO ex-dividend day. */
        val exDividend: Boolean = false,
        /** The latest volatility-curve inversion in the readings (see [lastInversion]), or null. */
        val inversion: Inversion? = null,
        /** [state] comes from the sharp-fall rule on a VOO estimate (0.24). */
        val stateEstimated: Boolean = false,
        /** Set when [state] is Panic from the sharp-fall rule: what happened, for the notification text. */
        val sharpFallNote: String? = null,
    ) {
        val belowSma: Boolean? get() = sma?.let { spx < it }
        val change: Double? get() = prevClose?.let { spx / it - 1 }
    }

    data class Notice(val id: Int, val title: String, val text: String)

    /** A day when VIX/VIX3M reached 1.00 (as shown) after being at 0.90 or below. */
    data class Inversion(val date: String, val ratio: Double, val vix: Double?, val vix3m: Double?)

    enum class Kind { STATE, BELOW_SMA, SHARP_FALL, CURVE }

    const val ID_STATE = 1
    const val ID_ECONOMY = 2
    const val ID_BELOW_SMA = 3
    const val ID_SHARP_FALL = 4
    const val ID_CURVE = 5
    /** Test notices use their own ids so they never replace or hide a real one. */
    const val TEST_ID_OFFSET = 100

    /** Notification channels ("notification types" in Android settings), both high importance. */
    const val CHANNEL_STATE = "state_economy"
    const val CHANNEL_BELOW_SMA = "below_sma"
    const val CHANNEL_SHARP_FALL = "sharp_fall"
    const val CHANNEL_CURVE = "vix_curve"
    /** The old default-importance channel (versions up to 0.8); removed on start. */
    const val LEGACY_CHANNEL = "market_state"

    /** The channel a notice goes to; a test goes to the same channel as the real notice. */
    fun channelOf(noticeId: Int): String = when (noticeId % TEST_ID_OFFSET) {
        ID_BELOW_SMA -> CHANNEL_BELOW_SMA
        ID_SHARP_FALL -> CHANNEL_SHARP_FALL
        ID_CURVE -> CHANNEL_CURVE
        else -> CHANNEL_STATE
    }

    fun channelOf(kind: Kind): String = when (kind) {
        Kind.BELOW_SMA -> CHANNEL_BELOW_SMA
        Kind.SHARP_FALL -> CHANNEL_SHARP_FALL
        Kind.CURVE -> CHANNEL_CURVE
        Kind.STATE -> CHANNEL_STATE
    }

    private val QUIET = setOf(State.CALM, State.PULLBACK)

    /** The economy flags that are on, in the engine's note order. */
    fun flagsOn(c: Context): List<String> = listOf(
        "Credit stress" to c.creditStress, "Recession" to c.recession,
        "Rate shock" to c.rateShock, "High valuation" to c.valuationHigh,
    ).filter { it.second == true }.map { it.first }

    /** Each flag that is on, with its note: the engine adds one note per flag, in the same order. */
    fun flagNotes(c: Context): Map<String, String> = flagsOn(c).zip(c.notes).toMap()

    /** [price]: the newest S&P 500 close (a price-only day when VIX is not published yet). */
    fun now(r: Reading, flags: Set<String>, price: PriceView = r.price(), inversion: Inversion? = null) =
        Now(price.date, r.state, flags, flagNotes(r.context), price.close, price.sma200, price.prevClose, price.prevDate,
            price.estimated, price.exDividend, inversion, r.stateEstimated, sharpFallNote(r))

    /** The notification text of a Panic shown by the sharp-fall rule, or null. */
    fun sharpFallNote(r: Reading): String? {
        if (!r.sharpFallPanic || r.fallChange == null || r.fallDate == null) return null
        return "Very sharp fall: S&P 500 ${pct2(r.fallChange)} on ${Explain.dmy(r.fallDate)}. Panic is shown until a " +
            "day closes at −0.25% or better. It doesn't count as panic in a dip for the later fading check."
    }

    /** VIX/VIX3M at or above this, as shown (two decimals), is an inverted curve. */
    const val CURVE_ON = 1.00
    /** After a notice, the next one needs VIX/VIX3M back at or below this first (about 3 notices a year in 2008–2026). */
    const val CURVE_REARM = 0.90

    /** The VIX/VIX3M of [r] as an [Inversion] candidate. */
    fun curvePoint(r: Reading) = Inversion(r.date, r.vixRatio, r.vix, r.vix3m)

    /** The latest inversion in [readings] (oldest first), or null if none. */
    fun lastInversion(readings: List<Reading>): Inversion? = lastInversionOf(readings.map(::curvePoint))

    /**
     * Walks the days (oldest first): an inversion is a day with VIX/VIX3M ≥ [CURVE_ON] while armed;
     * it disarms until a day at or below [CURVE_REARM]. Compared as shown, in hundredths.
     */
    fun lastInversionOf(days: List<Inversion>): Inversion? = inversionsOf(days).lastOrNull()

    /** Every inversion in [days] (oldest first), by the rule of [lastInversionOf]. */
    fun inversionsOf(days: List<Inversion>): List<Inversion> {
        var armed = true
        val out = mutableListOf<Inversion>()
        for (d in days) {
            if (d.ratio.isNaN()) continue
            val shown = Math.round(d.ratio * 100)
            if (armed && shown >= Math.round(CURVE_ON * 100)) { out += d; armed = false }
            else if (shown <= Math.round(CURVE_REARM * 100)) armed = true
        }
        return out
    }

    /** " (estimate)" after a price notification's title when the close is estimated from VOO. */
    private fun Now.mark() = if (estimated) " (estimate)" else ""

    /** The note at the start of an estimated price notification. */
    private fun Now.estimateNote() = if (!estimated) "" else
        "Estimated from VOO; FRED has not published the close yet." + (if (exDividend) " Ex-dividend day." else "") + " "

    /**
     * Real rules:
     *  - state change, except No panic ↔ Pullback (needs a known previous state);
     *  - an economy flag that was off and is now on;
     *  - first close below the 200-day average; again only after a close back above it;
     *  - a sharp fall (−2.00% or worse vs the previous close), once per trading day;
     *  - a volatility-curve inversion newer than the last one seen (needs a known previous check).
     */
    fun evaluate(prev: Previous, now: Now, notifyState: Boolean, notifyBelowSma: Boolean,
                 notifySharpFall: Boolean = false, notifyCurve: Boolean = false): List<Notice> = buildList {
        if (notifyState) {
            val p = prev.state
            if (p != null && p != now.state && !(p in QUIET && now.state in QUIET)) {
                add(Notice(ID_STATE, now.state.label + if (now.stateEstimated) " (estimate)" else "",
                    (if (now.stateEstimated) "Estimated from VOO; FRED has not published the close yet. " else "") +
                        (now.sharpFallNote ?: now.state.message)))
            }
            val newFlags = now.flags - prev.flags
            if (newFlags.isNotEmpty()) {
                add(Notice(ID_ECONOMY, "Economy: " + newFlags.sorted().joinToString(", "),
                    // Only the notes of the flags that just turned on; the others are in the app.
                    newFlags.sorted().mapNotNull { now.flagNotes[it] }.joinToString(" ")
                        .ifBlank { "An economy flag turned on." }))
            }
        }
        if (notifyBelowSma) {
            val sma = now.sma
            if (now.belowSma == true && prev.belowSma != true && sma != null) {
                add(Notice(ID_BELOW_SMA, "S&P 500 is below its 200-day average" + now.mark(),
                    now.estimateNote() + String.format(Locale.US, "Close %,.0f on %s is %.1f%% below the 200-day average %,.0f. %s",
                        now.spx, Explain.dmy(now.date), (1 - now.spx / sma) * 100, sma, now.state.label)))
            }
        }
        if (notifySharpFall) {
            val ch = now.change
            val fall = ch?.let { Fall.of(it) }
            if (fall != null && now.date != prev.sharpFallDate) {
                add(Notice(ID_SHARP_FALL, "${fall.title}${now.mark()}: S&P 500 ${pct2(ch)} on ${Explain.dmy(now.date)}",
                    now.estimateNote() + String.format(Locale.US, "Close %,.0f, previous close %,.0f on %s. ", now.spx, now.prevClose, Explain.dmy(now.prevDate)) +
                        "Tiers: −2% sharp, −3% very sharp, −5% extreme. In 2008–2026 the S&P 500 fell 2% or more on about 4% of days."))
            }
        }
        if (notifyCurve) {
            val inv = now.inversion
            val seen = prev.inversionDate
            if (inv != null && seen != null && inv.date > seen) {
                val values = if (inv.vix != null && inv.vix3m != null)
                    String.format(Locale.US, "VIX %.2f is above VIX3M %.2f: ", inv.vix, inv.vix3m) else ""
                add(Notice(ID_CURVE, String.format(Locale.US, "Volatility curve inverted: VIX/VIX3M %.2f on %s", inv.ratio, Explain.dmy(inv.date)),
                    values + "near-term expected volatility is higher than the 3-month one. The suggested state " +
                        "changes only inside a dip (7% or more below the high): ${now.state.label}. " +
                        String.format(Locale.US, "The next notice comes after VIX/VIX3M has been at %.2f or below.", CURVE_REARM)))
            }
        }
    }

    /** "−2.41%" with a real minus sign. */
    fun pct2(x: Double): String = (if (x < 0) "−" else "+") + String.format(Locale.US, "%.2f%%", Math.abs(x) * 100)

    /**
     * A test: today's real values, with only the "previous check" (and, if needed, today's
     * close or flags) simulated so the chosen rule must fire. Runs [evaluate] itself, so the
     * notification is built by the same code as a real one. Nothing here is stored.
     */
    fun test(kind: Kind, now: Now): List<Notice> {
        val notices = when (kind) {
            Kind.STATE -> {
                // A previous state that always counts as a change from today's state.
                val prevState = if (now.state == State.PANIC) State.CALM else State.PANIC
                val simulatedFlags = now.flags.ifEmpty { setOf("Test flag") }
                val n = if (now.flags.isEmpty()) now.copy(flags = simulatedFlags,
                    flagNotes = mapOf("Test flag" to "Simulated economy flag: no real flag is on today.")) else now
                evaluate(Previous(prevState, emptySet(), null), n, notifyState = true, notifyBelowSma = false)
            }
            Kind.BELOW_SMA -> {
                // Previous close above the average; if today's close is above it too, simulate 2% below.
                val sma = now.sma ?: 0.0
                val n = if (now.belowSma == true || now.sma == null) now
                        else now.copy(spx = sma * 0.98)
                val simulated = n !== now
                evaluate(Previous(null, emptySet(), false), n, notifyState = false, notifyBelowSma = true)
                    .map { if (simulated) it.copy(text = "Simulated close (today's real close is above the average). " + it.text) else it }
            }
            Kind.SHARP_FALL -> {
                // If today's change is not a sharp fall, simulate a previous close 2.5% higher.
                val real = now.change?.let { Fall.of(it) } != null
                val n = if (real) now else now.copy(prevClose = now.spx / 0.975, prevDate = now.prevDate ?: now.date)
                evaluate(Previous(null, emptySet(), null, sharpFallDate = null), n, notifyState = false,
                    notifyBelowSma = false, notifySharpFall = true)
                    .map { if (!real) it.copy(text = "Simulated previous close (today's real change is not a sharp fall). " + it.text) else it }
            }
            Kind.CURVE -> {
                // If the latest reading is not inverted, simulate VIX/VIX3M 1.05 on today's date.
                val real = now.inversion != null
                val n = if (real) now else now.copy(inversion = Inversion(now.date, 1.05, null, null))
                evaluate(Previous(null, emptySet(), null, inversionDate = ""), n, notifyState = false,
                    notifyBelowSma = false, notifyCurve = true)
                    .map { if (!real) it.copy(text = "Simulated inversion (the latest VIX/VIX3M is below 1.00). " + it.text) else it }
            }
        }
        return notices.map { it.copy(id = it.id + TEST_ID_OFFSET, title = "Test: " + it.title) }
    }
}
