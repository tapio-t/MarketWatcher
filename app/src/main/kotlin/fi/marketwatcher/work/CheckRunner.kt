package fi.marketwatcher.work

import android.content.Context
import fi.marketwatcher.data.Settings
import fi.marketwatcher.data.SignalRepository
import fi.marketwatcher.engine.Explain
import fi.marketwatcher.engine.price

/** One background check: download, apply [NotificationRules], show or hold the notices, describe it. */
object CheckRunner {
    /** [text] goes to "Last background check"; [priceDate] is the newest S&P 500 close from FRED (not an estimate). */
    data class Outcome(val text: String, val priceDate: String?)

    /** [priceOnly]: the early check, which downloads only the S&P 500. Blocking: call off the main thread. */
    fun run(ctx: Context, priceOnly: Boolean): Outcome {
        val settings = Settings(ctx)
        val repo = SignalRepository(ctx)
        val r = if (priceOnly) repo.refreshPriceOnly() else repo.refresh()
        val today = r.today ?: return Outcome("No data from FRED yet.", null)
        val price = r.price ?: today.price()
        val notices = NotificationRules.evaluate(
            NotificationRules.Previous(r.previousState, r.previousFlags, r.previousBelowSma, r.previousSharpFallDate),
            NotificationRules.now(today, r.flags, price),
            notifyState = settings.notifications, notifyBelowSma = settings.notifyBelowSma,
            notifySharpFall = settings.notifySharpFall)
        val est = if (price.estimated) " (estimate from VOO)" else ""
        val data = if (price.date != today.date)
            "S&P 500 to ${Explain.dmy(price.date)}$est, VIX to ${Explain.dmy(today.date)}, ${today.label}"
        else "data from ${Explain.dmy(today.date)}, ${today.label}"
        val o = NotificationGate.deliver(ctx, notices)
        val text = when {
            notices.isEmpty() -> "No change ($data)."
            o.held.isNotEmpty() -> "Held until 08:00: ${o.held.joinToString("; ") { it.title }} ($data)."
            o.removed.isNotEmpty() -> "Notified: ${o.shown.joinToString("; ") { it.title }} ($data). " +
                Notifier.removedText(ctx)
            o.shown.isNotEmpty() -> "Notified: ${o.shown.joinToString("; ") { it.title }} ($data)."
            else -> "Had ${notices.size} notification(s) to send, but: ${o.blocked?.message ?: "posting failed."}"
        }
        // The early check keeps retrying until FRED has the close, even when VOO already gave an estimate.
        return Outcome((if (priceOnly) "Early check (S&P 500 only): " else "") + text, r.fredPriceDate)
    }
}
