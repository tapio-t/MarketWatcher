package fi.marketwatcher.ui

import fi.marketwatcher.TestData
import fi.marketwatcher.engine.Day
import fi.marketwatcher.engine.SignalEngine
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** History "Recovery time" tile: first later close at or above the all-time high in effect. */
class RecoveryTest {
    private fun rec(closes: List<Float>, ath: List<Double>, athDate: List<String>, dates: List<String>) =
        recoveryIndices(dates, closes.toFloatArray(), ath, athDate)

    @Test fun equalCloseCountsAndHighBeforeDataStart() {
        val dates = listOf("2020-01-01", "2020-01-02", "2020-01-03", "2020-01-06", "2020-01-07")
        val close = listOf(90f, 95f, 100f, 99f, 101f)
        // High 100 set before the data starts; reached again (equal) on day 2; day 4 is a new high.
        val ath = listOf(100.0, 100.0, 100.0, 100.0, 101.0)
        val athDate = listOf("2019-12-31", "2019-12-31", "2019-12-31", "2019-12-31", "2020-01-07")
        assertArrayEquals(intArrayOf(2, 2, 2, 2, -1), rec(close, ath, athDate, dates))
    }

    @Test fun dayAtTheHighAndNeverReachedAgain() {
        val dates = listOf("2020-01-01", "2020-01-02", "2020-01-03")
        val close = listOf(100f, 90f, 95f)
        val ath = listOf(100.0, 100.0, 100.0)
        val athDate = listOf("2020-01-01", "2020-01-01", "2020-01-01")
        assertArrayEquals(intArrayOf(-1, -1, -1), rec(close, ath, athDate, dates))
    }

    @Test fun yearsAndMonths() {
        assertEquals("5 years, 5 months", yearsMonths("2007-10-09", "2013-03-28"))
        assertEquals("1 year, 1 month", yearsMonths("2015-05-21", "2016-07-11"))
        assertEquals("0 years, 5 months", yearsMonths("2020-02-19", "2020-08-18"))   // 5 months 30 days: counted down
        assertEquals("2 years, 0 months", yearsMonths("2022-01-03", "2024-01-19"))
    }

    /** The recoveries of 2008–2025 in the bundled history. */
    @Test fun historicRecoveries() {
        val o = JSONObject(TestData.golden())
        val d = o.getJSONArray("days")
        val data = ChartData(SignalEngine.run((0 until d.length()).map { i ->
            val a = d.getJSONArray(i)
            Day(a.getString(0), a.getDouble(1), a.getDouble(2), if (a.isNull(3)) Double.NaN else a.getDouble(3))
        }))
        fun at(date: String): Pair<String, String?> {
            val i = data.readings.indexOfFirst { it.date == date }
            val k = data.recovery[i]
            return data.readings[i].athDate to (if (k >= 0) data.readings[k].date else null)
        }
        assertEquals("2007-10-09" to "2013-03-28", at("2008-11-20"))
        assertEquals("2018-09-20" to "2019-04-23", at("2018-12-24"))
        assertEquals("2020-02-19" to "2020-08-18", at("2020-03-23"))
        assertEquals("2022-01-03" to "2024-01-19", at("2022-10-12"))
        assertEquals("2025-02-19" to "2025-06-27", at("2025-04-08"))
        assertEquals("2026-08-13" to null, at("2026-09-22"))
    }
}
