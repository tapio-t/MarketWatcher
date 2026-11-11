package fi.marketwatcher.work

import fi.marketwatcher.TestData
import fi.marketwatcher.engine.Context
import fi.marketwatcher.engine.State
import fi.marketwatcher.work.NotificationRules.Inversion
import fi.marketwatcher.work.NotificationRules.Kind
import fi.marketwatcher.work.NotificationRules.Now
import fi.marketwatcher.work.NotificationRules.Previous
import org.junit.Assert.assertEquals
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationRulesTest {

    private fun now(state: State = State.CALM, spx: Double = 100.0, sma: Double? = 95.0, flags: Set<String> = emptySet()) =
        Now("2026-09-22", state, flags, flags.associateWith { "$it note." }, spx, sma)

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

    @Test fun economyNoticeShowsOnlyTheNewFlagsNotes() {
        // Rate shock turns on while High valuation was already on (20.10.2026).
        val n = now(flags = setOf("Rate shock", "High valuation"))
        val notice = NotificationRules.evaluate(Previous(State.CALM, setOf("High valuation"), false), n,
            notifyState = true, notifyBelowSma = false).single()
        assertEquals("Economy: Rate shock", notice.title)
        assertEquals("Rate shock note.", notice.text)
    }

    @Test fun flagNotesPairEachFlagWithItsNote() {
        val c = Context(creditStress = false, recession = null, rateShock = true, valuationHigh = true,
            notes = listOf("Rate shock: 2-year yield …", "Valuation is high: …"))
        assertEquals(listOf("Rate shock", "High valuation"), NotificationRules.flagsOn(c))
        assertEquals(mapOf("Rate shock" to "Rate shock: 2-year yield …", "High valuation" to "Valuation is high: …"),
            NotificationRules.flagNotes(c))
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
        Now(date, State.CALM, emptySet(), emptyMap(), spx, 95.0, prevClose = prev, prevDate = "2026-10-13")

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

    @Test fun estimatedCloseIsMarkedInTheTitles() {
        val p = Previous(State.CALM, emptySet(), false)
        val est = day(97.0, 100.0).copy(estimated = true, exDividend = true)
        val fall = sharp(p, est).single()
        assertEquals("Very sharp fall (estimate): S&P 500 −3.00% on 14.10.2026", fall.title)
        assertTrue(fall.text, fall.text.startsWith("Estimated from VOO; FRED has not published the close yet. Ex-dividend day. "))
        val below = NotificationRules.evaluate(p, est.copy(spx = 90.0), notifyState = false, notifyBelowSma = true).single()
        assertEquals("S&P 500 is below its 200-day average (estimate)", below.title)
        assertEquals("FRED's close", "S&P 500 is below its 200-day average",
            NotificationRules.evaluate(p, day(90.0, 100.0), notifyState = false, notifyBelowSma = true).single().title)
    }

    /** An estimate notified the fall; FRED's close for the same day later is not notified again. */
    @Test fun fredCloseAfterAnEstimateIsNotNotifiedAgain() {
        val afterEstimate = Previous(State.CALM, emptySet(), true, sharpFallDate = "2026-10-14")
        assertTrue(sharp(afterEstimate, day(97.9, 100.0)).isEmpty())
        assertTrue(NotificationRules.evaluate(afterEstimate, day(90.0, 100.0), notifyState = false, notifyBelowSma = true).isEmpty())
    }

    // ---- volatility curve inverted (0.23)

    private fun pt(date: String, ratio: Double) = Inversion(date, ratio, null, null)

    @Test fun inversionNeedsRearmAtOrBelowPointNine() {
        val days = listOf(pt("d1", 0.85), pt("d2", 1.10), pt("d3", 0.95), pt("d4", 1.04), pt("d5", 0.90), pt("d6", 0.995))
        // d4 is not new (never back to 0.90 after d2); d6 shows as 1.00 after d5 re-armed.
        assertEquals(listOf("d2", "d6"), NotificationRules.inversionsOf(days).map { it.date })
        assertEquals("0.995 is shown as 1.00", "d6", NotificationRules.lastInversionOf(days)?.date)
        assertEquals("0.9949 is shown as 0.99", emptyList<Inversion>(), NotificationRules.inversionsOf(listOf(pt("d1", 0.9949))))
        assertEquals("NaN (no VIX3M) is skipped", emptyList<Inversion>(), NotificationRules.inversionsOf(listOf(pt("d1", Double.NaN))))
    }

    private fun curve(prev: Previous, inv: Inversion?, on: Boolean = true) =
        NotificationRules.evaluate(prev, now().copy(inversion = inv), notifyState = false, notifyBelowSma = false, notifyCurve = on)

    @Test fun inversionNotifiesOnceAndNotOnTheFirstCheck() {
        // 05.11.2026: VIX 28.18, VIX3M 25.55.
        val inv = Inversion("2026-11-05", 28.18 / 25.55, 28.18, 25.55)
        val n = curve(Previous(State.CALM, emptySet(), false, inversionDate = "2026-04-07"), inv).single()
        assertEquals(NotificationRules.ID_CURVE, n.id)
        assertEquals("Volatility curve inverted: VIX/VIX3M 1.10 on 05.11.2026", n.title)
        assertTrue(n.text, n.text.startsWith("VIX 28.18 is above VIX3M 25.55"))
        assertEquals(NotificationRules.CHANNEL_CURVE, NotificationRules.channelOf(n.id))
        assertTrue("none seen before", curve(Previous(State.CALM, emptySet(), false, inversionDate = ""), inv).size == 1)
        assertTrue("already seen", curve(Previous(State.CALM, emptySet(), false, inversionDate = "2026-11-05"), inv).isEmpty())
        assertTrue("first check after installing", curve(Previous(State.CALM, emptySet(), false, inversionDate = null), inv).isEmpty())
        assertTrue("switch off", curve(Previous(State.CALM, emptySet(), false, inversionDate = ""), inv, on = false).isEmpty())
        assertTrue("no inversion", curve(Previous(State.CALM, emptySet(), false, inversionDate = ""), null).isEmpty())
    }

    @Test fun inversionTestIsSimulatedWhenNeeded() {
        val t = NotificationRules.test(Kind.CURVE, now()).single()
        assertEquals(NotificationRules.ID_CURVE + NotificationRules.TEST_ID_OFFSET, t.id)
        assertTrue(t.title, t.title.startsWith("Test: Volatility curve inverted: VIX/VIX3M 1.05"))
        assertTrue(t.text, t.text.startsWith("Simulated inversion"))
    }

    @Test fun noBuyOrSellWordsInTheInversionNotice() {
        val n = curve(Previous(State.CALM, emptySet(), false, inversionDate = ""), Inversion("2026-11-05", 1.10, 28.18, 25.55)).single()
        val words = (n.title + " " + n.text).lowercase().split(Regex("[^a-z]+"))
        assertFalse(n.text, words.any { it in setOf("buy", "sell", "buying", "selling") })
    }

    /** 2008–2026 (the golden inputs): 57 notices, about 3 a year, as in the 0.23 proposal. */
    @Test fun inversionsInHistory() {
        val d = JSONObject(TestData.golden()).getJSONArray("days")
        val days = (0 until d.length()).map { d.getJSONArray(it) }.filter { !it.isNull(3) && it.getString(0) >= "2008-01-01" }
            .map { Inversion(it.getString(0), it.getDouble(2) / it.getDouble(3), it.getDouble(2), it.getDouble(3)) }
        val inv = NotificationRules.inversionsOf(days)
        assertEquals(57, inv.size)
        assertEquals("2008-01-04", inv.first().date)
        assertEquals("2026-03-03", inv.last().date)
    }
}
