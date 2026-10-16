package fi.marketwatcher

import org.junit.Assume.assumeTrue

/**
 * Data files for the unit tests, from the private MarketWatcher-data repository (cloned next to this
 * project; see app/build.gradle.kts). Without it, tests that need them are skipped, not failed.
 */
object TestData {
    fun golden(): String = text("golden_v4.json")
    fun cape(): String = text("cape_tr_shiller.json")

    private fun text(name: String): String {
        val r = TestData::class.java.classLoader!!.getResource(name)
        assumeTrue("$name not found: clone MarketWatcher-data next to this project", r != null)
        return r!!.readText()
    }
}
