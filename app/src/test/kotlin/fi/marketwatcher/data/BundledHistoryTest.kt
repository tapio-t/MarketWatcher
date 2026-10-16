package fi.marketwatcher.data

import fi.marketwatcher.TestData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bundled 2007–2026 history that the build downloads from MarketWatcher-data. Fails (on GitHub,
 * where the data is required) if the download brought the wrong or a cut-off file.
 */
class BundledHistoryTest {
    private val days by lazy { BundledHistory.parse(TestData.history()) }

    @Test fun fullRangeFrom2007() {
        assertEquals(4961, days.size)
        assertEquals("2007-01-03", days.first().date)
        assertEquals("2026-09-22", days.last().date)
        assertEquals(1416.6, days.first().spx, 1e-9)
    }

    @Test fun datesStrictlyIncreasing() {
        assertTrue(days.zipWithNext().all { (a, b) -> a.date < b.date })
    }

    @Test fun assetNamesMatchTheApp() {
        // BundledData checks for these names to show the "no bundled history" line.
        assertTrue(TestData.history().isNotEmpty())
        assertTrue(TestData.cape().isNotEmpty())
        assertEquals("replay_2007_2026.json", BundledData.HISTORY)
        assertEquals("cape_tr_shiller.json", BundledData.CAPE)
    }
}
