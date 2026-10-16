package fi.marketwatcher.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.marketwatcher.engine.PriceView
import fi.marketwatcher.engine.Reading
import fi.marketwatcher.engine.price
import fi.marketwatcher.engine.SignalEngine
import fi.marketwatcher.engine.Thresholds

private const val URL_VIX = "https://www.thetrading.tools/vix-term-structure"
private const val URL_SAHM = "https://fred.stlouisfed.org/series/SAHMREALTIME"
private const val URL_UNRATE = "https://fred.stlouisfed.org/series/UNRATE"
private const val URL_NFCI = "https://fred.stlouisfed.org/series/NFCI"
private const val URL_BUFFETT = "https://buffettindicator.org/"
private const val URL_VIXCLS = "https://fred.stlouisfed.org/series/VIXCLS"
private const val URL_T10Y2Y = "https://fred.stlouisfed.org/series/T10Y2Y"
private const val URL_DGS2 = "https://fred.stlouisfed.org/series/DGS2"
private const val URL_SHILLER = "https://shillerdata.com/"
private const val URL_SPX_CHART = TRADINGVIEW_SPX_CHART

private data class Info(
    val title: String, val value: String, val date: String?, val explanation: List<AnnotatedString>,
    val inApp: String, val flag: Boolean?, val source: String, val flagName: String = "Flag",
    /** Optional monospace lines under the explanation (e.g. NFCI's 1-, 4- and 8-week changes). */
    val detail: List<String> = emptyList(),
)

private fun monthName(iso: String?): String = iso?.let {
    java.time.LocalDate.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("MM/yyyy"))
} ?: "—"

