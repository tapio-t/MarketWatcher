package fi.marketwatcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fi.marketwatcher.engine.Explain
import fi.marketwatcher.engine.PriceView
import fi.marketwatcher.engine.Reading
import fi.marketwatcher.engine.State
import fi.marketwatcher.engine.price
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// ---------------------------------------------------------------- colours and formatting

internal data class Palette(
    val calm: Color, val pullback: Color, val panic: Color, val fading: Color,
    val newWave: Color, val bear: Color, val uptrend: Color,
    val sma: Color, val vix: Color, val warn: Color,
)

private val LightPalette = Palette(
    Color(0xFF2E7D4F), Color(0xFF5F7482), Color(0xFFC0392B), Color(0xFF1F63B8),
    Color(0xFF8A3FA6), Color(0xFF7A5230), Color(0xFF00838F),
    Color(0xFFB7791F), Color(0xFF2F6F7E), Color(0xFFB45309),
)
private val DarkPalette = Palette(
    Color(0xFF4FB57A), Color(0xFF8FA3B0), Color(0xFFEF6A5B), Color(0xFF5B9BEA),
    Color(0xFFC07BDB), Color(0xFFC08A5C), Color(0xFF3CC3CF),
    Color(0xFFE0A84A), Color(0xFF6FB3C3), Color(0xFFF0A43A),
)

@Composable
internal fun palette(): Palette = if (isSystemInDarkTheme()) DarkPalette else LightPalette

internal fun Palette.of(s: State): Color = when (s) {
    State.CALM -> calm
    State.PULLBACK -> pullback
    State.PANIC -> panic
    State.FIRST_WAVE_FADING -> fading
    State.NEW_WAVE -> newWave
    State.BEAR_GRIND -> bear
    State.UPTREND_RESTORED -> uptrend
}

internal fun fmt(pattern: String, vararg a: Any): String = String.format(Locale.US, pattern, *a)

/** DD.MM.YYYY, e.g. 31.10.2026. */
internal fun dmy(iso: String?): String = Explain.dmy(iso)

/** Signed percent with a real minus sign: "−0.4%", "+8.0%". */
internal fun signedPct(x: Double): String =
    if (Math.round(x * 1000) == 0L) "0.0%" else (if (x < 0) "−" else "+") + fmt("%.1f%%", abs(x) * 100)

private fun signed2(x: Double): String = (if (x < 0) "−" else "+") + fmt("%.2f", abs(x))
private fun signed3(x: Double): String = (if (x < 0) "−" else "+") + fmt("%.3f", abs(x))

// ---------------------------------------------------------------- history data for charts

/**
 * Everything the charts need, computed once per data load. Marked @Immutable so Compose can
 * skip the charts while only the selected day changes.
 */
@Immutable
class ChartData(val readings: List<Reading>) {
    val n: Int = readings.size
    val spx = FloatArray(n) { readings[it].spx.toFloat() }
    val sma = FloatArray(n) { readings[it].sma200?.toFloat() ?: Float.NaN }
    val states: Array<State> = Array(n) { readings[it].state }
    val year = IntArray(n) { readings[it].date.substring(0, 4).toInt() }
    val lo: Double = readings.minOf { it.spx }
    val hi: Double = readings.maxOf { it.spx }
    /** For each day: index of the first later close at or above its all-time high, or -1 (see [recoveryIndices]). */
    val recovery: IntArray = recoveryIndices(readings.map { it.date }, spx, readings.map { it.ath }, readings.map { it.athDate })

    /** Index of the lowest close in [y], or null if the year is not in the data. */
    fun lowestCloseOf(y: Int): Int? {
        var best = -1
        for (i in 0 until n) if (year[i] == y && (best < 0 || readings[i].spx < readings[best].spx)) best = i
        return best.takeIf { it >= 0 }
    }
}

// ---------------------------------------------------------------- value tiles

