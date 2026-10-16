package fi.marketwatcher.ui

import fi.marketwatcher.TestData
import fi.marketwatcher.engine.Day
import fi.marketwatcher.engine.SignalEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YearNotesTest {
    @Test fun everyYearChipHasANote() {
        YEARS.forEach { assertTrue("note for $it", !YEAR_NOTES[it].isNullOrBlank()) }
    }

    @Test fun notesAreDescriptiveOnly() {
        YEAR_NOTES.values.forEach { n ->
            val t = n.lowercase()
            assertFalse(n, Regex("\\b(buy|sell|you should)\\b").containsMatchIn(t))
        }
    }

    /** The card's date is the computed lowest close of the year, matching the dates the notes describe. */
    @Test fun lowestClosesMatchTheNotes() {
        val o = JSONObject(TestData.golden())
        val d = o.getJSONArray("days")
        val data = ChartData(SignalEngine.run((0 until d.length()).map { i ->
            val a = d.getJSONArray(i)
            Day(a.getString(0), a.getDouble(1), a.getDouble(2), if (a.isNull(3)) Double.NaN else a.getDouble(3))
        }))
        val expected = mapOf(2008 to "2008-11-20", 2009 to "2009-03-09", 2011 to "2011-10-03", 2018 to "2018-12-24",
            2020 to "2020-03-23", 2022 to "2022-10-12", 2025 to "2025-04-08")
        YEARS.forEach { y -> assertEquals("$y", expected[y], data.readings[data.lowestCloseOf(y)!!].date) }
    }

    @Test fun bubbleIngredientsCard() {
        assertEquals(10, BUBBLE_INGREDIENTS.size)
        BUBBLE_INGREDIENTS.forEach { assertTrue(it.isNotBlank()) }
        assertTrue(BUBBLE_SOURCE.startsWith("Heikki Keskiväli"))
        assertTrue(BUBBLE_SOURCE.endsWith("December 13, 2020"))
    }
}
