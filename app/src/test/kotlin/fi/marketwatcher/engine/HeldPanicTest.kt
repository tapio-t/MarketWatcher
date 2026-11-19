package fi.marketwatcher.engine

import fi.marketwatcher.TestData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 31.03–02.04.2026: Panic was shown without any panic reading, held from 26.–30.03 until fading was
 * confirmed (06.04). 0.25 explains it on the Suggested box and in the "why" dialog; the engine is unchanged.
 */
class HeldPanicTest {
    private val readings: Map<String, Reading> by lazy {
        val d = JSONObject(TestData.golden()).getJSONArray("days")
        SignalEngine.run((0 until d.length()).map { i ->
            val a = d.getJSONArray(i)
            Day(a.getString(0), a.getDouble(1), a.getDouble(2), if (a.isNull(3)) Double.NaN else a.getDouble(3))
        }).associateBy { it.date }
    }

    @Test fun panicWithAPanicReadingIsNotHeld() {
        val r = readings.getValue("2026-03-27")   // VIX/VIX3M 1.06
        assertEquals(State.PANIC, r.state)
        assertNull(Explain.heldPanic(r))
    }

    @Test fun heldPanicListsTheFadingChecks() {
        val r = readings.getValue("2026-03-31")
        assertEquals(State.PANIC, r.state)
        val h = assertNotNullHeld(r)
        assertEquals(4, h.checks.size)
        assertFalse("1 day without a new low", h.allMet)
        assertTrue(h.short, h.short.startsWith("No panic reading today · fading checks"))
    }

    /** 02.04.2026: all four fading checks met, but a calmer state needs a 2nd day in a row (03.04 was a holiday). */
    @Test fun allChecksMetWaitsForASecondDay() {
        val r = readings.getValue("2026-04-02")
        assertEquals(State.PANIC, r.state)
        assertEquals(State.FIRST_WAVE_FADING, r.rawState)
        val h = assertNotNullHeld(r)
        assertTrue(h.checks.toString(), h.allMet)
        assertEquals("No panic reading today · fading checks 4 of 4 met · needs a 2nd day", h.short)
        assertTrue(Explain.notes(r).any { it.startsWith("All met today") })
        assertEquals(State.FIRST_WAVE_FADING, readings.getValue("2026-04-06").state)
        assertNull(Explain.heldPanic(readings.getValue("2026-04-06")))
    }

    @Test fun noExplanationOutsidePanic() {
        assertNull(Explain.heldPanic(readings.getValue("2026-04-10")))
    }

    private fun assertNotNullHeld(r: Reading) = Explain.heldPanic(r).also { assertNotNull(r.date, it) }!!
}
