package fi.marketwatcher.work

import fi.marketwatcher.work.NotificationRules.Notice
import fi.marketwatcher.work.Reminders.Action
import fi.marketwatcher.work.Reminders.Pending
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Reminder vibrations (0.27): every 120 minutes while unread, at most 3 times, only 08:00–18:00. */
class RemindersTest {
    private val zone = ZoneId.of("Europe/Helsinki")
    private fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 11, 19, h, m, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()
    private val notice = Notice(NotificationRules.ID_SHARP_FALL, "🟠 Sharp fall", "text")

    @Test fun unreadAndDueInsideHoursIsPostedAgain() {
        val p = Pending(notice, ms(at(12, 0)), 3)
        assertEquals(Action.REPOST, Reminders.decide(p, at(12, 0), listed = true))
        assertEquals("alarm a few minutes late", Action.REPOST, Reminders.decide(p, at(12, 7), listed = true))
    }

    @Test fun openedOrSwipedAwayIsDropped() {
        assertEquals(Action.DROP, Reminders.decide(Pending(notice, ms(at(12, 0)), 3), at(12, 0), listed = false))
    }

    @Test fun earlyAlarmWaits() {
        assertEquals(Action.WAIT, Reminders.decide(Pending(notice, ms(at(12, 0)), 3), at(11, 59), listed = true))
    }

    /** A notification at 15:00 is reminded at 17:00 only: the one due at 19:00 is skipped (no change to 08:00–18:00). */
    @Test fun dueAfterSixIsSkipped() {
        val first = Pending(notice, ms(at(17, 0)), 3)
        assertEquals(Action.REPOST, Reminders.decide(first, at(17, 0), listed = true))
        val second = Reminders.next(first, ms(at(17, 0)))!!
        assertEquals(ms(at(19, 0)), second.dueMs)
        assertEquals(Action.DROP, Reminders.decide(second, at(19, 0), listed = true))
        assertEquals("due at 18:00 itself is outside", Action.DROP,
            Reminders.decide(Pending(notice, ms(at(18, 0)), 2), at(18, 0), listed = true))
    }

    @Test fun everyTwoHoursAtMostThreeTimes() {
        var p: Pending? = Pending(notice, ms(at(8, 30)), Reminders.TIMES)
        val times = mutableListOf<ZonedDateTime>()
        var now = at(8, 30)
        while (p != null) {
            assertEquals(Action.REPOST, Reminders.decide(p, now, listed = true))
            times += now
            p = Reminders.next(p, ms(now))
            now = now.plusMinutes(Reminders.INTERVAL_MINUTES)
        }
        assertEquals(listOf(at(8, 30), at(10, 30), at(12, 30)), times)
    }

    @Test fun lastReminderLeavesNothing() {
        assertNull(Reminders.next(Pending(notice, 0, 1), 0))
    }

    @Test fun reminderTestIsATestNotice() {
        val t = NotificationRules.reminderTest()
        assertTrue(t.id >= NotificationRules.TEST_ID_OFFSET)
        assertTrue(t.title, t.title.startsWith("Test: 🟠 "))
    }
}
