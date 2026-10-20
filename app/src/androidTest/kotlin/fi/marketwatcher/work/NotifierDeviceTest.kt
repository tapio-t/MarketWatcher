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
