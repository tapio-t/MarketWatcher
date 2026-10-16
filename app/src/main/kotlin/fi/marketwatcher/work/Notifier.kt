package fi.marketwatcher.work

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import fi.marketwatcher.work.NotificationRules.CHANNEL_BELOW_SMA
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

    /**
     * Creates the channels at high importance (pop-up banners) and removes the old
     * default-importance channel, whose importance an app cannot raise. Safe to call often.
     */
    fun ensureChannels(ctx: Context) {
        val nm = NotificationManagerCompat.from(ctx)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATE, "State and economy",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When the suggested state changes or an economy flag turns on"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_BELOW_SMA, "Below 200-day average",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When the S&P 500 closes below its 200-day average"
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SHARP_FALL, "Sharp falls",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When the S&P 500 falls 2% or more in a day"
        })
        if (nm.getNotificationChannel(LEGACY_CHANNEL) != null) nm.deleteNotificationChannel(LEGACY_CHANNEL)
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
                    .setStyle(NotificationCompat.BigTextStyle().bigText(n.text))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)   // banners on Android 7.1 and older style
                    .setCategory(NotificationCompat.CATEGORY_STATUS)
                    .setAutoCancel(true)
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
