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

    @Test fun officialBeforeTheLatestMonthEstimateAfter() {
        val march2009 = h.valueOn(LocalDate.of(2009, 3, 9), 676.53)!!
        assertFalse(march2009.estimated)
        assertEquals(14.5927, march2009.value, 1e-4)
        val oct = h.valueOn(LocalDate.of(2026, 10, 1), 7651.54)!!
        assertTrue(oct.estimated)
        assertEquals(43.2066 * 7651.54 / 7631.47, oct.value, 1e-3)
        assertTrue("top 10% since 1881", oct.percentile >= 0.90)
    }
}