/** What a tile's value means (quoted from its source) and how MarketWatcher uses it. */
@Composable
internal fun InfoDialog(kind: InfoKind, r: Reading, price: PriceView = r.price(), onClose: () -> Unit) {
    val d = r.day
    val p = price   // the S&P 500 tiles: on Today possibly a newer, price-only day
    val uri = LocalUriHandler.current
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val warn = palette().warn

    val info: Info = when (kind) {
        InfoKind.VIX_RATIO -> {
            val title = "VIX / VIX3M"
            val value = fmt("%.2f", r.vixRatio) + if (r.vixRatio >= 1.0) " · backwardation" else " · contango"
            val date = r.date
            val explanation = listOf(AnnotatedString("Above 1.0 the curve is in backwardation — near-term stress and " +
                "hedging demand; below it, contango, the calm-market default."))
            val inApp = "In MarketWatcher: VIX/VIX3M at ${fmt("%.2f", Thresholds.PANIC_RATIO)} or above is one of the Panic " +
                "rules (${fmt("%.2f", Thresholds.RELAPSE_RATIO)} or above after this wave has faded)."
            val flag = r.backwardation
            Info(title, value, date, explanation, inApp, flag, URL_VIX, flagName = "Backwardation")
        }
        InfoKind.DAY_CHANGE -> {
            val explanation = listOf(AnnotatedString("The S&P 500 close compared with the previous trading day's close. " +
                "In 2008–2026 the index fell 2.00% or more (as shown, rounded to 0.01%) on 208 of 4,709 trading days (4.4%), 3.00% or more on 82 (1.7%) " +
                "and 5% or more on 19 (0.4%), most of them in 2008, 2009, 2011, 2020 and 2022."))
            val inApp = "In MarketWatcher: amber at −2.00% or worse, compared in hundredths of a percent as shown. " +
                "Tiers: −2% Sharp fall, −3% Very sharp fall, −5% Extreme fall. Close ${fmt("%,.0f", p.close)} on " +
                "${dmy(p.date)}, previous close ${p.prevClose?.let { fmt("%,.0f", it) } ?: "—"} on ${dmy(p.prevDate)}. " +
                "FRED adds the close about 03:00 Helsinki time; the \"Sharp falls\" notification is shown at 08:00 at " +
                "the earliest. Display only; it does not change the suggested state."
            Info("Day change", p.change?.let { signedPct2(it) } ?: "—", p.date, explanation, inApp,
                p.fall != null, URL_SPX_CHART, flagName = p.fall?.title ?: "Sharp fall")
        }
        InfoKind.VS_AVERAGE -> {
            val explanation = listOf(AnnotatedString("How far the S&P 500 close is above (+) or below (−) its 200-day " +
                "simple moving average, the average of the last 200 closes. A close below a long average is widely " +
                "read as a weakening trend."))
            val inApp = "In MarketWatcher: amber at 0.0% or below, as shown on the tile. Close " +
                "${fmt("%,.0f", p.close)} and 200-day average ${p.sma200?.let { fmt("%,.0f", it) } ?: "—"} on ${dmy(p.date)}. " +
                "The \"below 200-day average\" notification uses the same comparison but fires only when the close " +
                "is strictly below the average. Display only; it does not change the suggested state."
            Info("vs average", p.distSma200?.let { signedPct(it) } ?: "—", p.date, explanation, inApp,
                p.atOrBelowAverageShown, URL_SPX_CHART, flagName = "At or below average")
        }
        InfoKind.SMA_TREND -> {
            val prev = p.sma200Prev
            val diff = if (p.sma200 != null && prev != null) p.sma200 - prev else null
            val explanation = listOf(AnnotatedString("Whether the 200-day average itself is going up or down: today's " +
                "200-day average compared with its value 20 trading days earlier."))
            val inApp = "In MarketWatcher: amber when falling. 200-day average ${p.sma200?.let { fmt("%,.0f", it) } ?: "—"} " +
                "today vs ${prev?.let { fmt("%,.0f", it) } ?: "—"} 20 trading days earlier" +
                (diff?.let { " (${fmt("%+,.0f", it)})" } ?: "") + ". The engine uses the same test in its Bear grind " +
                "and New wave rules (\"below a falling 200-day average\"). The tile itself is display only."
            Info("Average is", when (p.sma200Rising) { true -> "Rising"; false -> "Falling"; null -> "—" }, p.date,
                explanation, inApp, p.averageFalling, URL_SPX_CHART, flagName = "Falling")
        }
        InfoKind.VIX -> {
            val explanation = listOf(AnnotatedString("The Cboe Volatility Index measures the market's expectation of " +
                "near-term volatility conveyed by S&P 500 index option prices."))
            val inApp = "In MarketWatcher: VIX at ${fmt("%.0f", Thresholds.PANIC_VIX)} or above and in the top " +
                "${fmt("%.0f", (1 - Thresholds.PANIC_VIX_PCT) * 100)}% of its past year is one of the Panic rules. " +
                "Today VIX is at the ${Math.round(r.vixPct * 100)}% percentile of its past year."
            Info("VIX", fmt("%.1f", r.vix), r.date, explanation, inApp, r.vixPanicLevel, URL_VIXCLS,
                flagName = "Panic level")
        }
        InfoKind.YIELD_CURVE -> {
            val explanation = listOf(AnnotatedString("The 10-year Treasury yield minus the 2-year Treasury yield. " +
                "Positive values may imply future growth, negative values may imply economic downturns. A negative " +
                "spread (an inverted yield curve) has historically come before recessions, often by a year or more."))
            val inApp = "In MarketWatcher: the tile turns amber when the curve is inverted (below " +
                "${fmt("%.2f", Thresholds.YIELD_INVERTED)}). Shown for context; it does not change the suggested state."
            Info("Yield curve 10y–2y", d.t10y2y?.let { fmt("%+.2f", it) + " pts" } ?: "—", d.t10y2yDate,
                explanation, inApp + (inversionText(d)?.let { " Inversion: $it." } ?: ""), r.context.yieldInverted, URL_T10Y2Y, flagName = "Inverted")
        }
        InfoKind.DGS2 -> {
            val explanation = listOf(AnnotatedString("Market Yield on U.S. Treasury Securities at 2-Year Constant " +
                "Maturity, Quoted on an Investment Basis (Board of Governors of the Federal Reserve System, H.15 " +
                "Selected Interest Rates). It moves with expectations for the Fed's policy rate."))
            val change = dgs2Change(d)
            val compare = if (change != null) " Change: ${fmt("%+.2f", change)} points since " +
                "${fmt("%.2f", d.dgs2SixMonthsAgo!!)}% on ${dmy(d.dgs2SixMonthsAgoDate)}." else ""
            val inApp = "In MarketWatcher: the Rate shock flag turns on when the 2-year yield is " +
                "${fmt("%.2f", Thresholds.RATE_SHOCK_PP)} points or more above its level about 6 months earlier " +
                "(2022-type pressure).$compare Shown for context; it does not change the suggested state."
            Info("2-year yield", d.dgs2?.let { fmt("%.2f%%", it) } ?: "—", d.dgs2Date, explanation, inApp,
                r.context.rateShock, URL_DGS2, flagName = "Rate shock")
        }
        InfoKind.CAPE -> {
            val explanation = listOf(AnnotatedString("The cyclically adjusted price-to-earnings ratio (CAPE) divides " +
                "the S&P 500 price by the average of ten years of inflation-adjusted earnings. The total return " +
                "version reinvests dividends, so changes in how much companies pay out don't distort it. High values " +
                "have historically been followed by lower long-term returns; it is not a timing signal."))
            val how = if (d.capeEstimated) "Estimate: Robert Shiller's latest official value (${monthName(d.capeDate)}) " +
                "scaled by the S&P 500 change since then." else "Official monthly value from Robert Shiller's data (${monthName(d.capeDate)})."
            val rank = d.capePct?.let { " Higher than ${fmt("%.0f", it * 100)}% of all months since 1881." } ?: ""
            val inApp = "In MarketWatcher: $how$rank The tile turns amber in the top " +
                "${fmt("%.0f", (1 - Thresholds.CAPE_PCT) * 100)}% of its history since 1881. Context only."
            Info("CAPE (total return)", d.cape?.let { (if (d.capeEstimated) "≈" else "") + fmt("%.1f", it) } ?: "—",
                d.capeDate, explanation, inApp, r.context.capeHigh, URL_SHILLER, flagName = "Top 10%")
        }
        InfoKind.SAHM -> {
            val title = "Sahm rule"
            val value = d.sahm?.let { fmt("%.2f", it) } ?: "—"
            val date = d.sahmDate
            val explanation = listOf(
                buildAnnotatedString {
                    append("Sahm Recession Indicator signals the start of a recession when the three-month moving " +
                        "average of the ")
                    withLink(LinkAnnotation.Url(URL_UNRATE, link) { runCatching { uri.openUri(URL_UNRATE) } }) {
                        append("national unemployment rate (U3)")
                    }
                    append(" rises by 0.50 percentage points or more relative to the minimum of the three-month " +
                        "averages from the previous 12 months.")
                },
                AnnotatedString("This indicator is based on \"real-time\" data, that is, the unemployment rate (and the " +
                    "recent history of unemployment rates) that were available in a given month. The BLS revises the " +
                    "unemployment rate each year at the beginning of January, when the December unemployment rate for " +
                    "the prior year is published. Revisions to the seasonal factors can affect estimates in recent " +
                    "years. Otherwise the unemployment rate does not revise."),
            )
            val inApp = "In MarketWatcher: the Recession flag turns on at ${fmt("%.2f", Thresholds.SAHM_ON)} or above. " +
                "It is shown for context and does not change the suggested state."
            val flag = r.context.recession
            Info(title, value, date, explanation, inApp, flag, URL_SAHM)
        }
        InfoKind.NFCI -> {
            val title = "NFCI"
            val value = d.nfci?.let { fmt("%+.3f", it) } ?: "—"
            val date = d.nfciDate
            val explanation = listOf(AnnotatedString("The Chicago Fed's National Financial Conditions Index (NFCI) provides " +
                "a comprehensive weekly update on U.S. financial conditions in money markets, debt and equity markets " +
                "and the traditional and \"shadow\" banking systems. Positive values of the NFCI indicate financial " +
                "conditions that are tighter than average, while negative values indicate financial conditions that " +
                "are looser than average."))
            // One line per window: change, the minimum rise it needs, ✓ when met.
            val detail = SignalEngine.creditWindows(d).map { w ->
                val label = "${w.weeks} week${if (w.weeks > 1) "s" else ""}:".padEnd(9)
                val change = w.change?.let { fmt("%+.3f", it) } ?: "  —   "
                "$label $change  (needs ${fmt("%+.3f", w.minimum)})" + if (w.met) " ✓" else ""
            }
            val inApp = "In MarketWatcher: the Credit stress flag turns on when NFCI is above 0 (tighter than average), " +
                "or when it is at ${fmt("%.3f", Thresholds.CREDIT_FLOOR).replace("-", "−")} or higher and has risen " +
                "${fmt("%+.3f", Thresholds.CREDIT_RISE_1W)} in 1 week, ${fmt("%+.3f", Thresholds.CREDIT_RISE_4W)} in 4 weeks " +
                "or ${fmt("%+.3f", Thresholds.CREDIT_RISE_8W)} in 8 weeks. Rises from deeper below average, like Q4 2018 " +
                "and April 2025, do not count. It is shown for context and does not change the suggested state."
            val flag = r.context.creditStress
            Info(title, value, date, explanation, inApp, flag, URL_NFCI, detail = detail)
        }
        InfoKind.BUFFETT -> {
            val title = "Buffett ratio"
            val value = d.buffett?.let { fmt("%.0f%%", it * 100) } ?: "—"
            val date = d.buffettDate
            val explanation = listOf(AnnotatedString("When the indicator climbs well above 100%, the stock market is large " +
                "relative to the underlying economy — historically a sign of stretched valuations. When it falls below " +
                "the long-term trend, equities have tended to deliver stronger forward returns."))
            val rank = d.buffettPct?.let { " Today's value is higher than ${fmt("%.0f", it * 100)}% of its history." } ?: ""
            val inApp = "In MarketWatcher: market value of US nonfinancial corporate equities (FRED NCBEILQ027S) ÷ nominal " +
                "GDP (FRED GDP), quarterly, used from 75 days after the quarter ends. The High valuation flag turns on " +
                "in the top ${fmt("%.0f", (1 - Thresholds.VALUATION_PCT) * 100)}% of its history.$rank " +
                "Context only, not a timing signal."
            val flag = r.context.valuationHigh
            Info(title, value, date, explanation, inApp, flag, URL_BUFFETT)
        }
    }

    val (title, value, date, explanation, inApp, flag, source, flagName, detail) = info
    AppDialog(title, MaterialTheme.colorScheme.onSurface, null, onClose) {
        Text(value, fontSize = 22.sp, fontFamily = FontFamily.Monospace,
            color = if (flag == true) warn else MaterialTheme.colorScheme.onSurface)
        Text(dmy(date), fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (flag != null) {
            Text(if (flag) "$flagName: on" else "$flagName: off", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (flag) warn else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(6.dp))
        explanation.forEach { Text(it, fontSize = 14.sp, lineHeight = 20.sp) }
        if (detail.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            if (d.nfciDate != null && kind == InfoKind.NFCI) Text("Change to ${dmy(d.nfciDate)}", fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            detail.forEach { Text(it, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
        }
        Spacer(Modifier.height(6.dp))
        Text(inApp, fontSize = 13.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { runCatching { uri.openUri(source) } }) {
            Text("Source: " + source.removePrefix("https://").removePrefix("www.").trimEnd('/') + " ↗")
        }
    }
}

