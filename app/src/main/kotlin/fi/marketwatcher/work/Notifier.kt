package fi.marketwatcher.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import fi.marketwatcher.work.NotificationRules.CHANNEL_BELOW_RUN
import fi.marketwatcher.work.NotificationRules.CHANNEL_BELOW_SMA
import fi.marketwatcher.work.NotificationRules.CHANNEL_CURVE
import fi.marketwatcher.work.NotificationRules.CHANNEL_SHARP_FALL
import fi.marketwatcher.work.NotificationRules.CHANNEL_STATE
import fi.marketwatcher.work.NotificationRules.LEGACY_CHANNEL

/** Posting notifications, and finding out why one would not be shown. */
object Notifier {

    enum class Blocked(val message: String) {
        PERMISSION("Blocked: notification permission is not granted."),
        APP("Blocked: notifications are turned off for MarketWatcher."),
        CHANNEL("Blocked: this notification type is turned off in Android settings."),
    }

    /** What happened to a posted notification, checked against Android's list of active ones. */
    enum class Delivery { SHOWN, SHOWN_DND, NOT_LISTED }

    /** Every notification vibrates with this pattern (off, on, off, on in ms), whatever the phone's default is. */
    val VIBRATION = longArrayOf(0, 300, 200, 300)

    /**
     * Creates the channels at high importance (pop-up banners) with vibration, and removes the old
     * channels: the default-importance one (up to 0.8) and the ones without vibration (up to 0.24),
     * whose settings an app cannot change. A replaced channel that was turned off in Android
     * settings is created turned off. Safe to call often.
     */
    fun ensureChannels(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        fun channel(id: String, name: String, text: String) {
            if (nm.getNotificationChannel(id) != null) return
            // Keep the user's choice from the channel this one replaces.
            val old = NotificationRules.OLD_CHANNELS.entries.firstOrNull { it.value == id }?.key
            val off = old?.let { nm.getNotificationChannel(it) }?.importance == NotificationManager.IMPORTANCE_NONE
            nm.createNotificationChannel(NotificationChannel(id, name,
                if (off) NotificationManager.IMPORTANCE_NONE else NotificationManager.IMPORTANCE_HIGH).apply {
                description = text
                enableVibration(true)
                vibrationPattern = VIBRATION
            })
        }
        channel(CHANNEL_STATE, "State and economy", "When the suggested state changes or an economy flag turns on")
        channel(CHANNEL_BELOW_SMA, "Below 200-day average", "When the S&P 500 closes below its 200-day average")
        channel(CHANNEL_BELOW_RUN, "Below average for ${NotificationRules.BELOW_RUN_ALERT} days",
            "When the S&P 500 has closed below its 200-day average ${NotificationRules.BELOW_RUN_ALERT} days in a row")
        channel(CHANNEL_SHARP_FALL, "Sharp falls", "When the S&P 500 falls 2% or more in a day")
        channel(CHANNEL_CURVE, "Volatility curve inverted", "When VIX/VIX3M reaches 1.00 (again after falling to 0.90 or below)")
        (NotificationRules.OLD_CHANNELS.keys + LEGACY_CHANNEL).forEach {
            if (nm.getNotificationChannel(it) != null) nm.deleteNotificationChannel(it)
        }
    }