/** Tiles that open an explanation when tapped (Today screen). */
internal enum class InfoKind { DAY_CHANGE, VS_AVERAGE, SMA_TREND, VIX, VIX_RATIO, SAHM, NFCI, BUFFETT, YIELD_CURVE, DGS2, CAPE }

internal data class Tile(
    val label: String, val value: String, val date: String,
    val flagged: Boolean = false, val info: InfoKind? = null,
    /** Optional extra small line (e.g. the 2-year yield's 6-month change). */
    val sub: String? = null,
)
internal data class TileGroup(val title: String, val tiles: List<Tile>)

/** "−2.41%": two decimals, real minus sign (the sharp-fall tiers are compared in 0.01%). */
internal fun signedPct2(x: Double): String =
    if (Math.round(x * 10_000) == 0L) "0.00%" else (if (x < 0) "−" else "+") + fmt("%.2f%%", abs(x) * 100)

/** The Day change tile: amber at −2.00% or worse, with the fall tier under the value. */
internal fun dayChangeTile(p: PriceView): Tile =
    Tile("Day change", p.change?.let { signedPct2(it) } ?: "—", dmy(p.date), flagged = p.fall != null,
        info = InfoKind.DAY_CHANGE, sub = p.fall?.title)

/** Today, right below the Suggested box: the newest close against the previous one. */
internal fun dayChangeGroup(p: PriceView): TileGroup = TileGroup("S&P 500 day change", listOf(
    Tile("Close", fmt("%,.0f", p.close), dmy(p.date)),
    Tile("Prev. close", p.prevClose?.let { fmt("%,.0f", it) } ?: "—", dmy(p.prevDate)),
    dayChangeTile(p),
))

/** The S&P 500 and Trend tiles of one reading (also shown under the Today scroller for the selected day). */
internal fun priceGroups(r: Reading): List<TileGroup> = priceGroups(r.price())

/**
 * The S&P 500 and Trend tiles of a close, which may be a price-only day (VIX not published yet).
 * [withDayChange]: History adds the Day change tile to the S&P 500 group.
 */
internal fun priceGroups(p: PriceView, withDayChange: Boolean = false): List<TileGroup> {
    val day = dmy(p.date)
    return listOf(
        TileGroup("S&P 500", listOfNotNull(
            Tile("Close", fmt("%,.0f", p.close), day),
            Tile("All-time high", fmt("%,.0f", p.ath), dmy(p.athDate)),
            Tile("From high", signedPct(p.drawdown), day),
            if (withDayChange) dayChangeTile(p) else null,
        )),
        TileGroup("Trend", listOf(
            Tile("200-day avg", p.sma200?.let { fmt("%,.0f", it) } ?: "—", day),
            Tile("vs average", p.distSma200?.let { signedPct(it) } ?: "—", day, p.atOrBelowAverageShown, InfoKind.VS_AVERAGE),
            Tile("Average is", when (p.sma200Rising) { true -> "Rising"; false -> "Falling"; null -> "—" }, day,
                p.averageFalling, InfoKind.SMA_TREND),
        )),
    )
}

/**
 * The values of one reading, each with the date of the FRED observation it comes from. [price] is
 * the newest S&P 500 close for the S&P 500 and Trend tiles (on Today it can be newer than [r]).
 */
