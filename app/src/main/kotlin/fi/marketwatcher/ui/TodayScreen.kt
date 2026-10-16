package fi.marketwatcher.ui

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.marketwatcher.engine.PriceView
import fi.marketwatcher.engine.Reading
import fi.marketwatcher.engine.price
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.roundToInt

private val HM_DMY: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm dd.MM.yyyy")

/** Full interactive SPX chart (not the symbol overview page). */
internal const val TRADINGVIEW_SPX_CHART = "https://www.tradingview.com/chart/?symbol=SP%3ASPX"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TodayScreen(
    t: Reading?, price: PriceView?, chart: ChartData?, hasApiKey: Boolean, loading: Boolean, checkedAt: Long,
    onRefresh: () -> Unit,
) {
    var showWhy by rememberSaveable { mutableStateOf(false) }
    var info by rememberSaveable { mutableStateOf<InfoKind?>(null) }
    // A pop-up from the tiles under the scroller shows the day selected when the tile was tapped.
    var dayInfo by remember { mutableStateOf<Pair<InfoKind, Reading>?>(null) }
    val uri = LocalUriHandler.current
    PullToRefreshBox(isRefreshing = loading, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("MarketWatcher", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (hasApiKey && checkedAt > 0) {
                // When FRED was last checked; opening the app checks again only after 30 minutes.
                val at = Instant.ofEpochMilli(checkedAt).atZone(ZoneId.systemDefault())
                val text = at.format(HM_DMY)
                // The S&P 500 close comes ~13 hours before VIX: show how far each part reaches.
                val days = if (t != null && price != null && price.date != t.date)
                    " · S&P 500 to ${dmy(price.date)} · VIX to ${dmy(t.date)}" else ""
                Text("Data checked $text$days · pull down to check now", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 0.dp))
            }
            if (t == null) {
                Text(if (!hasApiKey) "Add your free FRED API key in Settings to start."
                     else "No data yet. Pull down to refresh.")
            } else {
                val p = price ?: t.price()
                StateBox(t, large = true, onClick = { showWhy = true }, showDate = p.date != t.date)
                ValueGrid(listOf(dayChangeGroup(p)), perRow = 3, onInfo = { info = it })
                ValueGrid(groupsFor(t, p), perRow = 3, onInfo = { info = it })
                if (chart != null) PastYear(chart) { kind, r -> dayInfo = kind to r }
            }
            TextButton(onClick = { runCatching { uri.openUri(TRADINGVIEW_SPX_CHART) } },
                modifier = Modifier.padding(top = 4.dp)) {
                Text("Open the S&P 500 chart on TradingView ↗")
            }
            Column(Modifier.padding(horizontal = 12.dp).padding(bottom = 16.dp)) {
                Text("“It is imminent — the only thing that's unknown is the timing, location, duration, " +
                    "magnitude, policy response, recovery dynamics, and cultural impact.”",
                    fontStyle = FontStyle.Italic, fontSize = 14.sp, lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("— Morgan Housel", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    if (showWhy && t != null) WhyDialog(t) { showWhy = false }
    val kind = info
    if (kind != null && t != null) InfoDialog(kind, t, price ?: t.price()) { info = null }
    dayInfo?.let { (k, r) -> InfoDialog(k, r) { dayInfo = null } }
}

/**
 * Past-year chart with a day scroller and the selected day's six S&P 500 and Trend tiles. The selected
 * day lives in one [MutableIntState] read only by the cursor, the scroller and [SelectedDay], so moving
 * it never recomposes the rest of the Today screen.
 */
@Composable
private fun PastYear(chart: ChartData, onInfo: (InfoKind, Reading) -> Unit) {
    val last = chart.n - 1
    val first = max(0, last - 251)                          // the chart's one-year window
    // Starts at the latest day; back to it whenever new data arrives (n changes).
    // A restored selection can fall outside the window if the reloaded history differs.
    val sel = rememberSaveable(chart.n) { mutableIntStateOf(last) }.also { if (it.intValue !in first..last) it.intValue = last }
    val scrub: (Int) -> Unit = remember(sel, first, last) { { i -> sel.intValue = i.coerceIn(first, last) } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("S&P 500 · PAST YEAR", fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.6.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PastYearChart(chart, first, last, sel, scrub)
        ChartKey()
        Scrubber(first..last, sel, scrub)
        SelectedDay(chart, sel, onInfo)
    }
}

/** The past-year chart; tap or drag sideways on it to pick a day (same as the History chart). */
@Composable
private fun PastYearChart(chart: ChartData, first: Int, last: Int, sel: MutableIntState, onScrub: (Int) -> Unit) {
    val scrub by rememberUpdatedState(onScrub)
    // x position -> day index; [right] is MiniChart's 6 dp right padding in pixels.
    fun index(x: Float, width: Float, right: Float): Int {
        val f = (x / (width - right).coerceAtLeast(1f)).coerceIn(0f, 1f)
        return first + (f * (last - first)).roundToInt()
    }
    MiniChart(chart, { last }, Modifier.fillMaxWidth().height(150.dp)
        .pointerInput(first, last) {
            val right = 6.dp.toPx()
            detectTapGestures { p -> scrub(index(p.x, size.width.toFloat(), right)) }
        }
        .pointerInput(first, last) {
            val right = 6.dp.toPx()
            detectHorizontalDragGestures(onDragStart = { p -> scrub(index(p.x, size.width.toFloat(), right)) }) { change, _ ->
                change.consume()
                scrub(index(change.position.x, size.width.toFloat(), right))
            }
        }
        .semantics { contentDescription = "S&P 500, past year. Drag sideways to pick a day." },
        cursor = { sel.intValue })
}

/** Date and state of the selected day, then its six S&P 500 and Trend tiles. Only this recomposes while scrubbing. */
@Composable
private fun SelectedDay(chart: ChartData, sel: MutableIntState, onInfo: (InfoKind, Reading) -> Unit) {
    val i = sel.intValue
    val r = chart.readings[i]
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dmy(r.date) + if (i == chart.n - 1) " · latest" else "", fontSize = 13.sp,
                fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.testTag("todaySelectedDate"))
            Text(r.label, fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).weight(1f, fill = false))
        }
        ValueGrid(priceGroups(r), perRow = 3, onInfo = { kind -> onInfo(kind, r) })
    }
}
