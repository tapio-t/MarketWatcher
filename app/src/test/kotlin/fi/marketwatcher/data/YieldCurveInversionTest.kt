package fi.marketwatcher.data

import fi.marketwatcher.engine.Day
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Last inverted" / "inverted since" use only data up to the day shown. */
class YieldCurveInversionTest {
    private val curve = mapOf(
        "2022-07-05" to 0.05, "2022-07-06" to -0.04, "2022-07-07" to -0.10,
        "2022-12-30" to 0.30, "2024-09-04" to -0.01, "2024-09-05" to 0.02, "2024-09-06" to 0.05,
    )
    private val lk = DataAssembler.Lookups(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), curve)
    private fun on(date: String) = lk.fill(Day(date, 100.0, 15.0, 17.0))

    @Test fun neverInvertedYet() {
        val d = on("2022-07-05")
        assertNull(d.t10y2yInvertedSince); assertNull(d.t10y2yLastInverted)
    }

    @Test fun invertedSinceStartOfRun() {
        assertEquals("2022-07-06", on("2022-07-07").t10y2yInvertedSince)
        assertEquals("2024-09-04", on("2024-09-04").t10y2yInvertedSince)
    }

    @Test fun lastInvertedAfterRunEnds() {
        val d = on("2024-09-06")
        assertNull(d.t10y2yInvertedSince)
        assertEquals("2024-09-04", d.t10y2yLastInverted)
    }

    @Test fun historyDoesNotLookAhead() {
        // In 2023 (between the two runs) the last inversion known is 07.07.2022, not 2024.
        assertEquals("2022-07-07", on("2023-01-10").t10y2yLastInverted)
    }
}
