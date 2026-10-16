package fi.marketwatcher.engine

import fi.marketwatcher.TestData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden test: runs the Kotlin engine on the same 2007–2026 inputs as python/engine_v4.py
 * and requires the same state, raw state, uptrend counter and credit flag on every day.
 * Regenerate with:  python python/backtest_v4.py --golden   (writes MarketWatcher-data/test-resources/golden_v4.json)
 */
class SignalEngineGoldenTest {

    private fun load(): Pair<List<Day>, List<List<Any?>>> {
        val text = TestData.golden()
        val o = JSONObject(text)
        val d = o.getJSONArray("days")
        val days = (0 until d.length()).map { i ->
            val a = d.getJSONArray(i)
            Day(
                date = a.getString(0), spx = a.getDouble(1), vix = a.getDouble(2),
                vix3m = if (a.isNull(3)) Double.NaN else a.getDouble(3),
                nfci = if (a.isNull(4)) null else a.getDouble(4),
                nfciPrev = if (a.isNull(5)) null else a.getDouble(5),
                nfci4w = if (a.length() > 6 && !a.isNull(6)) a.getDouble(6) else null,
                nfci8w = if (a.length() > 7 && !a.isNull(7)) a.getDouble(7) else null,
            )
        }
        val e = o.getJSONArray("expected")
        val expected = (0 until e.length()).map { i ->
            val a = e.getJSONArray(i)
            listOf(a.getString(0), a.getString(1), a.getString(2), a.getInt(3),
                if (a.isNull(4)) null else a.getBoolean(4))
        }
        return days to expected
    }

    @Test fun matchesPythonEveryDay() {
        val (days, expected) = load()
        val out = SignalEngine.run(days)
        assertEquals("number of readings", expected.size, out.size)
        for (i in out.indices) {
            val r = out[i]; val x = expected[i]
            val msg = "day ${r.date}"
            assertEquals(msg, x[0], r.date)
            assertEquals(msg, x[1], r.state.name)
            assertEquals(msg, x[2], r.rawState.name)
            assertEquals(msg, x[3], r.uptrendRun)
            assertEquals(msg, x[4], r.context.creditStress)
        }
    }

    /** The "why" dialog must name exactly one deciding rule, and it must give the engine's raw state. */
    @Test fun explanationMatchesTheEngineEveryDay() {
        val (days, _) = load()
        for (r in SignalEngine.run(days)) {
            val decided = Explain.steps(r).filter { it.outcome == Explain.Outcome.DECIDED }
            assertEquals("day ${r.date}", 1, decided.size)
            assertEquals("day ${r.date}", r.rawState, decided[0].state)
        }
    }

    /** Backwardation (VIX/VIX3M ≥ 1.00) drives the amber VIX / VIX3M tile on Today and History. */
    @Test fun backwardationDays() {
        val out = SignalEngine.run(load().first)
        assertEquals(478, out.count { it.backwardation })
        val byDate = out.associateBy { it.date }
        for (d in listOf("2008-10-10", "2018-12-24", "2020-03-16", "2020-03-23", "2022-03-07", "2025-04-08"))
            assertTrue(d, byDate.getValue(d).backwardation)
        assertTrue(!byDate.getValue("2026-09-22").backwardation)
    }

    @Test fun everyLabelStartsWithSuggested() {
        State.values().forEach { assertTrue(it.label.startsWith("Suggested: ")) }
    }

    @Test fun noStateTextTellsTheUserToBuyOrSell() {
        State.values().forEach {
            val t = (it.title + " " + it.message).lowercase()
            assertTrue("$it mentions buying", !Regex("\\bbuy").containsMatchIn(t))
            assertTrue("$it mentions selling advice", !t.contains("sell now") && !t.contains("you should"))
        }
    }
}
