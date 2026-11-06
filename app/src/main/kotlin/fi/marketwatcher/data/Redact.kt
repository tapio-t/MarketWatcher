package fi.marketwatcher.data

/** Removes API keys from any text before it is shown on screen or stored. */
object Redact {
    private val QUERY = Regex("(api_?key=)[^&\\s\"']+", RegexOption.IGNORE_CASE)   // FRED api_key=, Alpha Vantage apikey=
    private val BARE = Regex("\\b[a-z0-9]{32}\\b")       // the FRED key format
    private val BARE_AV = Regex("\\b(?=[A-Z0-9]*[0-9])(?=[A-Z0-9]*[A-Z])[A-Z0-9]{16}\\b")   // Alpha Vantage keys: 16 capitals and digits

    fun text(s: String): String = BARE_AV.replace(BARE.replace(QUERY.replace(s, "$1***"), "***"), "***")
}
