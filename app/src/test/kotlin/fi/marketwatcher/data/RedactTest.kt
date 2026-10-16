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
    }
}
