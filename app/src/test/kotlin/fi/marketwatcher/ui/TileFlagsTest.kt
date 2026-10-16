package fi.marketwatcher.ui

import fi.marketwatcher.TestData
import fi.marketwatcher.engine.Day
import fi.marketwatcher.engine.SignalEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The VIX / VIX3M tile (Today and History use the same tiles) is amber exactly on backwardation days. */
class TileFlagsTest {
    @Test fun vixTileIsAmberExactlyAtOrAboveOne() {
        val o = JSONObject(TestData.golden())
        val d = o.getJSONArray("days")
        val days = (0 until d.length()).map { i ->
            val a = d.getJSONArray(i)
            Day(a.getString(0), a.getDouble(1), a.getDouble(2), if (a.isNull(3)) Double.NaN else a.getDouble(3),
                if (a.isNull(4)) null else a.getDouble(4), if (a.isNull(5)) null else a.getDouble(5))
        }
        var amber = 0
        for (r in SignalEngine.run(days)) {
            val tile = groupsFor(r).flatMap { it.tiles }.single { it.label == "VIX / VIX3M" }
            assertEquals(r.date, r.vixRatio >= 1.0, tile.flagged)
            if (tile.flagged) amber++
        }
        assertEquals(478, amber)
    }

    /** VIX tile: amber exactly on the VIX part of the Panic rule (VIX ≥ 30 and top 5% of its past year). */
    @Test fun vixTileIsAmberOnPanicLevelDays() {
        val o = JSONObject(TestData.golden())
        val d = o.getJSONArray("days")
        val days = (0 until d.length()).map { i ->
            val a = d.getJSONArray(i)
            Day(a.getString(0), a.getDouble(1), a.getDouble(2), if (a.isNull(3)) Double.NaN else a.getDouble(3))
        }
        val out = SignalEngine.run(days)
        var amber = 0
        for (r in out) {
            val tile = groupsFor(r).flatMap { it.tiles }.single { it.label == "VIX" }
            assertEquals(r.date, r.vix >= 30 && r.vixPct >= 0.95, tile.flagged)
            if (tile.flagged) amber++
        }
        assertEquals(178, amber)
        val byDate = out.associateBy { it.date }
        for (x in listOf("2008-10-10", "2018-12-24", "2020-03-16", "2022-03-07", "2025-04-08"))
            assertEquals(x, true, byDate.getValue(x).vixPanicLevel)
    }

    @Test fun economyHasTwoRowsInTheRequestedOrder() {
        val r = SignalEngine.run((0 until 300).map { Day("2020-01-%02d".format(1 + it % 28), 100.0 + it, 15.0, 17.0) }).last()
        val eco = groupsFor(r).single { it.title == "Economy" }.tiles.map { it.label }
        assertEquals(listOf("Sahm rule", "NFCI", "Buffett ratio", "Yield curve 10y–2y", "2-year yield", "CAPE (total return)"), eco)
    }

    @Test fun noVisibleCalm() {
        fi.marketwatcher.engine.State.entries.forEach { assertEquals(false, it.title.contains("Calm")) }
        assertEquals("Suggested: No panic", fi.marketwatcher.engine.State.CALM.label)
    }

    private fun goldenReadings(): List<fi.marketwatcher.engine.Reading> {
        val o = JSONObject(TestData.golden())
        val d = o.getJSONArray("days")
        return SignalEngine.run((0 until d.length()).map { i ->
            val a = d.getJSONArray(i)
            Day(a.getString(0), a.getDouble(1), a.getDouble(2), if (a.isNull(3)) Double.NaN else a.getDouble(3))
        })
    }

    /** "vs average": amber at 0.0% or below as shown (rounded to 0.1%); "Average is": amber when falling. */
    @Test fun trendTilesAmberRules() {
        var below = 0; var falling = 0
        for (r in goldenReadings()) {
            val tiles = groupsFor(r).flatMap { it.tiles }
            val vs = tiles.single { it.label == "vs average" }
            val avg = tiles.single { it.label == "Average is" }
            assertEquals(r.date, Math.round(r.distSma200!! * 1000) <= 0, vs.flagged)
            assertEquals(r.date, avg.value == "Falling", avg.flagged)
            if (vs.flagged) below++
            if (avg.flagged) falling++
        }
        assertEquals(1095, below)
        assertEquals(1061, falling)
    }

    @Test fun zeroShowsWithoutSign() {
        assertEquals("0.0%", signedPct(0.0004))
        assertEquals("0.0%", signedPct(-0.0004))
        assertEquals("+0.1%", signedPct(0.0006))
        assertEquals("−2.5%", signedPct(-0.025))
    }
}
