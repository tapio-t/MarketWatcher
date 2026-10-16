package fi.marketwatcher.engine

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/**
 * Turns a [Reading]'s [Trace] into the "why" list shown when the Suggested box is tapped.
 * The steps follow the engine's real checking order; the first rule that applies sets the state.
 */
object Explain {

    enum class Outcome { DECIDED, NOT_MET, SKIPPED }

    data class Step(
        val branch: Branch,
        /** The state this rule gives when it applies. */
        val state: State,
        val rule: String,
        val detail: String,
        val outcome: Outcome,
    )

    private val DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    fun dmy(iso: String?): String = iso?.let { LocalDate.parse(it).format(DMY) } ?: "—"

    fun steps(r: Reading): List<Step> {
        val t = r.trace
        val th = Thresholds
        val panicState = if (t.relapsed) State.NEW_WAVE else State.PANIC
        val peak = r.episodeVixPeak
        val low = r.episodeLow

        val rules = listOf(
            Triple(Branch.CALM, State.CALM,
                "No panic: not in a dip (within 7% of the all-time high). A dip starts 7% below the high and ends within 3% of it") to
                "${pct(r.drawdown)} from the all-time high ${grp(r.ath)} (${dmy(r.athDate)})",

            Triple(Branch.NEW_WAVE, State.NEW_WAVE,
                "New wave: after a fade, VIX goes above its level at the fade, or the index is below a " +
                    "falling 200-day average with a new low, VIX/VIX3M ≥ 1.05 or VIX ≥ 90% of its dip peak") to
                if (!t.hadFadeBefore) "No fading earlier in this dip"
                else "VIX ${f1(r.vix)} vs ${t.peakAtFade?.let { f1(it) } ?: "—"} at the fade; " +
                    "${if (t.belowFalling) "below a falling" else "not below a falling"} 200-day average; " +
                    "new low: ${yes(t.newLow)}; VIX/VIX3M ${f2(r.vixRatio)}",

            Triple(Branch.RESTORED, State.UPTREND_RESTORED,
                "Uptrend restored: fell 10% or more, broke the 200-day average, then 10 closes above a " +
                    "rising average, no panic") to
                "${r.uptrendRun} closes above a rising average (needs 10); worst fall in this dip " +
                    "${r.episodeMaxDd?.let { pct(it) } ?: "—"} (needs −10%); panic today: ${yes(t.panicNow)}",

            Triple(Branch.FADING, State.FIRST_WAVE_FADING,
                "First wave fading: panic seen, VIX ≤ 80% of its dip peak, curve not inverted " +
                    "(or 0.15 below its peak), no new low for 3 days, 2% or more off the low") to
                if (!t.hadPanic) "No panic seen yet in this dip"
                else "VIX at ${peak?.let { "${Math.round(r.vix / it * 100)}% of its peak ${f1(it)}" } ?: "—"}; " +
                    "VIX/VIX3M ${f2(r.vixRatio)} (peak ${t.epPeakRatio?.let { f2(it) } ?: "—"}); " +
                    "${r.daysSinceLow ?: "—"} days since the low; " +
                    "${low?.let { sgn(r.spx / it - 1) } ?: "—"} off the low",

            Triple(Branch.PANIC, panicState,
                "Panic: VIX ≥ VIX3M (≥ 1.05 after a fade), or VIX ≥ 30 in the top 5% of its past year" +
                    if (t.relapsed) ". Shown as New wave, because fear already returned once" else "") to
                "VIX/VIX3M ${f2(r.vixRatio)}; VIX ${f1(r.vix)} at the ${Math.round(r.vixPct * 100)}% percentile",

            Triple(Branch.FADE_HOLDS, State.FIRST_WAVE_FADING,
                "Fading continues: fear faded earlier in this dip and has not returned") to
                if (t.hadFade) "Fading recorded earlier; no new wave since" else "No fading earlier in this dip",

            Triple(Branch.PANIC_HOLDS, panicState,
                "Panic continues: panic seen in this dip, fading not confirmed yet") to
                if (t.hadPanic) "Panic seen earlier in this dip" else "No panic seen in this dip",

            Triple(Branch.BEAR, State.BEAR_GRIND,
                "Bear grind: 15% or more below the high, below a falling 200-day average, no panic") to
                "${pct(r.drawdown)} from the high; " +
                    (r.distSma200?.let { "${sgn(it)} vs a ${if (r.sma200Rising == true) "rising" else "falling"} average" }
                        ?: "200-day average not available"),

            Triple(Branch.PULLBACK, State.PULLBACK,
                "Pullback: 7% or more below the high, none of the above") to
                "${pct(r.drawdown)} from the high",
        )

        // Outside a dip only the first rule is checked.
        val decided = if (!r.episodeActive) Branch.CALM else t.branch
        var reached = false
        return rules.map { (rule, detail) ->
            val (branch, state, text) = rule
            val outcome = when {
                reached -> Outcome.SKIPPED
                branch == decided -> { reached = true; Outcome.DECIDED }
                else -> Outcome.NOT_MET
            }
            Step(branch, state, text, detail, outcome)
        }
    }

    fun notes(r: Reading): List<String> = buildList {
        if (r.state != r.rawState) add(
            "Today's checks point to ${r.rawState.title}. A new state is shown only after it holds " +
                "for 2 days (Panic and New wave are shown at once), so ${r.state.title} is still shown.")
        add("Economy values (Sahm rule, NFCI, Buffett ratio) are shown for context and do not change the state.")
    }

    private fun yes(b: Boolean) = if (b) "yes" else "no"
    private fun f1(x: Double) = String.format(Locale.US, "%.1f", x)
    private fun f2(x: Double) = String.format(Locale.US, "%.2f", x)
    private fun grp(x: Double) = String.format(Locale.US, "%,.0f", x)
    private fun pct(x: Double) = (if (x < 0) "−" else "") + String.format(Locale.US, "%.1f%%", abs(x) * 100)
    private fun sgn(x: Double) = (if (x < 0) "−" else "+") + String.format(Locale.US, "%.1f%%", abs(x) * 100)
}
