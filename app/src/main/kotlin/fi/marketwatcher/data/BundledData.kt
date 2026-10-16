package fi.marketwatcher.data

import android.content.Context

/**
 * Whether this build contains the bundled market data (2007–2026 history and Shiller CAPE). Both come
 * from the private MarketWatcher-data repository at build time (app/build.gradle.kts); a build made
 * without access to it still works, with FRED's ~10 years only.
 */
object BundledData {
    const val HISTORY = "replay_2007_2026.json"
    const val CAPE = "cape_tr_shiller.json"

    const val MISSING_TEXT = "This build has no bundled history (2007–2016) or CAPE data: the private " +
        "MarketWatcher-data repository was not available when it was built. Only FRED's last ~10 years are shown. " +
        "See the README, \"Repositories and data\"."

    @Volatile private var missing: Boolean? = null

    fun isMissing(ctx: Context): Boolean = missing ?: run {
        val names = runCatching { ctx.assets.list("")?.toSet() }.getOrNull().orEmpty()
        (HISTORY !in names || CAPE !in names).also { missing = it }
    }
}
