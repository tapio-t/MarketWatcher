package fi.marketwatcher.work

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import fi.marketwatcher.data.Settings
import fi.marketwatcher.work.NotificationRules.Notice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Every notification goes through here: shown at once between 08:00 and 18:00 phone time, otherwise
 * held (newest per kind) and shown at the next 08:00 by [DeliverHeldReceiver] (an alarm that also
 * fires while the phone sleeps), [DeliverHeldWorker] (backup, survives a restart), or when the app
 * is opened inside the window, whichever comes first.
 */
object NotificationGate {
    /** [removed]: shown, but another app removed them within [Notifier.RECHECK_MS]. */
    data class Outcome(val shown: List<Notice>, val held: List<Notice>, val blocked: Notifier.Blocked?,
                       val removed: List<Notice> = emptyList())

    private const val PREFS = "marketwatcher_state"
    private const val KEY = "held_notices"
    private const val WORK = "deliver_held_notifications"
    private val LOCK = Any()

    /**
     * Shows [notices] now, or holds them until 08:00 when outside the notification hours. Blocking:
     * after showing, waits [Notifier.RECHECK_MS] to see whether another app removed them.
     */
    fun deliver(ctx: Context, notices: List<Notice>, now: ZonedDateTime = ZonedDateTime.now()): Outcome {
        if (notices.isEmpty()) return Outcome(emptyList(), emptyList(), null)
        if (!QuietHours.isOpen(now)) {
            hold(ctx, notices, now)
            return Outcome(emptyList(), notices, null)
        }
        val blocked = notices.firstNotNullOfOrNull { Notifier.blocked(ctx, NotificationRules.channelOf(it.id)) }
        val shown = notices.filter { Notifier.post(ctx, it) }
        return Outcome(shown, emptyList(), if (shown.size < notices.size) blocked else null,
            Notifier.removedSoonAfterPosting(ctx, shown))
    }

    /**
     * Adds [notices] to the held list and makes sure they are shown at the next 08:00. [policy]:
     * REPLACE normally; the delivery worker itself uses APPEND_OR_REPLACE so it does not cancel itself.
     */
    fun hold(ctx: Context, notices: List<Notice>, now: ZonedDateTime = ZonedDateTime.now(),
             policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        synchronized(LOCK) {
            write(ctx, QuietHours.merge(read(ctx), notices.map { QuietHours.held(it, now) }))
        }
        val at = QuietHours.nextOpen(now)
        // The alarm fires at 08:00 even while the phone sleeps (Android may delay it a few minutes).
        // The WorkManager job alone waited until the phone was woken (08:06 on 20.10.2026).
        runCatching {
            ctx.getSystemService(AlarmManager::class.java)
                .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), alarmIntent(ctx))
        }
        val delay = Duration.between(now, at).toMillis().coerceAtLeast(0)
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, policy,
            OneTimeWorkRequestBuilder<DeliverHeldWorker>().setInitialDelay(delay, TimeUnit.MILLISECONDS).build())
    }

    private fun alarmIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, 0,
        Intent(ctx, DeliverHeldReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Held notices waiting for 08:00 (for Settings). */
    fun held(ctx: Context): List<Notice> = synchronized(LOCK) { read(ctx) }

    /**
     * Inside the notification hours: shows the held notices and returns those shown. Ones Android
     * blocks right now (permission or notification type off) stay held for the next app start.
     */
    fun flush(ctx: Context, now: ZonedDateTime = ZonedDateTime.now()): List<Notice> {
        if (!QuietHours.isOpen(now)) return emptyList()
        return synchronized(LOCK) {
            val list = read(ctx)
            if (list.isEmpty()) return@synchronized emptyList()
            val shown = list.filter { Notifier.post(ctx, it) }
            write(ctx, list - shown.toSet())
            shown
        }
    }

    /**
     * Blocking: [flush], then checks that the shown notices stayed and writes the result to
     * "Last background check". Used by the alarm, the backup worker and the app start.
     */
    fun flushAndRecord(ctx: Context, now: ZonedDateTime = ZonedDateTime.now()) {
        val shown = flush(ctx, now)
        if (shown.isEmpty()) return
        val removed = Notifier.removedSoonAfterPosting(ctx, shown)
        val titles = shown.joinToString("; ") { it.title }
        Settings(ctx).recordCheck(Instant.now(),
            if (removed.isEmpty()) "Showed held notification(s): $titles."
            else "Showed held notification(s): $titles. ${Notifier.removedText(ctx)}")
    }

    private fun read(ctx: Context): List<Notice> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(s)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Notice(o.getInt("id"), o.getString("title"), o.getString("text"))
            }
        }.getOrDefault(emptyList())
    }

    private fun write(ctx: Context, list: List<Notice>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("title", it.title).put("text", it.text)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).commit()
    }
}

/** The 08:00 alarm: shows the held notifications, also while the phone sleeps. */
class DeliverHeldReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()   // about 10 seconds: delivery and the 2-second check fit
        Thread {
            try {
                val now = ZonedDateTime.now()
                if (QuietHours.isOpen(now)) NotificationGate.flushAndRecord(app, now)
                // Fired early (clock or time-zone change): set the alarm again for the next 08:00.
                else if (NotificationGate.held(app).isNotEmpty())
                    NotificationGate.hold(app, emptyList(), now, ExistingWorkPolicy.KEEP)
            } catch (e: Exception) {
                // The backup worker or the next app start shows them.
            } finally {
                pending.finish()
            }
        }.start()
    }
}

/** Backup for [DeliverHeldReceiver]: shows the held notifications at 08:00 (Android may run it late). */
class DeliverHeldWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val now = ZonedDateTime.now()
        if (!QuietHours.isOpen(now)) {
            // Ran early (clock or time-zone change): hold again for the next 08:00.
            NotificationGate.hold(applicationContext, emptyList(), now, ExistingWorkPolicy.APPEND_OR_REPLACE)
            return@withContext Result.success()
        }
        NotificationGate.flushAndRecord(applicationContext, now)
        Result.success()
    }
}
