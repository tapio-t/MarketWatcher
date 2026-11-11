package fi.marketwatcher.work

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class QuietHoursTest {
    private val hki = ZoneId.of("Europe/Helsinki")
    private fun at(s: String, zone: ZoneId = hki) = ZonedDateTime.of(java.time.LocalDateTime.parse(s), zone)

    @Test fun window() {
        assertFalse(QuietHours.isOpen(at("2026-10-16T07:59:59")))
        assertTrue(QuietHours.isOpen(at("2026-10-16T08:00")))
        assertTrue(QuietHours.isOpen(at("2026-10-16T17:59:59")))
        assertFalse(QuietHours.isOpen(at("2026-10-16T18:00")))
    }

    @Test fun nextOpen() {
        assertEquals(at("2026-10-16T08:00"), QuietHours.nextOpen(at("2026-10-16T03:32:10")))
        assertEquals(at("2026-10-17T08:00"), QuietHours.nextOpen(at("2026-10-16T18:00")))
        assertEquals(at("2026-10-16T12:00"), QuietHours.nextOpen(at("2026-10-16T12:00")))
        // Daylight saving ends on 25.10.2026 at 04:00 in Helsinki: still 08:00 local.
        assertEquals(at("2026-10-25T08:00"), QuietHours.nextOpen(at("2026-10-25T02:30")))
        // The phone's own time zone decides.
        val ny = ZoneId.of("America/New_York")
        assertEquals(at("2026-10-16T08:00", ny), QuietHours.nextOpen(at("2026-10-16T05:00", ny)))
    }

    @Test fun heldNoticesKeepTheNewestPerKind() {
        val a = NotificationRules.Notice(4, "Sharp fall A", "a")
        val b = NotificationRules.Notice(4, "Sharp fall B", "b")
        val c = NotificationRules.Notice(1, "State", "c")
        assertEquals(listOf(c, b), QuietHours.merge(listOf(a, c), listOf(b)))
        assertEquals("a (Found 03:32 16.10.2026.)", QuietHours.held(a, at("2026-10-16T03:32")).text)
    }

    @Test fun earlyCheckWindow() {
        // Friday 16.10.2026: the window for Friday's close is 03:30–10:30 Saturday Helsinki (EEST).
        assertEquals(null, EarlySchedule.expectedAt(at("2026-10-16T08:40")))
        assertEquals(null, EarlySchedule.expectedAt(at("2026-10-17T03:29")))
        assertEquals("2026-10-16", EarlySchedule.expectedAt(at("2026-10-17T03:30")))
        assertEquals("2026-10-16", EarlySchedule.expectedAt(at("2026-10-17T10:29")))
        assertEquals(null, EarlySchedule.expectedAt(at("2026-10-17T10:30")))
        // No window on Saturday or Sunday night.
        assertEquals(null, EarlySchedule.expectedAt(at("2026-10-18T05:00")))
        assertEquals(null, EarlySchedule.expectedAt(at("2026-10-19T05:00")))
        // US daylight saving ends 1.11.2026: Monday 2.11 still starts at 03:30 Helsinki (EET).
        assertEquals(null, EarlySchedule.expectedAt(at("2026-11-03T03:29")))
        assertEquals("2026-11-02", EarlySchedule.expectedAt(at("2026-11-03T03:30")))
        // Thursday 05.11.2026 (the −4.36% day): looked for from 03:30 on 06.11.
        assertEquals("2026-11-05", EarlySchedule.expectedAt(at("2026-11-06T04:30")))
    }
}
