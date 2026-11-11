package fi.marketwatcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Reading Alpha Vantage replies, and the request limits. */
class AlphaVantageTest {
    // The shape of the docs' demo reply (TIME_SERIES_DAILY, 06.11.2026).
    private val daily = """
        {"Meta Data": {"1. Information": "Daily Prices (open, high, low, close) and Volumes", "2. Symbol": "VOO",
          "3. Last Refreshed": "2026-11-05", "4. Output Size": "Compact", "5. Time Zone": "US/Eastern"},
         "Time Series (Daily)": {
          "2026-11-05": {"1. open": "550.1000", "2. high": "552.0000", "3. low": "548.0000", "4. close": "549.8300", "5. volume": "5238949"},
          "2026-11-04": {"1. open": "553.0000", "2. high": "554.0000", "3. low": "543.0000", "4. close": "544.3900", "5. volume": "6100000"},
          "bad": {"4. close": "1"}, "2026-11-03": {"4. close": "None"}}}
    """.trimIndent()

    @Test fun readsDailyCloses() {
        assertEquals(mapOf("2026-11-04" to 544.39, "2026-11-05" to 549.83), AlphaVantageParse.dailyCloses(daily))
    }

    private fun failure(json: String): AlphaVantageException = try {
        AlphaVantageParse.dailyCloses(json); fail("no exception"); throw IllegalStateException()
    } catch (e: AlphaVantageException) { e }

    @Test fun invalidKeyIsRejected() {
        val e = failure("""{"Error Message": "the parameter apikey is invalid or missing. Please claim your free API key."}""")
        assertTrue(e.keyRejected)
        assertTrue(e.message!!.startsWith("Alpha Vantage: the parameter apikey is invalid"))
    }

    @Test fun dailyLimitIsNotAKeyProblem() {
        val e = failure("""{"Information": "We have detected your API key as ABCD1234EFGH5678 and our standard API rate limit is 25 requests per day."}""")
        assertFalse(e.keyRejected)
        assertFalse("key removed", e.message!!.contains("ABCD1234EFGH5678"))
        assertTrue(e.message!!.contains("25 requests per day"))
    }

    @Test fun unreadableOrEmptyReplies() {
        assertEquals("Alpha Vantage sent an unreadable reply.", failure("<html>").message)
        assertEquals("Alpha Vantage sent no data.", failure("{}").message)
    }

    @Test fun readsDividends() {
        val json = """{"symbol": "VOO", "data": [
            {"ex_dividend_date": "2026-09-26", "declaration_date": "2026-09-24", "amount": "1.7400"},
            {"ex_dividend_date": "2026-06-27", "amount": "None"},
            {"ex_dividend_date": "None", "amount": "1.6"}]}"""
        val d = AlphaVantageParse.dividends(json)
        assertEquals(setOf("2026-09-26", "2026-06-27"), d.keys)
        assertEquals(1.74, d.getValue("2026-09-26"), 0.0)
        assertTrue(d.getValue("2026-06-27").isNaN())
        assertTrue("no dividends listed", AlphaVantageParse.dividends("""{"symbol": "VOO"}""").isEmpty())
    }

    @Test fun requestLimits() {
        val h = 3_600_000L
        assertTrue(AvBudget.allowed(0, null, 0, 10 * h, "2026-11-06"))
        assertFalse("within the hour", AvBudget.allowed(10 * h, "2026-11-06", 1, 10 * h + h - 1, "2026-11-06"))
        assertTrue(AvBudget.allowed(10 * h, "2026-11-06", 11, 11 * h, "2026-11-06"))
        assertFalse("12 a day", AvBudget.allowed(10 * h, "2026-11-06", 12, 11 * h, "2026-11-06"))
        assertTrue("new day", AvBudget.allowed(10 * h, "2026-11-05", 12, 11 * h, "2026-11-06"))
    }

    @Test fun expectedTradingDay() {
        fun ny(s: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(s), ZoneId.of("America/New_York"))
        assertEquals("2026-11-05", AvBudget.expectedDate(ny("2026-11-06T16:29")))   // Friday before 16:30
        assertEquals("2026-11-06", AvBudget.expectedDate(ny("2026-11-06T16:30")))
        // 05.11.2026: 23:30 Helsinki is 16:30 New York, so the evening estimate of the fall is looked for.
        assertEquals("2026-11-05", AvBudget.expectedDate(ZonedDateTime.of(2026, 11, 5, 23, 30, 0, 0, ZoneId.of("Europe/Helsinki"))))
        assertEquals("2026-11-06", AvBudget.expectedDate(ny("2026-11-08T12:00")))   // Sunday
        assertEquals("2026-11-06", AvBudget.expectedDate(ny("2026-11-09T09:00")))   // Monday morning
        // Helsinki 03:30 on Saturday is Friday 20:30 in New York.
        assertEquals("2026-11-06", AvBudget.expectedDate(ZonedDateTime.of(2026, 11, 7, 3, 30, 0, 0, ZoneId.of("Europe/Helsinki"))))
    }
}
