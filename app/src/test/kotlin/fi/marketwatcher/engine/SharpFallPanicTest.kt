package fi.marketwatcher.engine

import fi.marketwatcher.TestData
import fi.marketwatcher.work.NotificationRules
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sharp-fall Panic rule (0.24): −3.00% or worse turns Panic on until a day at −0.25% or better. */
class SharpFallPanicTest {

    private fun days(): List<Day> {
        val d = JSONObject(TestData.golden()).getJSONArray("days")
        return (0 until d.length()).map { i ->
            val a = d.getJSONArray(i)
            Day(date = a.getString(0), spx = a.getDouble(1), vix = a.getDouble(2),
                vix3m = if (a.isNull(3)) Double.NaN else a.getDouble(3),
                nfci = if (a.isNull(4)) null else a.getDouble(4),
                nfciPrev = if (a.isNull(5)) null else a.getDouble(5),
                nfci4w = if (a.length() > 6 && !a.isNull(6)) a.getDouble(6) else null,
                nfci8w = if (a.length() > 7 && !a.isNull(7)) a.getDouble(7) else null)
        }
    }

    @Test fun stepRule() {
        assertTrue(SignalEngine.fallPanicStep(false, -300))
        assertFalse(SignalEngine.fallPanicStep(false, -299))
        assertTrue("−1.00% keeps it on", SignalEngine.fallPanicStep(true, -100))
        assertTrue("−0.26% keeps it on", SignalEngine.fallPanicStep(true, -26))
        assertFalse("−0.25% turns it off", SignalEngine.fallPanicStep(true, -25))
        assertFalse(SignalEngine.fallPanicStep(true, 50))
        assertEquals(-436L, SignalEngine.dayChangeBp(7353.73, 7688.62))   // 05.11.2026
    }

    /** 2008–2026: Panic is shown by the rule on 8 days, the same as python/engine_v4.py (golden test). */
    @Test fun historyDays() {
        val out = SignalEngine.run(days())
        val shown = out.filter { it.sharpFallPanic }
        assertEquals(listOf("2016-06-24", "2016-06-27", "2018-10-10", "2018-10-11", "2020-02-24",
            "2020-09-03", "2020-09-04", "2020-09-08"), shown.map { it.date })
        shown.forEach {
            assertEquals(State.PANIC, it.state)
            assertTrue(it.date, it.baseState in SignalEngine.FALL_PANIC_OVER)
        }
        // The "why" dialog: the sharp-fall step decides exactly on these days.
        assertEquals(shown.map { it.date },
            out.filter { Explain.sharpFall(it).outcome == Explain.Outcome.DECIDED }.map { it.date })
        // Inside a panic cycle the engine's own state stays (e.g. 16.03.2020, −11.98%).
        val covid = out.single { it.date == "2020-03-16" }
        assertTrue(covid.fallPanicOn)
        assertFalse(covid.sharpFallPanic)
    }

    /** Brexit, 24.06.2016 (−3.59%): carried on from the reading of 23.06 with the next close only. */
    @Test fun carriedOnToNewerCloses() {
        val all = days()
        val before = SignalEngine.run(all.takeWhile { it.date <= "2016-06-23" }).last()
        val full = SignalEngine.run(all.takeWhile { it.date <= "2016-06-28" }).associateBy { it.date }
        assertEquals(State.CALM, before.state)

        val fred = SignalEngine.withPrices(before, listOf(full.getValue("2016-06-24").price()))
        assertEquals(State.PANIC, fred.state)
        assertEquals("2016-06-24", fred.stateAsOf)
        assertFalse(fred.stateEstimated)
        assertEquals("Suggested: Panic", fred.label)
        assertEquals(State.CALM, fred.baseState)
        assertEquals(before.date, fred.date)   // the rest of the reading stays the 23.06 one

        val est = SignalEngine.withPrices(before, listOf(full.getValue("2016-06-24").price().copy(estimated = true)))
        assertTrue(est.stateEstimated)
        assertEquals("Suggested: Panic (estimate)", est.label)

        // 27.06 −1.81% keeps it on; 28.06 +1.78% turns it off: back to the reading's own state.
        val three = listOf("2016-06-24", "2016-06-27", "2016-06-28").map { full.getValue(it).price() }
        assertEquals(State.PANIC, SignalEngine.withPrices(before, three.take(2)).state)
        val off = SignalEngine.withPrices(before, three)
        assertEquals(State.CALM, off.state)
        assertNull(off.stateAsOf)

        assertEquals("no newer closes", before, SignalEngine.withPrices(before, emptyList()))
    }

    @Test fun panicNotificationFromAnEstimate() {
        val all = days()
        val before = SignalEngine.run(all.takeWhile { it.date <= "2016-06-23" }).last()
        val day = SignalEngine.run(all.takeWhile { it.date <= "2016-06-24" }).last()
        val est = SignalEngine.withPrices(before, listOf(day.price().copy(estimated = true)))
        val now = NotificationRules.now(est, emptySet(), day.price().copy(estimated = true))
        val n = NotificationRules.evaluate(NotificationRules.Previous(State.CALM, emptySet(), false), now,
            notifyState = true, notifyBelowSma = false).single()
        assertEquals("Suggested: Panic (estimate)", n.title)
        assertTrue(n.text, n.text.startsWith("Estimated from VOO; FRED has not published the close yet. " +
            "Very sharp fall: S&P 500 −3.59% on 24.06.2016."))
        val words = (n.title + " " + n.text).lowercase().split(Regex("[^a-z]+"))
        assertFalse(n.text, words.any { it in setOf("buy", "sell", "buying", "selling") })
    }
}
