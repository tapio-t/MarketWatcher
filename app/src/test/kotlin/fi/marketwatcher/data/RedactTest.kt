package fi.marketwatcher.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RedactTest {
    private val key = "4f8c2e91b7d34a06c5e1f92a7b3d1a2b"

    @Test fun removesKeyFromUrls() {
        val s = Redact.text("FileNotFoundException: https://api.stlouisfed.org/fred/series/observations?series_id=SP500&api_key=$key&file_type=json")
        assertFalse(s.contains(key))
        assertEquals(true, s.contains("api_key=***&file_type=json"))
    }

    @Test fun removesBareKeys() {
        assertEquals("key *** was rejected", Redact.text("key $key was rejected"))
    }

    @Test fun leavesNormalMessagesAlone() {
        val m = "FRED SP500 returned HTTP 500."
        assertEquals(m, Redact.text(m))
        val av = "Alpha Vantage: VOO closes to 05.11.2026. TIME_SERIES_DAILY ABCDEFGHIJKLMNOP 1234567890123456"
        assertEquals("16 letters only or 16 digits only are not keys", av, Redact.text(av))
    }

    @Test fun removesAlphaVantageKeys() {
        val avKey = "X7KQ2M9B4TZ1LW8P"
        val url = Redact.text("https://www.alphavantage.co/query?function=TIME_SERIES_DAILY&symbol=VOO&apikey=demo123&outputsize=compact")
        assertEquals(true, url.contains("apikey=***&outputsize=compact"))
        assertFalse(url.contains("demo123"))
        assertEquals("Your API key *** is over the limit", Redact.text("Your API key $avKey is over the limit"))
    }
}