internal fun groupsFor(r: Reading, price: PriceView = r.price(), withDayChange: Boolean = false): List<TileGroup> {
    val d = r.day
    val day = dmy(r.date)
    return priceGroups(price, withDayChange) + listOf(
        TileGroup("Volatility", listOf(
            Tile("VIX", fmt("%.1f", r.vix), day, r.vixPanicLevel, InfoKind.VIX),
            Tile("VIX3M", fmt("%.1f", r.vix3m), day),
            Tile("VIX / VIX3M", fmt("%.2f", r.vixRatio), day, r.backwardation, InfoKind.VIX_RATIO),
        )),
        TileGroup("Economy", listOf(
            Tile("Sahm rule", d.sahm?.let { signed2(it) } ?: "—", dmy(d.sahmDate), r.context.recession == true,
                InfoKind.SAHM),
            Tile("NFCI", d.nfci?.let { signed3(it) } ?: "—", dmy(d.nfciDate), r.context.creditStress == true,
                InfoKind.NFCI),
            Tile("Buffett ratio", d.buffett?.let { fmt("%.0f%%", it * 100) } ?: "—", dmy(d.buffettDate),
                r.context.valuationHigh == true, InfoKind.BUFFETT),
            // second row: below Sahm rule, NFCI and Buffett ratio
            Tile("Yield curve 10y–2y", d.t10y2y?.let { signed2(it) } ?: "—", dmy(d.t10y2yDate),
                r.context.yieldInverted == true, InfoKind.YIELD_CURVE, sub = inversionText(d)),
            Tile("2-year yield", d.dgs2?.let { fmt("%.2f%%", it) } ?: "—", dmy(d.dgs2Date),
                r.context.rateShock == true, InfoKind.DGS2,
                sub = dgs2Change(d)?.let { signed2(it) + " in 6 mo" }),
            Tile("CAPE (total return)", d.cape?.let { (if (d.capeEstimated) "≈" else "") + fmt("%.1f", it) } ?: "—",
                dmy(d.capeDate), r.context.capeHigh == true, InfoKind.CAPE,
                sub = if (d.cape != null && d.capeEstimated) "estimate" else null),
        )),
    )
}

/** "inverted since …" / "last inverted …" / "not inverted since 2007" for the yield-curve tile. */
internal fun inversionText(d: fi.marketwatcher.engine.Day): String? = when {
    d.t10y2y == null -> null
    d.t10y2yInvertedSince != null && (d.t10y2y ?: 0.0) < 0 -> "inverted since ${dmy(d.t10y2yInvertedSince)}"
    d.t10y2yLastInverted != null -> "last inverted ${dmy(d.t10y2yLastInverted)}"
    else -> "not inverted since 2007"
}

/** The 2-year yield's change over about 6 months (the Rate shock rule), in percentage points. */
internal fun dgs2Change(d: fi.marketwatcher.engine.Day): Double? =
    if (d.dgs2 != null && d.dgs2SixMonthsAgo != null) d.dgs2 - d.dgs2SixMonthsAgo else null

/**
 * Tiles in rows of [perRow]; groups are joined in pairs when perRow is 6 (landscape).
 * With [onInfo], tiles that have an explanation show ⓘ and open it when tapped.
 */
