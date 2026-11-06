package fi.marketwatcher.data

import fi.marketwatcher.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/** The bundled Shiller total return CAPE (from ie_data.xls) and how a day's value is chosen. */
class CapeHistoryTest {
    private val h by lazy { CapeHistory.parse(TestData.cape()) }

    @Test fun bundledRange() {
        assertEquals(1749, h.months.size)
        assertEquals(YearMonth.of(1881, 1), h.months.first().month)
        assertEquals(YearMonth.of(2026, 9), h.lastOfficial!!.month)
        assertEquals(25.8867, h.months.single { it.month == YearMonth.of(2008, 1) }.value, 1e-4)
        assertEquals(43.2066, h.lastOfficial!!.value, 1e-4)
    }

    @Test fun eachMonthItsOwnValue() {
        val march2009 = h.valueOn(LocalDate.of(2009, 3, 9))!!
        assertEquals(YearMonth.of(2009, 3), march2009.month)
        assertEquals(14.5927, march2009.value, 1e-4)
        assertFalse("low in March 2009", march2009.percentile >= 0.90)
        val sep = h.valueOn(LocalDate.of(2026, 9, 22))!!
        assertEquals(YearMonth.of(2026, 9), sep.month)
        assertEquals(43.2066, sep.value, 1e-4)
    }

    /** After the file's newest month the newest value stays, never scaled by the S&P 500 (0.22). */
    @Test fun afterTheNewestMonthTheNewestValue() {
        val sep = h.valueOn(LocalDate.of(2026, 9, 1))!!
        for (d in listOf(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 6), LocalDate.of(2027, 2, 15))) {
            assertEquals(d.toString(), sep, h.valueOn(d))
        }
        assertTrue("top 10% since 1881", sep.percentile >= 0.90)
    }
}
