package fi.marketwatcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import fi.marketwatcher.data.BundledData
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fi.marketwatcher.engine.Reading
import fi.marketwatcher.engine.State
import kotlin.math.ln
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas

/** Year chips: each jumps to the lowest close of that year. */
internal val YEARS = listOf(2008, 2009, 2011, 2018, 2020, 2022, 2025)
private const val TODAY_CHIP = 0

/**
 * Landscape replay of 2008–today. Smoothness comes from keeping the moving part tiny:
 *  - the selected day lives in one [MutableIntState] that only small composables and draw
 *    lambdas read, so moving it never recomposes the screen;
 *  - the full chart (bands, S&P 500, 200-day average, VIX) is drawn once into a bitmap and only
 *    redrawn when size, data or theme change; the cursor is a separate, cheap layer;
 *  - sideways drags on the chart or the scrub bar are claimed after the touch slop, so the
 *    vertical scroll of the page cannot steal them halfway.
 */
@Composable
internal fun HistoryScreen(data: ChartData?) {
    if (data == null || data.n < 2) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val n = data.n
    // A restored selection can point past the end if the reloaded history is shorter.
    val sel = rememberSaveable(n) { mutableIntStateOf(n - 1) }.also { if (it.intValue > n - 1) it.intValue = n - 1 }
    var picked by rememberSaveable { mutableStateOf<Int?>(TODAY_CHIP) }
    var whyFor by remember { mutableStateOf<Reading?>(null) }
    // A value pop-up shows the day that was selected when its tile was tapped.
    var infoFor by remember { mutableStateOf<Pair<InfoKind, Reading>?>(null) }
    val haptic = LocalHapticFeedback.current
    val ctx = LocalContext.current
    val dataMissing = remember { BundledData.isMissing(ctx) }

    // One place that moves the selection: clamps, and ticks the phone at each new year.
    val select: (Int) -> Unit = remember(data, haptic) {
        { i ->
            val c = i.coerceIn(0, n - 1)
            val old = sel.intValue
            if (c != old) {
                if (data.year[c] != data.year[old]) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                sel.intValue = c
            }
        }
    }
    val scrub: (Int) -> Unit = remember(select) { { i -> picked = null; select(i) } }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val chartHeight = (maxHeight * 0.62f).coerceAtLeast(180.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = picked == TODAY_CHIP, onClick = { picked = TODAY_CHIP; select(n - 1) },
                    label = { Text("Today", fontSize = 12.sp) })
                YEARS.forEach { y ->
                    val low = remember(data, y) { data.lowestCloseOf(y) }
                    if (low != null) FilterChip(selected = picked == y, onClick = { picked = y; select(low) },
                        label = { Text("$y", fontSize = 12.sp) })
                }
            }
            if (dataMissing) Text(BundledData.MISSING_TEXT, fontSize = 12.sp, lineHeight = 16.sp,
                color = palette().warn, modifier = Modifier.testTag("bundledDataMissing"))
            BigChart(data, sel, scrub, Modifier.fillMaxWidth().height(chartHeight))
            Scrubber(0..n - 1, sel, scrub)
            DayLine(data, sel)
            JumpNote(data, picked)
            SelectedState(data, sel) { whyFor = it }
            SelectedValues(data, sel) { kind, r -> infoFor = kind to r }
            RecoveryTile(data, sel)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("S&P 500 · YEAR TO SELECTED DAY", fontSize = 11.sp, letterSpacing = 0.6.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                MiniChart(data, { sel.intValue }, Modifier.fillMaxWidth().height(140.dp))
                SelectedLegend(data, sel)
            }
            BubbleIngredients()
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 12.dp)) { StateKey() }
        }
    }
    whyFor?.let { r -> WhyDialog(r) { whyFor = null } }
    infoFor?.let { (kind, r) -> InfoDialog(kind, r) { infoFor = null } }
}

// Each of these reads the selection, so only they recompose when it moves.

@Composable
private fun DayLine(data: ChartData, sel: MutableIntState) {
    val r = data.readings[sel.intValue]
    Text("${dmy(r.date)} · ${r.label}", fontSize = 13.sp, fontFamily = FontFamily.Monospace, maxLines = 1)
}

@Composable
private fun SelectedState(data: ChartData, sel: MutableIntState, onWhy: (Reading) -> Unit) {
    val r = data.readings[sel.intValue]
    StateBox(r, large = false, onClick = { onWhy(r) }, modifier = Modifier.testTag("suggestedBox"))
}

@Composable
private fun SelectedValues(data: ChartData, sel: MutableIntState, onInfo: (InfoKind, Reading) -> Unit) {
    val r = data.readings[sel.intValue]
    ValueGrid(groupsFor(r, withDayChange = true), perRow = 6, onInfo = { kind -> onInfo(kind, r) })
}

