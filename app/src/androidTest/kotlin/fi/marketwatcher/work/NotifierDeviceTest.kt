package fi.marketwatcher.work

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fi.marketwatcher.engine.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On a phone or emulator (`./gradlew connectedAndroidTest`): a test notice is posted on a
 * high-importance channel and Android lists it as active.
 */
@RunWith(AndroidJUnit4::class)
class NotifierDeviceTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before fun grantPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(ctx.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    @Test fun channelsAreHighImportanceAndLegacyIsGone() {
        Notifier.ensureChannels(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java)!!
        for (id in listOf(NotificationRules.CHANNEL_STATE, NotificationRules.CHANNEL_BELOW_SMA)) {
            val ch = nm.getNotificationChannel(id)
            assertNotEquals(null, ch)
            // on a fresh install (the user can lower it later in Android settings)
            assertEquals(NotificationManager.IMPORTANCE_HIGH, ch.importance)
        }
        assertEquals(null, nm.getNotificationChannel(NotificationRules.LEGACY_CHANNEL))
        // 0.25: every channel vibrates, and the channels without vibration (up to 0.24) are gone.
        for (id in NotificationRules.OLD_CHANNELS.values + NotificationRules.CHANNEL_BELOW_RUN) {
            val ch = nm.getNotificationChannel(id)
            assertTrue(id, ch.shouldVibrate())
            assertTrue(id, ch.vibrationPattern.contentEquals(Notifier.VIBRATION))
        }
        NotificationRules.OLD_CHANNELS.keys.forEach { assertEquals(it, null, nm.getNotificationChannel(it)) }
    }

    private val nm get() = ctx.getSystemService(NotificationManager::class.java)!!

    /** The active notification [id] within half a second, or null (e.g. removed by a launcher's filter). */
    private fun active(id: Int): android.app.Notification? {
        repeat(20) {
            nm.activeNotifications.firstOrNull { it.id == id }?.notification?.let { return it }
            Thread.sleep(25)
        }
        return null
    }

    /** The phone's notification vibrations of this app since [since] ("MM-dd HH:mm:ss.SSS"), from its vibration log. */
    private fun vibrationsSince(since: String): List<String> {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys vibrator_manager")
        val log = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readText()
        return log.lines().filter { "usage: NOTIFICATION" in it && "Notification (${ctx.packageName} " in it }
            .filter { it.trim().take(18) > since }
    }

    private fun phoneTime(): String = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US).format(java.util.Date())

    /** 0.27: MarketWatcher's amber icon and colour, and the 🟠 in the title. */
    @Test fun noticeLooksDistinct() {
        val now = NotificationRules.Now("2026-09-22", State.CALM, emptySet(), emptyMap(), 100.0, 95.0)
        val notice = NotificationRules.test(NotificationRules.Kind.BELOW_SMA, now).single()
        assertTrue(Notifier.post(ctx, notice))
        val n = active(notice.id)
        nm.cancel(notice.id)
        assumeFalse("Removed by another app's notification filter", n == null && Notifier.listenerApps(ctx).isNotEmpty())
        assertEquals(fi.marketwatcher.R.drawable.ic_stat_marketwatcher, n!!.smallIcon.resId)
        assertEquals(Notifier.AMBER, n.color)
        val title = n.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString()
        assertTrue(title, title.startsWith("Test: 🟠 "))
    }

    /**
     * 0.27: an unread notification is posted again by its reminder and the phone vibrates again (read
     * from the phone's own vibration log). Only 08:00–18:00, like the reminders themselves.
     */
    @Test fun reminderPostsAgainAndVibrates() {
        assumeTrue("Reminders run only 08:00–18:00", QuietHours.isOpen(java.time.ZonedDateTime.now()))
        val n = NotificationRules.reminderTest()
        assertTrue(Notifier.post(ctx, n))
        assumeTrue("Removed by another app's notification filter (${Notifier.listenerApps(ctx).joinToString()})", active(n.id) != null)
        Thread.sleep(1500)                                   // the first vibration ends
        val before = phoneTime()
        Reminders.start(ctx, n, delayMs = 0, times = 1)
        val posted = Reminders.run(ctx)
        assertEquals(listOf(n), posted)
        assertTrue("last reminder: nothing left", Reminders.pending(ctx).none { it.notice.id == n.id })
        var found = emptyList<String>()
        repeat(30) { if (found.isEmpty()) { Thread.sleep(100); found = vibrationsSince(before) } }
        val stillListed = active(n.id) != null
        nm.cancel(n.id)
        Reminders.clear(ctx)
        assumeFalse("Removed by another app's filter before vibrating", found.isEmpty() && !stillListed)
        assertTrue("no notification vibration after the reminder at $before", found.isNotEmpty())
    }

    /** A reminder for a notification that was opened or swiped away is dropped without posting. */
    @Test fun reminderForDismissedNoticeIsDropped() {
        val n = NotificationRules.reminderTest()
        Reminders.start(ctx, n, delayMs = 0, times = 3)
        assertEquals(emptyList<NotificationRules.Notice>(), Reminders.run(ctx))   // never posted: not listed
        assertTrue(Reminders.pending(ctx).isEmpty())
    }

    @Test fun testNoticeIsPostedAndListed() {
        val now = NotificationRules.Now("2026-09-22", State.CALM, emptySet(), emptyMap(), 100.0, 95.0)
        val notices = NotificationRules.test(NotificationRules.Kind.BELOW_SMA, now)
        assertEquals(1, notices.size)
        assertEquals(null, Notifier.blocked(ctx, NotificationRules.CHANNEL_BELOW_SMA))
        assertTrue(Notifier.post(ctx, notices[0]))
        // Look every 25 ms for half a second: was it listed, and is it still listed at the end?
        var seen = false
        var last = Notifier.Delivery.NOT_LISTED
        repeat(20) {
            Thread.sleep(25)
            last = Notifier.delivery(ctx, notices[0].id)
            if (last != Notifier.Delivery.NOT_LISTED) seen = true
        }
        ctx.getSystemService(NotificationManager::class.java)!!.cancel(notices[0].id)
        // Tapio's launcher filter removes the test app's notifications (the test app is reinstalled
        // on every run, so it can never be on the filter's allowed list). Skip, not fail, in that case.
        assumeFalse("Removed by another app's notification filter (${Notifier.listenerApps(ctx).joinToString()})",
            last == Notifier.Delivery.NOT_LISTED && (seen || Notifier.listenerApps(ctx).isNotEmpty()))
        assertNotEquals(Notifier.Delivery.NOT_LISTED, last)
    }
}
