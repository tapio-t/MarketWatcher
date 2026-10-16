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

    @Test fun earlyCheckSchedule() {
        // Friday 16.10.2026 08:40 Helsinki: next run Friday 20:30 New York (03:30 Saturday Helsinki).
        val r = EarlySchedule.next(at("2026-10-16T08:40"))
        assertEquals("2026-10-16", r.expectedDate)
        assertEquals(at("2026-10-17T03:30"), r.at.withZoneSameInstant(hki))
        // After Friday's run: next is Monday.
        assertEquals("2026-10-19", EarlySchedule.next(r.at).expectedDate)
        // Retries hourly until 03:30 New York the next morning (10:30 Helsinki).
        assertEquals(at("2026-10-17T04:30"), EarlySchedule.retry(r, r.at)!!.withZoneSameInstant(hki))
        assertEquals(null, EarlySchedule.retry(r, at("2026-10-17T10:00")))
        // US daylight saving ends 1.11.2026: Monday 2.11 run is 20:30 EST = 03:30 Helsinki (EET) too.
        assertEquals(at("2026-11-03T03:30"), EarlySchedule.next(at("2026-11-02T12:00")).at.withZoneSameInstant(hki))
    }
}
