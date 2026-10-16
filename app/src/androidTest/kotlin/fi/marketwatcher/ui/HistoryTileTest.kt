package fi.marketwatcher.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fi.marketwatcher.data.BundledHistory
import fi.marketwatcher.engine.SignalEngine
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** History: the VIX / VIX3M tile follows the selected day (amber at 1.00 or above). */
@RunWith(AndroidJUnit4::class)
class HistoryTileTest {
    @get:Rule val rule = createComposeRule()

    private fun warning(on: Boolean) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, if (on) "Warning on" else "Warning off")

    @Test fun vixTileFollowsSelectedDay() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val data = ChartData(SignalEngine.run(BundledHistory.load(ctx)))
        rule.setContent { MaterialTheme { HistoryScreen(data) } }

        rule.onNodeWithText("2018").performScrollTo().performClick()          // lowest close of 2018: 24.12.2018, VIX/VIX3M 1.25
        rule.onNodeWithTag("tile:VIX / VIX3M").performScrollTo().assert(warning(true))

        rule.onNodeWithText("Today").performScrollTo().performClick()         // bundled data ends 22.09.2026, VIX/VIX3M 0.81
        rule.onNodeWithTag("tile:VIX / VIX3M").performScrollTo().assert(warning(false))
    }

    /** The "What happened" card sits directly above the Suggested box and closes when the selection moves. */
    @Test fun whatHappenedCardAboveSuggestedBox() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val data = ChartData(SignalEngine.run(BundledHistory.load(ctx)))
        rule.setContent { MaterialTheme { HistoryScreen(data) } }
        for (y in YEARS) {
            rule.onNodeWithText("$y").performScrollTo().performClick()
            val card = rule.onNodeWithTag("whatHappened").performScrollTo().fetchSemanticsNode().boundsInRoot
            val box = rule.onNodeWithTag("suggestedBox").fetchSemanticsNode().boundsInRoot
            assertTrue("$y: card must be above the Suggested box", card.bottom <= box.top)
        }
        rule.onNodeWithContentDescription("Next day").performScrollTo().performClick()
        rule.onNodeWithTag("whatHappened").assertDoesNotExist()
    }

    /** Recovery time: the 2008 low's high (09.10.2007) was reached again on 28.03.2013. */
    @Test fun recoveryTileFollowsSelectedDay() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val data = ChartData(SignalEngine.run(BundledHistory.load(ctx)))
        rule.setContent { MaterialTheme { HistoryScreen(data) } }
        rule.onNodeWithText("2008").performScrollTo().performClick()
        rule.onNodeWithTag("recoveryTile").performScrollTo()
        rule.onNodeWithText("5 years, 5 months").assertExists()
        rule.onNodeWithText("All-time high 1,565 on 09.10.2007").assertExists()
        rule.onNodeWithText("Reached again 28.03.2013").assertExists()
        rule.onNodeWithText("Today").performScrollTo().performClick()       // 22.09.2026: high of 13.08.2026 not reached yet
        rule.onNodeWithText("Not reached again").assertExists()
    }

    @Test fun bubbleIngredientsCardBelowYearChart() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val data = ChartData(SignalEngine.run(BundledHistory.load(ctx)))
        rule.setContent { MaterialTheme { HistoryScreen(data) } }
        rule.onNodeWithTag("bubbleIngredients").performScrollTo()
        rule.onNodeWithText("INGREDIENTS OF ECONOMIC BUBBLES").assertExists()
        rule.onNodeWithText("Source: $BUBBLE_SOURCE").assertExists()
    }
}
