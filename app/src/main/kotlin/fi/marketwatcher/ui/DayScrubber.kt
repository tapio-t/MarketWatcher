package fi.marketwatcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ---------------------------------------------------------------- scrub bar with one-day steps

/**
 * Day scroller shared by History (whole history) and Today (past year): ‹ and › step one day
 * (hold to repeat), the bar can be tapped or dragged. [range] is the span of day indices it covers.
 */
@Composable
internal fun Scrubber(range: IntRange, sel: MutableIntState, onScrub: (Int) -> Unit) {
    val scrub by rememberUpdatedState(onScrub)
    val track = MaterialTheme.colorScheme.outlineVariant
    val fill = palette().vix
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RepeatButton("‹", "Previous day") { scrub(sel.intValue - 1) }
        Box(Modifier.weight(1f).height(40.dp)
            .pointerInput(range) {
                detectTapGestures { p -> scrub(trackIndex(p.x, size.width.toFloat(), 10.dp.toPx(), range)) }
            }
            .pointerInput(range) {
                val r = 10.dp.toPx()
                detectHorizontalDragGestures(onDragStart = { p -> scrub(trackIndex(p.x, size.width.toFloat(), r, range)) }) { change, _ ->
                    change.consume()
                    scrub(trackIndex(change.position.x, size.width.toFloat(), r, range))
                }
            }
            .semantics { contentDescription = "Day"; role = Role.Button }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val r = 10.dp.toPx()
                val y = size.height / 2
                val f = ((sel.intValue - range.first).toFloat() / (range.last - range.first).coerceAtLeast(1)).coerceIn(0f, 1f)
                val x = r + f * (size.width - 2 * r)
                drawLine(track, Offset(r, y), Offset(size.width - r, y), strokeWidth = 4.dp.toPx())
                drawLine(fill, Offset(r, y), Offset(x, y), strokeWidth = 4.dp.toPx())
                drawCircle(fill, radius = r, center = Offset(x, y))
            }
        }
        RepeatButton("›", "Next day") { scrub(sel.intValue + 1) }
    }
}

/** Day index under [x] on a track of [width] with knob radius [r], mapped onto [range]. */
internal fun trackIndex(x: Float, width: Float, r: Float, range: IntRange): Int {
    val f = ((x - r) / (width - 2 * r).coerceAtLeast(1f)).coerceIn(0f, 1f)
    return range.first + (f * (range.last - range.first)).roundToInt()
}

/** One step per tap; hold to repeat (after 0.4 s, then 20 days per second). */
@Composable
private fun RepeatButton(text: String, description: String, onStep: () -> Unit) {
    val step by rememberUpdatedState(onStep)
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                coroutineScope {
                    awaitEachGesture {
                        awaitFirstDown()
                        step()
                        val repeat = launch {
                            delay(400)
                            while (true) { step(); delay(50) }
                        }
                        waitForUpOrCancellation()
                        repeat.cancel()
                    }
                }
            }
            .semantics { contentDescription = description; role = Role.Button; onClick { step(); true } },
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 22.sp, maxLines = 1, overflow = TextOverflow.Clip,
            modifier = Modifier.widthIn(min = 12.dp))
    }
}
