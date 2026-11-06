package fi.marketwatcher.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S&P 500 closes estimated from VOO, and how they continue the price-only days. */
class VooEstimateTest {
    private val spx = mapOf("2026-11-02" to 6000.0, "2026-11-03" to 6060.0)
    private val voo = mapOf(
        "2026-11-02" to 550.0, "2026-11-03" to 555.5,
        "2026-11-04" to 544.39, "2026-11-05" to 549.83,
    )

    @Test fun scalesFredsLastCloseByVoosChange() {
        val e = VooEstimate.after(spx, voo, emptyMap())
        assertEquals(listOf("2026-11-04", "2026-11-05"), e.keys.toList())
        assertEquals(6060.0 * 544.39 / 555.5, e.getValue("2026-11-04").close, 1e-9)
        assertEquals(6060.0 * 549.83 / 555.5, e.getValue("2026-11-05").close, 1e-9)
        assertFalse(e.getValue("2026-11-04").exDividend)
    }

    @Test fun noVooCloseOnFredsLastDayNoEstimate() {
        assertTrue(VooEstimate.after(spx, voo - "2026-11-03", emptyMap()).isEmpty())
        assertTrue(VooEstimate.after(emptyMap(), voo, emptyMap()).isEmpty())
    }

    @Test fun fredCatchingUpMovesTheStart() {
        val e = VooEstimate.after(spx + ("2026-11-04" to 5940.0), voo, emptyMap())
        assertEquals(listOf("2026-11-05"), e.keys.toList())
        assertEquals(5940.0 * 549.83 / 544.39, e.getValue("2026-11-05").close, 1e-9)
    }

    /** VOO drops by its dividend on the ex-date and the index does not: the dividend is added back. */
    @Test fun dividendIsAddedBackOnTheExDate() {
        val e = VooEstimate.after(spx, voo, mapOf("2026-11-04" to 1.85))
        val d4 = e.getValue("2026-11-04")
        assertTrue(d4.exDividend)
        assertEquals(6060.0 * (544.39 + 1.85) / 555.5, d4.close, 1e-9)
        // The next day continues from VOO's real close.
        assertEquals(d4.close * 549.83 / 544.39, e.getValue("2026-11-05").close, 1e-9)
    }

    @Test fun unknownDividendAmountIsMarkedButNotCorrected() {
        val d4 = VooEstimate.after(spx, voo, mapOf("2026-11-04" to Double.NaN)).getValue("2026-11-04")
        assertTrue(d4.exDividend)
        assertEquals(6060.0 * 544.39 / 555.5, d4.close, 1e-9)
    }

    @Test fun priceDaysContinueWithEstimates() {
        val days = (1..250).map { i ->
            val d = java.time.LocalDate.of(2025, 1, 1).plusDays(i.toLong()).toString()
            Day(d, 5000.0 + i, 15.0, 17.0)
        }
        val last = days.last()
        val nextDay = java.time.LocalDate.parse(last.date).plusDays(1).toString()
        val fred = days.associate { it.date to it.spx }
        val est = mapOf(last.date to Estimate(1.0, false), nextDay to Estimate(5145.0, exDividend = true))
        val views = PriceDays.after(days, fred, est)
        assertEquals("only the day after FRED's last close", 1, views.size)
        val v = views.single()
        assertEquals(nextDay, v.date)
        assertEquals(5145.0, v.close, 0.0)
        assertEquals(last.spx, v.prevClose!!, 0.0)
        assertTrue(v.estimated && v.exDividend && !v.complete)
        assertEquals(Fall.SHARP, v.fall)
    }

    @Test fun fredPriceOnlyDayWinsOverAnEstimate() {
        val days = (1..5).map { Day("2026-10-0$it", 6000.0, 15.0, 17.0) }
        val fred = days.associate { it.date to it.spx } + ("2026-10-06" to 6100.0)
        val views = PriceDays.after(days, fred, mapOf("2026-10-06" to Estimate(5000.0, false), "2026-10-07" to Estimate(6200.0, false)))
        assertEquals(listOf("2026-10-06", "2026-10-07"), views.map { it.date })
        assertEquals(6100.0, views[0].close, 0.0)
        assertFalse(views[0].estimated)
        assertTrue(views[1].estimated)
    }
}
