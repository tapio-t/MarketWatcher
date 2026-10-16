package fi.marketwatcher.data

/** Removes API keys from any text before it is shown on screen or stored. */
object Redact {
    private val QUERY = Regex("(api_key=)[^&\\s\"']+", RegexOption.IGNORE_CASE)
    private val BARE = Regex("\\b[a-z0-9]{32}\\b")   // the FRED key format

    fun text(s: String): String = BARE.replace(QUERY.replace(s, "$1***"), "***")
}
