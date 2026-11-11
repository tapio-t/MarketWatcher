package fi.marketwatcher.data

import android.content.Context
import fi.marketwatcher.engine.Day
import fi.marketwatcher.engine.PriceDays
import fi.marketwatcher.engine.PriceView
import fi.marketwatcher.engine.Reading
import fi.marketwatcher.engine.SignalEngine
import fi.marketwatcher.engine.State
import fi.marketwatcher.engine.VooEstimate
import fi.marketwatcher.engine.price
import org.json.JSONArray
import java.time.Instant
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Personal settings. The FRED API key is kept encrypted by [ApiKeyStore]; everything else here is
 * harmless. Versions up to 0.10 stored the key in plain text in this file: [migrateKey] moves it
 * to encrypted storage automatically. It runs off the main thread at the start of every data load
 * (app start and background check, whichever comes first), and deletes the plain-text entry only
 * after the encrypted copy has been read back successfully. Until then the plain-text key keeps
 * working, so nothing is ever lost.
 */
class Settings(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val store = ApiKeyStore(app)
    private val avStore = ApiKeyStore(app, ApiKeyStore.Slot.ALPHA_VANTAGE)

    /** Safe to call any number of times; blocking (Keystore), so call off the main thread. */
    fun migrateKey(): Unit = synchronized(LOCK) {
        if (!prefs.contains(LEGACY_KEY)) return
        val plain = prefs.getString(LEGACY_KEY, "").orEmpty().trim()
        if (plain.isEmpty()) { prefs.edit().remove(LEGACY_KEY).commit(); return }
        try {
            store.save(plain)
            val back = store.load()
            if (back is ApiKeyStore.Loaded.Key && back.value == plain) {
                // commit(): the deletion is on disk before we report it done.
                prefs.edit().remove(LEGACY_KEY)
                    .putLong(MIGRATED_AT, System.currentTimeMillis())
                    .commit()
            }
            // Otherwise keep the plain-text key and try again next time; it is never lost.
        } catch (e: Exception) {
            // Keystore problem: keep the plain-text key, retry on the next start.
        }
    }

    /** True when a key is saved (cheap; no decryption). */
    val hasApiKey: Boolean get() = store.has() || prefs.contains(LEGACY_KEY)

    /** The key for FRED requests: decrypted on demand, never kept on screen. "" when none. */
    val apiKey: String
        get() = when (val k = store.load()) {
            is ApiKeyStore.Loaded.Key -> k.value
            ApiKeyStore.Loaded.Unreadable -> { prefs.edit().putBoolean(KEY_UNREADABLE, true).apply(); "" }
            ApiKeyStore.Loaded.TryLater -> throw FredException("Couldn't read the saved API key right now. Try again.")
            ApiKeyStore.Loaded.None -> prefs.getString(LEGACY_KEY, "").orEmpty()   // only while a migration is pending
        }

    /** Last 4 characters of the saved key, for "•••• 1a2b"; null when none or unreadable. */
    fun apiKeyTail(): String? = runCatching { apiKey }.getOrNull()?.takeIf { it.length >= 4 }?.takeLast(4)

    fun saveApiKey(key: String): Unit = synchronized(LOCK) {
        store.save(key)
        prefs.edit().remove(LEGACY_KEY).remove(KEY_UNREADABLE).commit()
    }

    fun removeApiKey(): Unit = synchronized(LOCK) {
        store.clear()
        prefs.edit().remove(LEGACY_KEY).commit()
    }

    /** True when an Alpha Vantage key is saved (optional: VOO estimates while FRED's close is missing). */
    val hasAvKey: Boolean get() = avStore.has()

    /** The Alpha Vantage key, decrypted on demand. "" when none. */
    val avApiKey: String
        get() = when (val k = avStore.load()) {
            is ApiKeyStore.Loaded.Key -> k.value
            ApiKeyStore.Loaded.Unreadable -> { prefs.edit().putBoolean(AV_UNREADABLE, true).apply(); "" }
            ApiKeyStore.Loaded.TryLater -> throw AlphaVantageException("Couldn't read the saved Alpha Vantage key right now. Try again.")
            ApiKeyStore.Loaded.None -> ""
        }

    fun avApiKeyTail(): String? = runCatching { avApiKey }.getOrNull()?.takeIf { it.length >= 4 }?.takeLast(4)

    fun saveAvApiKey(key: String): Unit = synchronized(LOCK) {
        avStore.save(key)
        prefs.edit().remove(AV_UNREADABLE).commit()
    }

    /** Removes only the key: saved VOO prices stay and still give estimates. */
    fun removeAvApiKey(): Unit = synchronized(LOCK) { avStore.clear() }

    var avKeyWasUnreadable: Boolean
        get() = prefs.getBoolean(AV_UNREADABLE, false)
        set(v) { prefs.edit().putBoolean(AV_UNREADABLE, v).apply() }

    /** VOO downloads for [AvBudget]: the last one (epoch ms), its New York day and how many that day. */
    val avLastRequestAt: Long get() = prefs.getLong("av_last_request_at", 0L)
    val avDay: String? get() = prefs.getString("av_day", null)
    val avDayCount: Int get() = prefs.getInt("av_day_count", 0)
    fun recordAvRequest(nowMs: Long, day: String) {
        val count = if (avDay == day) avDayCount + 1 else 1
        prefs.edit().putLong("av_last_request_at", nowMs).putString("av_day", day).putInt("av_day_count", count).commit()
    }

    /** The last VOO download (epoch ms) and what it got, for the Alpha Vantage card in Settings. */
    val avLastResultAt: Long get() = prefs.getLong("av_last_result_at", 0L)
    val avLastResult: String? get() = prefs.getString("av_last_result", null)
    fun recordAvResult(text: String) = prefs.edit()
        .putLong("av_last_result_at", System.currentTimeMillis()).putString("av_last_result", Redact.text(text)).apply()

    /** Set when a saved key could not be decrypted and was removed (shown once in Settings). */
    var keyWasUnreadable: Boolean
        get() = prefs.getBoolean(KEY_UNREADABLE, false)
        set(v) { prefs.edit().putBoolean(KEY_UNREADABLE, v).apply() }

    /** When the plain-text key was moved to encrypted storage (epoch ms), or null if it never had to be. */
    val keyMigratedAt: Long? get() = prefs.getLong(MIGRATED_AT, 0L).takeIf { it > 0 }

    /** Notify when the suggested state changes or an economy flag turns on. */
    var notifications: Boolean
        get() = prefs.getBoolean("notifications", true)
        set(v) = prefs.edit().putBoolean("notifications", v).apply()
    /** Notify when the S&P 500 closes below its 200-day average (once per crossing). */
    var notifyBelowSma: Boolean
        get() = prefs.getBoolean("notify_below_sma", true)
        set(v) = prefs.edit().putBoolean("notify_below_sma", v).apply()

    /** Notify when the S&P 500 falls 2.00% or more from the previous close (once per trading day). */
    var notifySharpFall: Boolean
        get() = prefs.getBoolean("notify_sharp_fall", true)
        set(v) = prefs.edit().putBoolean("notify_sharp_fall", v).apply()

    /** Notify when VIX/VIX3M reaches 1.00 (again after being at 0.90 or below). */
    var notifyCurve: Boolean
        get() = prefs.getBoolean("notify_curve", true)
        set(v) = prefs.edit().putBoolean("notify_curve", v).apply()

    /** When FRED data was last downloaded successfully (epoch ms; 0 = never). */
    var lastDownloadAt: Long
        get() = prefs.getLong("last_download_at", 0L)
        set(v) { prefs.edit().putLong("last_download_at", v).apply() }

    /** Whether opening the app should download: only if the last download is older than [minutes]. */
    fun downloadDue(minutes: Long = 30): Boolean =
        System.currentTimeMillis() - lastDownloadAt >= minutes * 60_000

    /** Shiller CAPE update: last check (epoch ms) and its result text. */
    var capeCheckedAt: Long
        get() = prefs.getLong("cape_checked_at", 0L)
        set(v) { prefs.edit().putLong("cape_checked_at", v).apply() }
    var capeCheckResult: String?
        get() = prefs.getString("cape_check_result", null)
        set(v) { prefs.edit().putString("cape_check_result", v).apply() }
    /** The ie_data.xls link last downloaded, to skip unchanged files. */
    var capeLastLink: String?
        get() = prefs.getString("cape_last_link", null)
        set(v) { prefs.edit().putString("cape_last_link", v).apply() }

    /** When a background check last ran (ISO instant) and what it did. */
    val lastCheckAt: String? get() = prefs.getString("last_check_at", null)
    val lastCheckResult: String? get() = prefs.getString("last_check_result", null)

    /** One entry of the background check log: when (ISO instant) and what happened. */
    data class CheckEntry(val at: String, val text: String)

    /** The last [CHECK_LOG_SIZE] background check results, newest first. */
    fun checkLog(): List<CheckEntry> = runCatching {
        val a = JSONArray(prefs.getString(CHECK_LOG, "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> CheckEntry(o.getString("at"), o.getString("text")) } }
    }.getOrDefault(emptyList())

    fun recordCheck(at: java.time.Instant, result: String): Unit = synchronized(LOG_LOCK) {
        val text = Redact.text(result)
        val log = (listOf(CheckEntry(at.toString(), text)) + checkLog()).take(CHECK_LOG_SIZE)
        val a = JSONArray()
        log.forEach { a.put(org.json.JSONObject().put("at", it.at).put("text", it.text)) }
        prefs.edit().putString("last_check_at", at.toString()).putString("last_check_result", text)
            .putString(CHECK_LOG, a.toString()).apply()
    }

    companion object {
        const val FILE = "marketwatcher_settings"
        const val LEGACY_KEY = "fred_api_key"          // plain text, versions up to 0.10
        private const val MIGRATED_AT = "key_migrated_at"
        private const val KEY_UNREADABLE = "key_unreadable"
        private const val AV_UNREADABLE = "av_key_unreadable"
        private const val CHECK_LOG = "check_log"
        const val CHECK_LOG_SIZE = 30
        private val LOG_LOCK = Any()
        private val LOCK = ApiKeyStore.LOCK   // shared with the store: migration can't overwrite a newly saved key
    }
}

/**
 * History bundled with the app: S&P 500, VIX, VIX3M and NFCI from 2007-01-03 to 2026-09-22
 * (FRED keeps only ~10 years of S&P 500 data, so the 2008–2016 part cannot be downloaded).
 * Rows: [date, spx, vix, vix3m|null, nfci|null, nfciPrev|null, nfciDate|null, nfci4w|null, nfci8w|null]
 * (older 8-column files load too; the 8-week window then stays inactive).
 * Sahm rule, 2-year yield and Buffett ratio are added from the FRED cache (see [DataAssembler.Lookups]).
 */
object BundledHistory {
    @Volatile private var cache: List<Day>? = null

    fun load(context: Context): List<Day> = cache ?: synchronized(this) {
        // Missing when built without the private MarketWatcher-data repository: then only FRED's ~10 years.
        cache ?: (runCatching { context.assets.open("replay_2007_2026.json").bufferedReader().use { it.readText() } }
            .getOrNull()?.let { parse(it) } ?: emptyList())
            .also { cache = it }
    }

    fun parse(json: String): List<Day> {
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            val r = a.getJSONArray(i)
            Day(
                date = r.getString(0), spx = r.getDouble(1), vix = r.getDouble(2),
                vix3m = if (r.isNull(3)) Double.NaN else r.getDouble(3),
                nfci = if (r.isNull(4)) null else r.getDouble(4),
                nfciPrev = if (r.isNull(5)) null else r.getDouble(5),
                nfciDate = if (r.length() > 6 && !r.isNull(6)) r.getString(6) else null,
                nfci4w = if (r.length() > 7 && !r.isNull(7)) r.getDouble(7) else null,
                nfci8w = if (r.length() > 8 && !r.isNull(8)) r.getDouble(8) else null,
            ).let { d ->
                // NFCI is weekly (Fridays): the earlier values are dated 28 and 56 days before.
                fun before(days: Long) = d.nfciDate?.let { LocalDate.parse(it).minusDays(days).toString() }
                d.copy(nfci4wDate = if (d.nfci4w != null) before(28) else null,
                       nfci8wDate = if (d.nfci8w != null) before(56) else null)
            }
        }
    }

    /** Bundled days up to their last date, then live FRED days after it. */
    fun merge(bundled: List<Day>, live: List<Day>): List<Day> {
        val last = bundled.lastOrNull()?.date ?: return live
        return bundled + live.filter { it.date > last }
    }
}

/** Fetches (or reads cached) FRED data, runs the engine, and remembers what was shown last. */
class SignalRepository(private val context: Context) {
    private val cache = SeriesCache(context)
    private val voo = VooCache(context)
    private val settings = Settings(context)
    private val memory = context.getSharedPreferences("marketwatcher_state", Context.MODE_PRIVATE)

    data class SeriesStatus(val series: Series, val lastDate: String?, val fetchedAt: Instant?)

    data class Result(
        /** Latest reading; null until live FRED data has been fetched at least once. */
        val today: Reading?,
        /** Every reading from 2008 to the latest day, for the History replay. */
        val history: List<Reading>,
        val series: List<SeriesStatus>,
        val previousState: State?,
        val previousFlags: Set<String>,
        /** Whether the last refresh saw the close below its 200-day average (null = not known yet). */
        val previousBelowSma: Boolean?,
        /**
         * S&P 500 days newer than [today]: FRED has the close, but not yet VIX/VIX3M (they come
         * ~13 hours later). Empty when [today] is the newest close.
         */
        val prices: List<PriceView> = emptyList(),
        /** The last trading day for which a sharp fall was seen (so it is notified only once). */
        val previousSharpFallDate: String? = null,
        /** The newest S&P 500 close from FRED (not an estimate). */
        val fredPriceDate: String? = null,
        /** The last volatility-curve inversion already seen ("" = none; null = not known yet). */
        val previousInversionDate: String? = null,
    ) {
        val flags: Set<String> get() = today?.let { flagsOf(it) } ?: emptySet()
        /** The latest volatility-curve inversion in the readings (live data only, like [today]). */
        val inversion: fi.marketwatcher.work.NotificationRules.Inversion?
            get() = if (today == null) null else fi.marketwatcher.work.NotificationRules.lastInversion(history)
        /** The newest S&P 500 close: a price-only day if there is one, else today's reading. */
        val price: PriceView? get() = if (today == null) null else prices.lastOrNull() ?: today.price()
    }

    /**
     * The newest FRED S&P 500 close a background check has already evaluated. Not the cache: the
     * app's own refresh may have downloaded the close without notifying.
     */
    fun checkedPriceDate(): String? = memory.getString("checked_price_date", null)

    /** Cache + bundled history only: works offline and before an API key is set. */
    fun fromCache(): Result {
        settings.migrateKey()
        return compute(Series.entries.associateWith { cache.read(it) })
    }

    /**
     * Blocking network refresh: call off the main thread. [save] = true only for background checks:
     * they remember what was seen, so the next check notifies only what is new. The app's own
     * refresh uses false, so opening the app never uses up a notification (0.23).
     */
    fun refresh(save: Boolean): Result {
        settings.migrateKey()
        val fred = FredClient(settings.apiKey)
        val dailyStart = LocalDate.now().minusYears(11)   // FRED keeps ~10 years of SP500
        val data = try {
            Series.entries.associateWith { cache.update(it, fred, dailyStart) }
        } catch (e: Exception) {
            updateVoo(cache.read(Series.SP500).lastDate)   // FRED failed: VOO can still fill the gap
            throw e
        }
        updateVoo(data.getValue(Series.SP500).lastDate)
        settings.lastDownloadAt = System.currentTimeMillis()
        return compute(data).also { if (save) remember(it) }
    }

    /**
     * The early check: downloads only the S&P 500 (one small request) and reads the other series
     * from the cache. Does not count as a full download for the 30-minute rule.
     */
    fun refreshPriceOnly(): Result {
        settings.migrateKey()
        val fred = FredClient(settings.apiKey)
        val dailyStart = LocalDate.now().minusYears(11)
        val data = Series.entries.associateWith {
            if (it == Series.SP500) cache.update(it, fred, dailyStart) else cache.read(it)
        }
        updateVoo(data.getValue(Series.SP500).lastDate)
        return compute(data).also { remember(it) }
    }

    /**
     * Downloads VOO from Alpha Vantage only when FRED and the saved VOO closes both lack the expected
     * trading day, within [AvBudget]; dividends weekly. Never fails the refresh: the outcome is shown
     * on the Alpha Vantage card in Settings.
     */
    private fun updateVoo(fredLast: String?) {
        if (!settings.hasAvKey) return
        val now = ZonedDateTime.now()
        val expected = AvBudget.expectedDate(now)
        val nowMs = System.currentTimeMillis()
        val nyDay = now.withZoneSameInstant(AvBudget.NEW_YORK).toLocalDate().toString()
        val haveVoo = voo.closes().keys.maxOrNull()
        val needCloses = (fredLast == null || fredLast < expected) && (haveVoo == null || haveVoo < expected) &&
            AvBudget.allowed(settings.avLastRequestAt, settings.avDay, settings.avDayCount, nowMs, nyDay)
        val divAt = voo.dividendsFetchedAt()
        val needDividends = divAt == null || Duration.between(divAt, Instant.now()).toDays() >= AvBudget.DIVIDENDS_DAYS
        if (!needCloses && !needDividends) return
        try {
            val av = AlphaVantageClient(settings.avApiKey)
            if (needCloses) {
                settings.recordAvRequest(nowMs, nyDay)
                val c = av.dailyCloses()
                voo.addCloses(c)
                settings.recordAvResult("VOO closes to ${c.keys.maxOrNull()?.let { fi.marketwatcher.engine.Explain.dmy(it) } ?: "—"}.")
            }
            // On failure the saved dividends stay and the next try is a week later (the request limit is small).
            if (needDividends) voo.setDividends(runCatching { av.dividends() }.getOrElse { voo.dividends() })
        } catch (e: Exception) {
            settings.recordAvResult(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun compute(data: Map<Series, CachedSeries>): Result {
        fun v(s: Series) = data.getValue(s).values
        val cape = runCatching { CapeHistory.load(context) }.getOrNull()
        val lookups = DataAssembler.Lookups(
            v(Series.DGS2), v(Series.NFCI), v(Series.SAHM), v(Series.EQUITIES), v(Series.GDP),
            v(Series.T10Y2Y), cape)
        val live = DataAssembler.assemble(v(Series.SP500), v(Series.VIX), v(Series.VIX3M), lookups)
        // Bundled days keep their backtested S&P/VIX/NFCI values; the slow series are added from FRED.
        val bundled = BundledHistory.load(context).map { lookups.fill(it, keepNfci = true) }
        val days = BundledHistory.merge(bundled, live)
        val readings = SignalEngine.run(days)
        val liveLast = live.lastOrNull()?.date
        // Saved VOO closes estimate the S&P 500 after FRED's last close (also after the key is removed).
        val estimates = VooEstimate.after(v(Series.SP500), voo.closes(), voo.dividends())
        return Result(
            today = if (liveLast != null) readings.lastOrNull() else null,
            history = readings,
            series = data.map { (s, c) -> SeriesStatus(s, c.lastDate, c.fetchedAt) },
            previousState = memory.getString("state", null)?.let { runCatching { State.valueOf(it) }.getOrNull() },
            previousFlags = memory.getStringSet("flags", emptySet()).orEmpty(),
            previousBelowSma = if (memory.contains("below_sma")) memory.getBoolean("below_sma", false) else null,
            prices = if (liveLast != null) PriceDays.after(days, v(Series.SP500), estimates) else emptyList(),
            previousSharpFallDate = memory.getString("sharp_fall_date", null),
            fredPriceDate = data.getValue(Series.SP500).lastDate,
            previousInversionDate = memory.getString("inversion_date", null),
        )
    }

    private fun remember(r: Result) {
        val t = r.today ?: return
        val e = memory.edit().putString("state", t.state.name).putStringSet("flags", r.flags)
        // The price part comes from the newest close, which may be a price-only day.
        r.price?.let { p ->
            p.sma200?.let { e.putBoolean("below_sma", p.close < it) }
            if (p.fall != null) e.putString("sharp_fall_date", p.date)
        }
        e.putString("inversion_date", r.inversion?.date ?: "")
        r.fredPriceDate?.let { e.putString("checked_price_date", it) }
        e.apply()
    }

    companion object {
        /** True when the close is below the 200-day average, null before the average exists. */
        fun belowSma(r: Reading): Boolean? = r.sma200?.let { r.spx < it }

        fun flagsOf(r: Reading): Set<String> = fi.marketwatcher.work.NotificationRules.flagsOn(r.context).toSet()
    }
}
