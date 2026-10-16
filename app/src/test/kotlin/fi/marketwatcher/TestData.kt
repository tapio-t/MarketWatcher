package fi.marketwatcher

import org.junit.Assume.assumeTrue

/**
 * Data files for the unit tests, from the private MarketWatcher-data repository (downloaded by the build;
 * see app/build.gradle.kts). Without it, tests that need them are skipped, not failed; the GitHub build
 * requires the data, so there they always run.
 */
object TestData {
    fun golden(): String = text("golden_v4.json")
    fun cape(): String = text("cape_tr_shiller.json")
    fun history(): String = text("replay_2007_2026.json")

    private fun text(name: String): String {
        val r = TestData::class.java.classLoader!!.getResource(name)
        assumeTrue("$name not found: built without MarketWatcher-data (see README, Repositories and data)", r != null)
        return r!!.readText()
    }
}
