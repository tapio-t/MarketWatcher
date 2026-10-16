package fi.marketwatcher.engine

import fi.marketwatcher.TestData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Price-only days (S&P 500 close before VIX is published) and the sharp-fall tiers. */
class PriceDaysTest {
    private val days: List<Day> by lazy {
        val d = JSONObject(TestData.golden()).getJSONArray("days")
        (0 until d.length()).map { i ->
            val a = d.getJSONArray(i)
            Day(a.getString(0), a.getDouble(1), a.getDouble(2), if (a.isNull(3)) Double.NaN else a.getDouble(3))
        }
    }

    /** Leaving out the last 30 days' VIX gives the same price values as the engine's full readings. */
    @Test fun priceOnlyDaysMatchTheEngine() {
        val full = SignalEngine.run(days).associateBy { it.date }
        val cut = days.size - 30
        val views = PriceDays.after(days.take(cut), days.associate { it.date to it.spx })
        assertEquals(30, views.size)
        for (v in views) {
            val r = full.getValue(v.date).price()
            assertEquals(v.date, r.copy(complete = false), v)
        }
    }

    @Test fun noNewerCloseNoPriceDays() {
        assertTrue(PriceDays.after(days, days.associate { it.date to it.spx }).isEmpty())
    }

    @Test fun fallTiers() {
        assertNull(Fall.of(-0.0199))
        assertEquals(Fall.SHARP, Fall.of(-0.0200))
        assertEquals(Fall.SHARP, Fall.of(-0.02994))
        assertEquals(Fall.VERY_SHARP, Fall.of(-0.0300))
        assertEquals(Fall.EXTREME, Fall.of(-0.0500))
        assertNull(Fall.of(0.03))
    }

    /**
     * 2008–2026 in the bundled history: 208 / 82 / 19 days of −2.00% / −3.00% / −5.00% or worse as shown
     * (31.07.2014 was −1.99993%, shown and counted as −2.00%).
     */
    @Test fun historicCounts() {
        val r = SignalEngine.run(days).map { it.price() }
        assertEquals(208, r.count { it.fall != null })
        assertEquals(82, r.count { it.fall == Fall.VERY_SHARP || it.fall == Fall.EXTREME })
        assertEquals(19, r.count { it.fall == Fall.EXTREME })
    }
}
