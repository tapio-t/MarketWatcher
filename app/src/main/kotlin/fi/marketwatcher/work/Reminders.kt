package fi.marketwatcher.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import fi.marketwatcher.data.Settings
import fi.marketwatcher.work.NotificationRules.Notice
import org.json.JSONArray
import org.json.JSONObject
import java.time.ZonedDateTime

/**
 * Reminder vibrations (0.27): a notification still in Android's notification list (not opened or
 * swiped away) is posted again every [INTERVAL_MINUTES] minutes, at most [TIMES] times, so the phone
 * vibrates again, like an unread text message. Only inside the notification hours (08:00–18:00):
 * a reminder due later is skipped together with the ones after it. Samsung's Smart alert (vibrate
 * when the phone is picked up) ignores other apps' notifications, also as messages or conversations
 * (tested on a Galaxy S23+, 19.11.2026).
 */
object Reminders {
    const val INTERVAL_MINUTES = 120L
    const val TIMES = 3
    /** Settings > Test waits this long instead of [INTERVAL_MINUTES] (Android may run the alarm a little later). */
    const val TEST_DELAY_MS = 60_000L

    /** A reminder: [notice] is posted again at [dueMs] (epoch ms); [left] reminders remain, this one included. */
    data class Pending(val notice: Notice, val dueMs: Long, val left: Int)

    enum class Action { REPOST, WAIT, DROP }

    /** Pure: what to do with [p] at [now]; [listed] = the notification is still in the notification list. */
    fun decide(p: Pending, now: ZonedDateTime, listed: Boolean): Action = when {
        !listed -> Action.DROP                                   // opened or swiped away
        now.toInstant().toEpochMilli() < p.dueMs -> Action.WAIT  // the alarm came early
        !QuietHours.isOpen(now) -> Action.DROP                   // due after 18:00: the rest are skipped too
        else -> Action.REPOST
    }

    /** The reminder after [p] was posted again at [nowMs], or null when it was the last one. */
    fun next(p: Pending, nowMs: Long, intervalMs: Long = INTERVAL_MINUTES * 60_000): Pending? =
        if (p.left > 1) p.copy(dueMs = nowMs + intervalMs, left = p.left - 1) else null

    private const val PREFS = "marketwatcher_state"
    private const val KEY = "reminders"
    private val LOCK = Any()

    /**
     * Starts reminders for [n] (a newer notice of the same kind replaces the older one's). Real notices
     * use the defaults; the Settings test uses [TEST_DELAY_MS] and one reminder.
     */
    fun start(ctx: Context, n: Notice, delayMs: Long = INTERVAL_MINUTES * 60_000, times: Int = TIMES,
              nowMs: Long = System.currentTimeMillis()) {
        synchronized(LOCK) {
            write(ctx, read(ctx).filter { it.notice.id != n.id } + Pending(n, nowMs + delayMs, times))
            schedule(ctx)
        }
    }

    /** Removes every reminder (the Settings switch was turned off). */
    fun clear(ctx: Context) = synchronized(LOCK) {
        write(ctx, emptyList())
        runCatching { ctx.getSystemService(AlarmManager::class.java).cancel(alarmIntent(ctx)) }
    }

    /** Reminders waiting (for tests and Settings). */
    fun pending(ctx: Context): List<Pending> = synchronized(LOCK) { read(ctx) }

    /** Runs the due reminders and sets the alarm for the next one. Returns the notices posted again. */
    fun run(ctx: Context, now: ZonedDateTime = ZonedDateTime.now()): List<Notice> = synchronized(LOCK) {
        val nowMs = now.toInstant().toEpochMilli()
        val posted = mutableListOf<Notice>()
        val keep = read(ctx).mapNotNull { p ->
            val listed = Notifier.delivery(ctx, p.notice.id) != Notifier.Delivery.NOT_LISTED
            when (decide(p, now, listed)) {
                Action.WAIT -> p
                Action.DROP -> null
                Action.REPOST -> if (Notifier.post(ctx, p.notice, remind = false)) { posted += p.notice; next(p, nowMs) } else null
            }
        }
        write(ctx, keep)
        schedule(ctx)
        posted
    }

    /** The alarm for the earliest reminder; it also fires while the phone sleeps (Android may delay it a few minutes). */
    private fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val first = read(ctx).minOfOrNull { it.dueMs }
        runCatching {
            if (first == null) am.cancel(alarmIntent(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, first, alarmIntent(ctx))
        }
    }

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, 1,
        Intent(ctx, ReminderReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun read(ctx: Context): List<Pending> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Pending(Notice(o.getInt("id"), o.getString("title"), o.getString("text")), o.getLong("due"), o.getInt("left"))
            }
        }.getOrDefault(emptyList())
    }

    private fun write(ctx: Context, list: List<Pending>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.notice.id).put("title", it.notice.title)
            .put("text", it.notice.text).put("due", it.dueMs).put("left", it.left)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).commit()
    }

    /** Whether real notices get reminders (Settings switch, on by default). */
    fun enabled(ctx: Context): Boolean = Settings(ctx).remindUnread
}

/** The reminder alarm: posts unread MarketWatcher notifications again, also while the phone sleeps. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread {
            try {
                Reminders.run(app)
            } catch (e: Exception) {
                // The next reminder or app start sets the alarm again.
            } finally {
                pending.finish()
            }
        }.start()
    }
}