@Composable
internal fun ValueGrid(groups: List<TileGroup>, perRow: Int, onInfo: ((InfoKind) -> Unit)? = null) {
    // Rows of at most perRow tiles. Wide screens join neighbouring groups that fit in one row;
    // a larger group continues on the next row without repeating its heading.
    val rows = ArrayList<TileGroup>()
    for (g in groups) {
        val last = rows.lastOrNull()
        if (perRow >= 6 && last != null && last.title.isNotEmpty() && last.tiles.size + g.tiles.size <= perRow) {
            rows[rows.size - 1] = TileGroup(last.title + " · " + g.title, last.tiles + g.tiles)
        } else {
            g.tiles.chunked(perRow).forEachIndexed { i, chunk -> rows += TileGroup(if (i == 0) g.title else "", chunk) }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { i, g ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (g.title.isNotEmpty()) Text(g.title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = if (i == 0) 0.dp else 4.dp).semantics { heading() })
                // Same height for every tile in a row, even when one has an extra line.
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    g.tiles.forEach { t ->
                        val open = if (onInfo != null && t.info != null) ({ onInfo(t.info) }) else null
                        ValueTile(t, open, Modifier.weight(1f).fillMaxHeight())
                    }
                    repeat(perRow - g.tiles.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ValueTile(v: Tile, onClick: (() -> Unit)?, modifier: Modifier) {
    val warn = palette().warn
    val border = if (v.flagged) warn.copy(alpha = 0.7f) else MaterialTheme.colorScheme.outlineVariant
    val shape = RoundedCornerShape(12.dp)
    val tap = if (onClick != null) Modifier.clip(shape).clickable(onClickLabel = "Explain ${v.label}", onClick = onClick)
              else Modifier
    Column(modifier.then(tap).border(BorderStroke(1.dp, border), shape)
        .testTag("tile:${v.label}")
        .semantics { stateDescription = if (v.flagged) "Warning on" else "Warning off" }
        .padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(v.label, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
            if (onClick != null) Text(" ⓘ", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
        }
        Text(v.value, fontSize = 17.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
            color = if (v.flagged) warn else MaterialTheme.colorScheme.onSurface)
        Text(v.date, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (v.sub != null) Text(v.sub, fontSize = 10.sp, lineHeight = 12.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, color = if (v.flagged) warn else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- Suggested box and "why" dialog

/** The "Suggested: <State>" box. Tapping it opens the explanation. */
@Composable
internal fun StateBox(r: Reading, large: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, showDate: Boolean = false) {
    val col = palette().of(r.state)
    Surface(onClick = onClick, color = col.copy(alpha = 0.14f), shape = RoundedCornerShape(if (large) 16.dp else 12.dp),
        modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = if (large) 18.dp else 10.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.label, color = col, fontSize = if (large) 24.sp else 17.sp, fontWeight = FontWeight.Bold)
                // Today, when the S&P 500 tiles are newer: the state is for the last day with VIX.
                if (showDate) Text("for ${dmy(r.date)} · waiting for VIX of newer days", color = col, fontSize = 12.sp)
            }
            Box(Modifier.size(if (large) 30.dp else 26.dp).background(col.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center) {
                Text("?", color = col, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

/** Why the state is what it is: the engine's rules in checking order, with that day's numbers. */
@Composable
internal fun WhyDialog(r: Reading, onClose: () -> Unit) {
    val col = palette().of(r.state)
    val steps = remember(r) { Explain.steps(r) }
    val notes = remember(r) { Explain.notes(r) }
    AppDialog(r.label, col, "Reading for ${dmy(r.date)}. Rules are checked from the top; the first that applies sets the state.",
        onClose) {
        steps.forEach { s -> StepRow(s, col) }
        Spacer(Modifier.size(6.dp))
        notes.forEach { Text(it, fontSize = 13.sp) }
    }
}

/** Dialog frame shared by all pop-ups: title, scrolling body, Close at the bottom (back and outside tap also close). */
@Composable
internal fun AppDialog(
    title: String, titleColor: Color, subtitle: String?, onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(24.dp), tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f).padding(vertical = 16.dp)) {
            Column(Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
                Text(title, color = titleColor, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, end = 12.dp, bottom = 4.dp).semantics { heading() })
                if (subtitle != null) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp, bottom = 8.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}

@Composable
private fun StepRow(s: Explain.Step, col: Color) {
    val decided = s.outcome == Explain.Outcome.DECIDED
    val mark = when (s.outcome) { Explain.Outcome.DECIDED -> "✓"; Explain.Outcome.NOT_MET -> "✗"; Explain.Outcome.SKIPPED -> "·" }
    val bg = if (decided) col.copy(alpha = 0.12f) else Color.Transparent
    Row(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(10.dp)).padding(8.dp)
        .alpha(if (s.outcome == Explain.Outcome.SKIPPED) 0.5f else 1f)) {
        Text(mark, fontWeight = FontWeight.Bold, color = if (decided) col else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(22.dp))
        Column {
            Text(s.rule, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(s.detail, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- one-year chart

/**
 * One year of the S&P 500 up to [index] (line coloured by state) with the dashed 200-day average.
 * [index] is read only while drawing, so a moving History cursor redraws this chart without
 * recomposing anything. [cursor] (Today) marks a selected day inside the fixed window the same way.
 */
@Composable
internal fun MiniChart(data: ChartData, index: () -> Int, modifier: Modifier, cursor: (() -> Int)? = null) {
    val pal = palette()
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val bg = MaterialTheme.colorScheme.surface
    val smaPath = remember { Path() }
    Canvas(modifier) {
        val px = 1.dp.toPx()
        val end = index().coerceIn(0, data.n - 1)
        val start = max(0, end - 251)
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (i in start..end) {
            lo = min(lo, data.spx[i]); hi = max(hi, data.spx[i])
            val s = data.sma[i]
            if (!s.isNaN()) { lo = min(lo, s); hi = max(hi, s) }
        }
        if (hi <= lo) hi = lo + 1f
        val padY = 6f * px
        val right = 6f * px
        val span = max(1, end - start).toFloat()
        fun x(i: Int) = (i - start) / span * (size.width - right)
        fun y(v: Float) = padY + (size.height - 2 * padY) * (1f - (v - lo) / (hi - lo))

        smaPath.reset()
        var started = false
        for (i in start..end) {
            val s = data.sma[i]
            if (s.isNaN()) continue
            if (started) smaPath.lineTo(x(i), y(s)) else { smaPath.moveTo(x(i), y(s)); started = true }
        }
        drawPath(smaPath, pal.sma, style = Stroke(width = 1.5f * px,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f * px, 4f * px))))
        for (i in start + 1..end) {
            drawLine(pal.of(data.states[i]), Offset(x(i - 1), y(data.spx[i - 1])), Offset(x(i), y(data.spx[i])),
                strokeWidth = 2f * px, cap = StrokeCap.Round)
        }
        drawCircle(pal.of(data.states[end]), radius = 3.5f * px, center = Offset(x(end), y(data.spx[end])))
        if (cursor != null) {
            val c = cursor().coerceIn(start, end)
            if (c != end) {
                drawLine(ink, Offset(x(c), 0f), Offset(x(c), size.height), strokeWidth = px)
                drawCircle(bg, radius = 5.5f * px, center = Offset(x(c), y(data.spx[c])))
                drawCircle(pal.of(data.states[c]), radius = 3.5f * px, center = Offset(x(c), y(data.spx[c])))
            }
        }
    }
}

/** Line key under the Today chart (the values are in the tiles under the scroller). */
@Composable
internal fun ChartKey() {
    val pal = palette()
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        KeyItem("S&P 500", MaterialTheme.colorScheme.onSurface, dashed = false)
        KeyItem("200-day average", pal.sma, dashed = true)
    }
}

@Composable
private fun KeyItem(label: String, color: Color, dashed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 16.dp, height = 8.dp)) {
            val y = size.height / 2
            drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(),
                pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
        }
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Legend under the one-year chart: latest values, each with its date on its own line. */
@Composable
internal fun MiniLegend(r: Reading) {
    val pal = palette()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        LegendItem("S&P 500", fmt("%,.0f", r.spx), dmy(r.date), dashed = false,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        LegendItem("200-day average", r.sma200?.let { fmt("%,.0f", it) } ?: "—", dmy(r.date), dashed = true,
            color = pal.sma, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun LegendItem(label: String, value: String, date: String, dashed: Boolean, color: Color, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(width = 16.dp, height = 8.dp)) {
                val y = size.height / 2
                drawLine(color, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx(),
                    pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())) else null)
            }
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(value, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
        Text(date, fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** State colour key used under the History charts. */
@Composable
internal fun StateKey() {
    val pal = palette()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        State.entries.forEach { st ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).background(pal.of(st), RoundedCornerShape(2.dp)))
                Spacer(Modifier.width(4.dp))
                Text(st.title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
