package fi.marketwatcher.work

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
    )

    /**
     * Today's values that the rules look at. State, flags and notes come from the newest full
     * reading; [date], [spx], [sma] and [prevClose] from the newest S&P 500 close, which may be a
     * price-only day (VIX not published yet).
     */
    data class Now(
        val date: String,
        val state: State,
        val flags: Set<String>,
        val notes: List<String>,
        val spx: Double,
        val sma: Double?,
        val prevClose: Double? = null,
        val prevDate: String? = null,
    ) {
        val belowSma: Boolean? get() = sma?.let { spx < it }
        val change: Double? get() = prevClose?.let { spx / it - 1 }
    }

    data class Notice(val id: Int, val title: String, val text: String)

    enum class Kind { STATE, BELOW_SMA, SHARP_FALL }

    const val ID_STATE = 1
    const val ID_ECONOMY = 2
    const val ID_BELOW_SMA = 3
    const val ID_SHARP_FALL = 4
    /** Test notices use their own ids so they never replace or hide a real one. */
    const val TEST_ID_OFFSET = 100

    /** Notification channels ("notification types" in Android settings), both high importance. */
    const val CHANNEL_STATE = "state_economy"
    const val CHANNEL_BELOW_SMA = "below_sma"
    const val CHANNEL_SHARP_FALL = "sharp_fall"
    /** The old default-importance channel (versions up to 0.8); removed on start. */
    const val LEGACY_CHANNEL = "market_state"

    /** The channel a notice goes to; a test goes to the same channel as the real notice. */
    fun channelOf(noticeId: Int): String = when (noticeId % TEST_ID_OFFSET) {
        ID_BELOW_SMA -> CHANNEL_BELOW_SMA
        ID_SHARP_FALL -> CHANNEL_SHARP_FALL
        else -> CHANNEL_STATE
    }

    fun channelOf(kind: Kind): String = when (kind) {
        Kind.BELOW_SMA -> CHANNEL_BELOW_SMA
        Kind.SHARP_FALL -> CHANNEL_SHARP_FALL
        Kind.STATE -> CHANNEL_STATE
    }

    private val QUIET = setOf(State.CALM, State.PULLBACK)

    /** [price]: the newest S&P 500 close (a price-only day when VIX is not published yet). */
    fun now(r: Reading, flags: Set<String>, price: PriceView = r.price()) =
        Now(price.date, r.state, flags, r.context.notes, price.close, price.sma200, price.prevClose, price.prevDate)

    /**
     * Real rules:
     *  - state change, except No panic ↔ Pullback (needs a known previous state);
     *  - an economy flag that was off and is now on;
     *  - first close below the 200-day average; again only after a close back above it;
     *  - a sharp fall (−2.00% or worse vs the previous close), once per trading day.
     */
    fun evaluate(prev: Previous, now: Now, notifyState: Boolean, notifyBelowSma: Boolean,
                 notifySharpFall: Boolean = false): List<Notice> = buildList {
        if (notifyState) {
            val p = prev.state
            if (p != null && p != now.state && !(p in QUIET && now.state in QUIET)) {
                add(Notice(ID_STATE, now.state.label, now.state.message))
            }
            val newFlags = now.flags - prev.flags
            if (newFlags.isNotEmpty()) {
                add(Notice(ID_ECONOMY, "Economy: " + newFlags.sorted().joinToString(", "),
                    now.notes.joinToString(" ").ifBlank { "An economy flag turned on." }))
            }
        }
        if (notifyBelowSma) {
            val sma = now.sma
            if (now.belowSma == true && prev.belowSma != true && sma != null) {
                add(Notice(ID_BELOW_SMA, "S&P 500 is below its 200-day average",
                    String.format(Locale.US, "Close %,.0f on %s is %.1f%% below the 200-day average %,.0f. %s",
                        now.spx, Explain.dmy(now.date), (1 - now.spx / sma) * 100, sma, now.state.label)))
            }
        }
        if (notifySharpFall) {
            val ch = now.change
            val fall = ch?.let { Fall.of(it) }
            if (fall != null && now.date != prev.sharpFallDate) {
                add(Notice(ID_SHARP_FALL, "${fall.title}: S&P 500 ${pct2(ch)} on ${Explain.dmy(now.date)}",
                    String.format(Locale.US, "Close %,.0f, previous close %,.0f on %s. ", now.spx, now.prevClose, Explain.dmy(now.prevDate)) +
                        "Tiers: −2% sharp, −3% very sharp, −5% extreme. In 2008–2026 the S&P 500 fell 2% or more on about 4% of days."))
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
                    notes = listOf("Simulated economy flag: no real flag is on today.")) else now
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
        }
        return notices.map { it.copy(id = it.id + TEST_ID_OFFSET, title = "Test: " + it.title) }
    }
}
