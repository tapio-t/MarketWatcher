package fi.marketwatcher.engine

import fi.marketwatcher.TestData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Days below the 200-day average and the sharp-fall statistics (0.25), on the golden days 2007–2026. */
class CloseStatsTest {
    private val days: List<Day> by lazy {
        val d = JSONObject(TestData.golden()).getJSONArray("days")
        (0 until d.length()).map { i ->
            val a = d.getJSONArray(i)
            Day(a.getString(0), a.getDouble(1), a.getDouble(2), if (a.isNull(3)) Double.NaN else a.getDouble(3))
        }
    }
    private val stats by lazy { CloseStats.of(days, emptyList()) }

    /** The gaps use every fall of −3.00% or worse, extreme ones included, through the newest close. */
    @Test fun everyVerySharpFallIsCounted() {
        assertEquals(83, stats.verySharpDates.size)
        val closes = days.map { it.spx }
        val extreme = (1 until days.size).filter { SignalEngine.dayChangeBp(closes[it], closes[it - 1]) <= -500 }.map { days[it].date }
        assertEquals(19, extreme.size)
        assertTrue(stats.verySharpDates.containsAll(extreme))
        assertEquals("2007-02-27", stats.verySharpDates.first())
        assertEquals("2025-04-10", stats.verySharpDates.last())
        // −2.99% is not very sharp, −3.00% is (compared as shown, in hundredths).
        val c = CloseStats(listOf("2020-01-01", "2020-01-02", "2020-01-03"), doubleArrayOf(100.0, 97.01, 94.0997))
        assertEquals(listOf("2020-01-03"), c.verySharpDates)
    }

    @Test fun sharpFallDaysPerYear() {
        assertEquals(2007..2026, stats.countedYears)
        val per = stats.fallDaysPerYear().toMap()
        assertEquals(11, per[2007]); assertEquals(41, per[2008]); assertEquals(0, per[2017]); assertEquals(2, per[2026])
        assertEquals(219, per.values.sum())
    }

    /** Plain gap: every pair of very sharp falls; burst gap: only gaps longer than 30 days. */
    @Test fun plainAndBurstGaps() {
        assertEquals(7.0, stats.medianGapDays, 0.0)
        assertEquals(122.0, stats.medianBurstGapDays, 0.0)
        assertEquals(24, stats.bursts)
    }

    @Test fun selectedYearCounts() {
        assertEquals(41, stats.fallDays(2008, Fall.SHARP))
        assertEquals(23, stats.fallDays(2022, Fall.SHARP))
        assertEquals(7, stats.fallDays(2025, Fall.SHARP))
        assertEquals(0, stats.fallDays(2017, Fall.SHARP))
        assertEquals(11, stats.fallDays(2008, Fall.EXTREME))
    }

    /** A run that started before the History readings (2008) is counted in full. */
    @Test fun runCountedFromTheFirstClose() {
        assertEquals("2007-12-27", stats.factsFor("2009-05-29")!!.belowRunStart)
        assertEquals(358, stats.factsFor("2009-05-29")!!.belowRun)
        assertEquals(0, stats.factsFor("2009-06-01")!!.belowRun)
    }

    @Test fun factsOfOneClose() {
        val f = stats.factsFor("2025-04-10")!!
        assertEquals(12, f.belowRun)
        assertEquals("2025-03-26", f.belowRunStart)
        // On a very sharp day itself, "previous" is the one before it.
        assertEquals("2025-04-04", f.prevVerySharp)
        val april = stats.factsFor("2026-04-02")!!
        assertEquals("2025-04-10", april.prevVerySharp)
        assertEquals(-0.0346, april.prevVerySharpChange!!, 1e-9)
        assertNull("no 200-day average yet", stats.factsFor("2007-01-03")!!.belowRun)
        assertNull(stats.factsFor("1999-01-01"))
    }

    /** Any amount below counts; a close at or above the average ends the run. */
    @Test fun anyNegativeValueCounts() {
        val closes = DoubleArray(203) { 100.0 }
        closes[200] = 99.999          // just below the average
        closes[201] = 99.99
        closes[202] = 100.5           // above
        val s = CloseStats(List(203) { "2020-01-%03d".format(it) }, closes)
        assertEquals(1, s.factsFor("2020-01-200")!!.belowRun)
        assertEquals(2, s.factsFor("2020-01-201")!!.belowRun)
        assertEquals(0, s.factsFor("2020-01-202")!!.belowRun)
        assertNull(s.factsFor("2020-01-202")!!.belowRunStart)
    }

    /** Estimated closes (VOO) get their facts but stay out of the counts. */
    @Test fun estimatesAreNotCounted() {
        val last = days.last()
        val est = PriceView("2026-09-23", last.spx * 0.95, last.spx, last.date, 0.0, "", null, null, null,
            complete = false, estimated = true)
        val s = CloseStats.of(days, listOf(est))
        assertNotNull(s.factsFor("2026-09-23"))
        assertEquals(stats.fallDays(2026, Fall.SHARP), s.fallDays(2026, Fall.SHARP))
        assertTrue(s.verySharpDates.none { it == "2026-09-23" })
    }
}
