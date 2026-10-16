package fi.marketwatcher.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.marketwatcher.data.BundledHistory
import fi.marketwatcher.engine.SignalEngine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Today: the scroller under the past-year chart moves the six tiles to the selected day. */
@RunWith(AndroidJUnit4::class)
class TodayScrollerTest {
    @get:Rule val rule = createComposeRule()

    @Test fun scrollerMovesSelectedDay() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val data = ChartData(SignalEngine.run(BundledHistory.load(ctx)))
        rule.setContent { MaterialTheme { TodayScreen(data.readings.last(), null, data, true, false, 0L) {} } }

        // Close appears three times: the day change group, today's grid and the selected day's grid.
        assertEquals(3, rule.onAllNodesWithTag("tile:Close").fetchSemanticsNodes().size)
        rule.onNodeWithTag("todaySelectedDate").performScrollTo().assertTextEquals("22.09.2026 · latest")

        rule.onNodeWithContentDescription("Previous day").performScrollTo().performClick()
        rule.onNodeWithTag("todaySelectedDate").assertTextEquals("21.09.2026")

        // Dragging the bar to its left end selects the first day of the one-year window.
        rule.onNodeWithContentDescription("Day").performScrollTo().performTouchInput { swipeLeft(startX = right - 1f, endX = left) }
        val first = data.readings[maxOf(0, data.n - 252)].date
        rule.onNodeWithTag("todaySelectedDate").assertTextEquals(dmy(first))
    }
}
