package fi.marketwatcher.work

import fi.marketwatcher.engine.State
import fi.marketwatcher.work.NotificationRules.Kind
import fi.marketwatcher.work.NotificationRules.Now
import fi.marketwatcher.work.NotificationRules.Previous
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {

    private fun now(state: State = State.CALM, spx: Double = 100.0, sma: Double? = 95.0, flags: Set<String> = emptySet()) =
        Now("2026-09-22", state, flags, emptyList(), spx, sma)

    private fun ids(prev: Previous, n: Now, state: Boolean = true, sma: Boolean = true) =
        NotificationRules.evaluate(prev, n, state, sma).map { it.id }

    @Test fun stateChangeNotifiesExceptNoPanicPullback() {
        assertEquals(listOf(NotificationRules.ID_STATE), ids(Previous(State.CALM, emptySet(), false), now(State.PANIC)))
        assertTrue(ids(Previous(State.CALM, emptySet(), false), now(State.PULLBACK)).isEmpty())
        assertTrue(ids(Previous(State.PULLBACK, emptySet(), false), now(State.CALM)).isEmpty())
        assertTrue("unknown previous state", ids(Previous(null, emptySet(), false), now(State.PANIC)).isEmpty())
    }

    @Test fun newEconomyFlagNotifiesOnce() {
        val n = now(flags = setOf("Recession"))
        assertEquals(listOf(NotificationRules.ID_ECONOMY), ids(Previous(State.CALM, emptySet(), false), n))
        assertTrue(ids(Previous(State.CALM, setOf("Recession"), false), n).isEmpty())
    }

    @Test fun belowAverageNotifiesOncePerCrossing() {
        val below = now(spx = 90.0, sma = 95.0)
        val above = now(spx = 100.0, sma = 95.0)
        assertEquals(listOf(NotificationRules.ID_BELOW_SMA), ids(Previous(State.CALM, emptySet(), false), below))
        assertEquals("first check ever, already below", listOf(NotificationRules.ID_BELOW_SMA),
            ids(Previous(State.CALM, emptySet(), null), below))
        assertTrue("still below: no repeat", ids(Previous(State.CALM, emptySet(), true), below).isEmpty())
        assertTrue(ids(Previous(State.CALM, emptySet(), false), above).isEmpty())
        assertTrue("no average yet", ids(Previous(State.CALM, emptySet(), false), now(spx = 90.0, sma = null)).isEmpty())
    }

    @Test fun switchesTurnRulesOff() {
        val prev = Previous(State.CALM, emptySet(), false)
        val n = now(State.PANIC, spx = 90.0, sma = 95.0, flags = setOf("Recession"))
        assertEquals(listOf(NotificationRules.ID_BELOW_SMA), ids(prev, n, state = false, sma = true))
        assertEquals(listOf(NotificationRules.ID_STATE, NotificationRules.ID_ECONOMY), ids(prev, n, state = true, sma = false))
    }

    @Test fun stateTestAlwaysSendsStateAndEconomyNotices() {
        for (s in State.entries) {
            val t = NotificationRules.test(Kind.STATE, now(s))
            assertEquals("state $s", listOf(101, 102), t.map { it.id })
            assertTrue(t.all { it.title.startsWith("Test: ") })
            assertEquals(s.label, t[0].title.removePrefix("Test: "))
        }
    }

    @Test fun belowAverageTestFiresAboveOrBelow() {
        val above = NotificationRules.test(Kind.BELOW_SMA, now(spx = 100.0, sma = 95.0))
        assertEquals(listOf(103), above.map { it.id })
        assertTrue(above[0].text.startsWith("Simulated close"))
        val below = NotificationRules.test(Kind.BELOW_SMA, now(spx = 90.0, sma = 95.0))
        assertEquals(listOf(103), below.map { it.id })
        assertTrue(!below[0].text.startsWith("Simulated"))
        assertTrue("no average: nothing to test", NotificationRules.test(Kind.BELOW_SMA, now(sma = null)).isEmpty())
    }

    @Test fun testsUseTheSameChannelAsRealNotices() {
        assertEquals(NotificationRules.CHANNEL_STATE, NotificationRules.channelOf(NotificationRules.ID_STATE))
        assertEquals(NotificationRules.CHANNEL_STATE, NotificationRules.channelOf(NotificationRules.ID_ECONOMY))
        assertEquals(NotificationRules.CHANNEL_BELOW_SMA, NotificationRules.channelOf(NotificationRules.ID_BELOW_SMA))
        for (kind in Kind.entries) {
            NotificationRules.test(kind, now(spx = 90.0, sma = 95.0)).forEach {
                assertEquals(NotificationRules.channelOf(kind), NotificationRules.channelOf(it.id))
            }
        }
    }

    private fun day(spx: Double, prev: Double?, date: String = "2026-10-14") =
        Now(date, State.CALM, emptySet(), emptyList(), spx, 95.0, prevClose = prev, prevDate = "2026-10-13")

    private fun sharp(prev: Previous, n: Now, on: Boolean = true) =
        NotificationRules.evaluate(prev, n, notifyState = false, notifyBelowSma = false, notifySharpFall = on)

    @Test fun sharpFallOncePerTradingDay() {
        val p = Previous(State.CALM, emptySet(), false)
        assertEquals(listOf(NotificationRules.ID_SHARP_FALL), sharp(p, day(98.0, 100.0)).map { it.id })   // −2.00%
        assertTrue("−1.99% is not a sharp fall", sharp(p, day(98.01, 100.0)).isEmpty())
        assertTrue("already seen", sharp(p.copy(sharpFallDate = "2026-10-14"), day(98.0, 100.0)).isEmpty())
        assertTrue("switch off", sharp(p, day(98.0, 100.0), on = false).isEmpty())
        assertTrue("no previous close", sharp(p, day(98.0, null)).isEmpty())
        val n = sharp(p, day(94.0, 100.0)).single()
        assertTrue(n.title, n.title.startsWith("Extreme fall: S&P 500 −6.00% on 14.10.2026"))
        assertEquals(NotificationRules.CHANNEL_SHARP_FALL, NotificationRules.channelOf(n.id))
    }

    @Test fun sharpFallTestIsSimulatedWhenNeeded() {
        val t = NotificationRules.test(Kind.SHARP_FALL, day(100.0, 99.0)).single()
        assertEquals(NotificationRules.ID_SHARP_FALL + NotificationRules.TEST_ID_OFFSET, t.id)
        assertTrue(t.text, t.text.startsWith("Simulated previous close"))
        assertTrue(t.title, t.title.startsWith("Test: Sharp fall: S&P 500 −2.50%"))
    }
}