    fun needsPermission(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    /** Null when a notice on [channel] can be shown. */
    fun blocked(ctx: Context, channel: String): Blocked? {
        if (needsPermission(ctx)) return Blocked.PERMISSION
        val nm = NotificationManagerCompat.from(ctx)
        if (!nm.areNotificationsEnabled()) return Blocked.APP
        ensureChannels(ctx)
        val ch = nm.getNotificationChannel(channel)
        if (ch != null && ch.importance == NotificationManager.IMPORTANCE_NONE) return Blocked.CHANNEL
        return null
    }

    /**
     * Posts [n] on its channel at once. Returns false if it was blocked. Real and test notices go
     * through [NotificationGate], which holds them outside 08:00–18:00.
     */
    @SuppressLint("MissingPermission") // checked by blocked() just above
    fun post(ctx: Context, n: NotificationRules.Notice): Boolean {
        val channel = NotificationRules.channelOf(n.id)
        if (blocked(ctx, channel) != null) return false
        return try {
            NotificationManagerCompat.from(ctx).notify(n.id,
                NotificationCompat.Builder(ctx, channel)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(n.title)
                    .setContentText(n.text)
                    .setStyle(style(ctx, n))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)   // banners on Android 7.1 and older style
                    .setVibrate(VIBRATION)                           // Android 7.1 and older; newer ones use the channel's
                    .setCategory(if (messageStyle(ctx)) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
                    .setContentIntent(openApp(ctx))
                    // On a locked screen show only "MarketWatcher: market update" until the phone is unlocked.
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(NotificationCompat.Builder(ctx, channel)
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setContentTitle("MarketWatcher")
                        .setContentText("Market update")
                        .build())
                    .build())
            true
        } catch (e: SecurityException) {
            false   // permission revoked between the check and the post
        }
    }

    /** Settings > "Show notifications as messages" (0.25, off by default). */
    private fun messageStyle(ctx: Context): Boolean = fi.marketwatcher.data.Settings(ctx).messageStyle

    /**
     * Message style (0.25): the notice as one message from "MarketWatcher", so phone features that
     * alert again for unread messages (Samsung's Smart alert: vibrate when the phone is picked up)
     * may include it. Otherwise the plain expandable text of earlier versions.
     */
    private fun style(ctx: Context, n: NotificationRules.Notice): NotificationCompat.Style {
        if (!messageStyle(ctx)) return NotificationCompat.BigTextStyle().bigText(n.text)
        val sender = Person.Builder().setName("MarketWatcher").setKey("marketwatcher").build()
        return NotificationCompat.MessagingStyle(sender)
            .setConversationTitle(n.title)
            .addMessage(n.text, System.currentTimeMillis(), sender)
    }

    /** Tapping a notification opens MarketWatcher (or brings it to the front). */
    private fun openApp(ctx: Context): PendingIntent? {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(ctx, 0, launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** How long after posting a notification is checked again: another app may remove it at once. */
    const val RECHECK_MS = 2000L

    /**
     * Blocking (call off the main thread): waits [RECHECK_MS] and returns the notices Android no
     * longer lists. On 20.10.2026 a launcher's notification filter removed one after 0.24 s.
     */
    fun removedSoonAfterPosting(ctx: Context, posted: List<NotificationRules.Notice>): List<NotificationRules.Notice> {
        if (posted.isEmpty()) return emptyList()
        Thread.sleep(RECHECK_MS)
        return posted.filter { delivery(ctx, it.id) == Delivery.NOT_LISTED }
    }

    /**
     * The apps allowed to read and remove other apps' notifications (Android settings:
     * "Notification read, reply and control"), by name. One of them removed a missing notification.
     */
    fun listenerApps(ctx: Context): List<String> {
        val pm = ctx.packageManager
        return NotificationManagerCompat.getEnabledListenerPackages(ctx)
            .filter { it != ctx.packageName }
            .map { pkg -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg) }
            .sorted()
    }

    /** One sentence for "Last background check" and the Test result when another app removed a notification. */
    fun removedText(ctx: Context): String {
        val apps = listenerApps(ctx)
        return "Another app removed it within ${RECHECK_MS / 1000} seconds" +
            (if (apps.isEmpty()) "." else ". Apps allowed to remove notifications: ${apps.joinToString(", ")}.") +
            " Allow MarketWatcher in that app's notification filter."
    }

    /** Whether Android lists notification [id] as active, and whether Do Not Disturb may hide it. */
    fun delivery(ctx: Context, id: Int): Delivery {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val listed = nm.activeNotifications.any { it.id == id && it.packageName == ctx.packageName }
        if (!listed) return Delivery.NOT_LISTED
        val dnd = nm.currentInterruptionFilter.let {
            it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        }
        return if (dnd) Delivery.SHOWN_DND else Delivery.SHOWN
    }
}