@Composable
private fun SelectedLegend(data: ChartData, sel: MutableIntState) = MiniLegend(data.readings[sel.intValue])

/**
 * Directly above the Suggested box. With a year chip selected: a "What happened" card (the day's
 * computed figures + the year's note). With "Today": a plain line. Gone once the selection moves.
 */
@Composable
private fun JumpNote(data: ChartData, picked: Int?) {
    when (picked) {
        null -> return
        TODAY_CHIP -> Text("Latest full reading, ${dmy(data.readings[data.n - 1].date)}.", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            val i = data.lowestCloseOf(picked) ?: return
            val r = data.readings[i]
            NoteCard("What happened · $picked", "whatHappened") {
                Text("Lowest close ${fmt("%,.0f", r.spx)} on ${dmy(r.date)}, ${signedPct(r.drawdown)} from the all-time high.",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                YEAR_NOTES[picked]?.let { Text(it, fontSize = 13.sp, lineHeight = 18.sp) }
            }
        }
    }
}

/** The shaded info card used by "What happened" and "Ingredients of economic bubbles". */
@Composable
private fun NoteCard(title: String, tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag(tag),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { heading() })
        content()
    }
}

/** Static card below the year chart (does not read the selection, so scrubbing never recomposes it). */
@Composable
private fun BubbleIngredients() = NoteCard(BUBBLE_TITLE, "bubbleIngredients") {
    BUBBLE_INGREDIENTS.forEach { item ->
        Row {
            Text("–", fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.width(14.dp))
            Text(item, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        }
    }
    Text("Source: $BUBBLE_SOURCE", fontSize = 12.sp, fontStyle = FontStyle.Italic, lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
}

/**
 * Recovery time of the all-time high in effect on the selected day: from the high's date to the first
 * later close at or above it. Full width, outlined like the value tiles.
 */
@Composable
private fun RecoveryTile(data: ChartData, sel: MutableIntState) {
    val i = sel.intValue
    val r = data.readings[i]
    val k = data.recovery[i]
    val latest = data.readings[data.n - 1].date
    val (value, line2) = when {
        r.date == r.athDate -> "At an all-time high" to "Close ${fmt("%,.0f", r.spx)} on ${dmy(r.date)}"
        k >= 0 -> yearsMonths(r.athDate, data.readings[k].date) to "Reached again ${dmy(data.readings[k].date)}"
        else -> "Not reached again" to "${yearsMonths(r.athDate, latest)} so far (to ${dmy(latest)})"
    }
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.fillMaxWidth().border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .testTag("recoveryTile")
            .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("RECOVERY TIME", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Text("All-time high ${fmt("%,.0f", r.ath)} on ${dmy(r.athDate)}", fontSize = 12.sp,
            fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(line2, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ---------------------------------------------------------------- big chart

/** Geometry shared by the static layer, the cursor and touch handling. */
private class ChartGeom(val w: Float, val h: Float, val n: Int, private val lo: Double, private val hi: Double, px: Float) {
    val left = 40f * px
    val right = 4f * px
    val top = 4f * px
    private val gap = 16f * px
    private val bottom = 14f * px
    val pH = (h - top - gap - bottom) * 0.68f
    val vTop = top + pH + gap
    val vH = h - vTop - bottom
    private val step = if (n > 1) (w - left - right) / (n - 1) else 0f
    val bandW = step + 0.6f
    fun x(i: Int) = left + i * step
    fun index(x: Float) = if (step <= 0f) 0 else ((x - left) / step).roundToInt().coerceIn(0, n - 1)
    fun ly(v: Double) = (top + pH * (1 - (ln(v) - ln(lo)) / (ln(hi) - ln(lo)))).toFloat()
    fun vy(v: Double) = (vTop + vH * (1 - v.coerceAtMost(85.0) / 85.0)).toFloat()
}

private class ChartColors(
    val ink: Color, val ink2: Color, val grid: Color, val bg: Color, val pal: Palette,
)

@Composable
private fun BigChart(data: ChartData, sel: MutableIntState, onScrub: (Int) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val colors = ChartColors(cs.onSurface, cs.onSurfaceVariant, cs.outlineVariant, cs.surface, palette())
    val measurer = rememberTextMeasurer()
    val scrub by rememberUpdatedState(onScrub)

    Box(modifier
        .pointerInput(data) {
            detectTapGestures { p -> scrub(ChartGeom(size.width.toFloat(), size.height.toFloat(), data.n, data.lo, data.hi, 1.dp.toPx()).index(p.x)) }
        }
        .pointerInput(data) {
            val g = { ChartGeom(size.width.toFloat(), size.height.toFloat(), data.n, data.lo, data.hi, 1.dp.toPx()) }
            detectHorizontalDragGestures(onDragStart = { p -> scrub(g().index(p.x)) }) { change, _ ->
                change.consume()
                scrub(g().index(change.position.x))
            }
        }
        .semantics { contentDescription = "S&P 500 and VIX, 2008 to today. Drag sideways to pick a day." }
    ) {
        // Static layer: rendered once into a bitmap per size, data and theme; then only copied.
        Box(Modifier.fillMaxSize().drawWithCache {
            val w = size.width.toInt().coerceAtLeast(1)
            val h = size.height.toInt().coerceAtLeast(1)
            val bitmap = ImageBitmap(w, h)
            CanvasDrawScope().draw(this as Density, layoutDirection, GraphicsCanvas(bitmap), Size(w.toFloat(), h.toFloat())) {
                drawStatic(data, ChartGeom(w.toFloat(), h.toFloat(), data.n, data.lo, data.hi, 1.dp.toPx()), colors, measurer)
            }
            onDrawBehind { drawImage(bitmap) }
        })
        // Cursor layer: the only part redrawn while the selection moves (state read in the draw phase).
        Canvas(Modifier.fillMaxSize()) {
            val px = 1.dp.toPx()
            val g = ChartGeom(size.width, size.height, data.n, data.lo, data.hi, px)
            val i = sel.intValue.coerceIn(0, data.n - 1)
            val r = data.readings[i]
            drawLine(colors.ink, Offset(g.x(i), g.top), Offset(g.x(i), g.vTop + g.vH), px)
            drawCircle(colors.bg, radius = 5.5f * px, center = Offset(g.x(i), g.ly(r.spx)))
            drawCircle(colors.pal.of(r.state), radius = 4f * px, center = Offset(g.x(i), g.ly(r.spx)))
        }
    }
}

private fun DrawScope.drawStatic(data: ChartData, g: ChartGeom, c: ChartColors, measurer: TextMeasurer) {
    val px = 1.dp.toPx()
    val history = data.readings
    val n = data.n
    val label = TextStyle(fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = c.ink2)
    val textH = 12f * px

    // state bands as runs
    var s0 = 0
    for (i in 1..n) if (i == n || data.states[i] != data.states[s0]) {
        val st = data.states[s0]
        drawRect(c.pal.of(st).copy(alpha = if (st == State.CALM) 0.05f else 0.22f),
            topLeft = Offset(g.x(s0), g.top), size = Size(g.x(i - 1) - g.x(s0) + g.bandW, g.pH))
        s0 = i
    }
    listOf(750.0, 1500.0, 3000.0, 6000.0).filter { it in data.lo..data.hi }.forEach { v ->
        drawLine(c.grid, Offset(g.left, g.ly(v)), Offset(g.w - g.right, g.ly(v)), px)
        drawText(measurer, fmt("%,.0f", v), Offset(0f, g.ly(v) - textH / 2), label)
    }
    listOf(0.0, 40.0, 80.0).forEach { v ->
        drawLine(c.grid, Offset(g.left, g.vy(v)), Offset(g.w - g.right, g.vy(v)), px)
        drawText(measurer, fmt("%.0f", v), Offset(0f, g.vy(v) - textH / 2), label)
    }
    for (i in 1 until n) if (data.year[i] != data.year[i - 1] && data.year[i] % 2 == 0) {
        drawText(measurer, "${data.year[i]}", Offset(g.x(i) - 11f * px, g.h - textH), label)
    }
    drawText(measurer, "S&P 500 (log)", Offset(g.left + 4f * px, g.top), label)
    drawText(measurer, "VIX, VIX3M dashed", Offset(g.left + 4f * px, g.vTop - textH - 2f * px), label)

    val price = Path(); val sma = Path(); val vix = Path(); val vixInv = Path(); val vix3m = Path()
    var smaStarted = false
    history.forEachIndexed { i, r ->
        if (i == 0) {
            price.moveTo(g.x(i), g.ly(r.spx)); vix3m.moveTo(g.x(i), g.vy(r.vix3m))
        } else {
            price.lineTo(g.x(i), g.ly(r.spx)); vix3m.lineTo(g.x(i), g.vy(r.vix3m))
            val p = if (r.vixRatio >= 1.0) vixInv else vix
            p.moveTo(g.x(i - 1), g.vy(history[i - 1].vix)); p.lineTo(g.x(i), g.vy(r.vix))
        }
        r.sma200?.let { s ->
            if (smaStarted) sma.lineTo(g.x(i), g.ly(s)) else { sma.moveTo(g.x(i), g.ly(s)); smaStarted = true }
        }
    }
    drawPath(sma, c.pal.sma, style = Stroke(width = 1.2f * px))
    drawPath(price, c.ink, style = Stroke(width = 1.2f * px))
    drawPath(vix3m, c.ink2, style = Stroke(width = 0.9f * px,
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3f * px, 3f * px))))
    drawPath(vix, c.pal.vix, style = Stroke(width = 1.1f * px))
    drawPath(vixInv, c.pal.panic, style = Stroke(width = 1.1f * px))
}
