package fi.marketwatcher.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Credit stress (0.15): NFCI above 0, or NFCI at or above −0.300 and up +0.050 in 1 week,
 * +0.115 in 4 weeks or +0.200 in 8 weeks (compared in thousandths).
 */
class CreditStressTest {
    private fun credit(nfci: Double?, w1: Double? = null, w4: Double? = null, w8: Double? = null) =
        SignalEngine.contextFor(Day("2026-09-30", 1.0, 1.0, 1.0, nfci = nfci, nfciPrev = w1, nfci4w = w4, nfci8w = w8)).creditStress

    @Test fun tinyWeeklyRiseIsNotStress() {
        // The case that showed the 0.12 bug: up 0.002 on the week, down over 4 weeks.
        assertEquals(false, credit(-0.555, w1 = -0.557, w4 = -0.551, w8 = -0.528))
        // Two equal weeks in a row
        assertEquals(false, credit(-0.250, w1 = -0.250, w4 = -0.250, w8 = -0.250))
    }

    @Test fun oneWeekBoundary() {
        assertEquals(true, credit(-0.250, w1 = -0.300))    // exactly +0.050
        assertEquals(false, credit(-0.251, w1 = -0.300))   // +0.049
    }

    @Test fun fourWeekBoundary() {
        assertEquals(true, credit(-0.185, w4 = -0.300))    // exactly +0.115
        assertEquals(false, credit(-0.186, w4 = -0.300))   // +0.114
        // 17.03.2023 (Silicon Valley Bank): −0.176, +0.119 over 4 weeks
        assertEquals(true, credit(-0.176, w1 = -0.207, w4 = -0.295, w8 = -0.290))
    }

    @Test fun eightWeekBoundary() {
        assertEquals(true, credit(-0.100, w8 = -0.300))    // exactly +0.200
        assertEquals(false, credit(-0.101, w8 = -0.300))   // +0.199
    }

    @Test fun floor() {
        assertEquals(true, credit(-0.300, w4 = -0.500))    // at the floor counts
        assertEquals(false, credit(-0.301, w4 = -0.500))   // below the floor: a big rise does not count
        // Q4 2018-type rise from deep below average
        assertEquals(false, credit(-0.423, w1 = -0.444, w4 = -0.501, w8 = -0.558))
    }

    @Test fun aboveZeroIsStressWithoutHistory() {
        assertEquals(true, credit(0.012))
        assertEquals(false, credit(-0.2))
        assertEquals(null, credit(null))
    }

    @Test fun windowsReportChangesAndMinimums() {
        val w = SignalEngine.creditWindows(Day("2026-09-30", 1.0, 1.0, 1.0, nfci = -0.176, nfciPrev = -0.207, nfci4w = -0.295))
        assertEquals(listOf(1, 4, 8), w.map { it.weeks })
        assertEquals(listOf(false, true, false), w.map { it.met })
        assertEquals(null, w[2].change)
        assertEquals("4 weeks +0.119", w[1].text)
    }
}
