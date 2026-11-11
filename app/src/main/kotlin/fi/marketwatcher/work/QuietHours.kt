package fi.marketwatcher.work

import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Notifications are shown only from 08:00 (inclusive) to 18:00 (exclusive), phone time. A notice
 * found outside these hours is held and shown at the next 08:00 (see [NotificationGate]).
 * Pure Kotlin: the phone's clock and time zone come in as [ZonedDateTime].
 */
object QuietHours {
    val OPEN: LocalTime = LocalTime.of(8, 0)
    val CLOSE: LocalTime = LocalTime.of(18, 0)
    const val TEXT = "Notifications are shown 08:00–18:00 phone time. Ones found outside these hours are shown at 08:00."

    fun isOpen(now: ZonedDateTime): Boolean = now.toLocalTime().let { it >= OPEN && it < CLOSE }

    /** The next moment notifications may be shown: [now] itself when already open. */
    fun nextOpen(now: ZonedDateTime): ZonedDateTime = when {
        isOpen(now) -> now
        now.toLocalTime() < OPEN -> now.with(OPEN).withSecond(0).withNano(0)
        else -> now.plusDays(1).with(OPEN).withSecond(0).withNano(0)
    }

    private val FOUND: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm dd.MM.yyyy")

    /** A held notice tells when it was found. */
    fun held(n: NotificationRules.Notice, foundAt: ZonedDateTime): NotificationRules.Notice =
        n.copy(text = n.text + " (Found ${foundAt.format(FOUND)}.)")

    /** Adds [new] to the held list; a newer notice of the same kind (id) replaces the older one. */
    fun merge(held: List<NotificationRules.Notice>, new: List<NotificationRules.Notice>): List<NotificationRules.Notice> {
        val ids = new.map { it.id }.toSet()
        return held.filter { it.id !in ids } + new
    }
}

/**
 * When the early check works: from 20:30 New York time on a US weekday (about 4.5 hours after the
 * close and half an hour after FRED usually adds the S&P 500 close; 03:30 Helsinki) until 03:30 New
 * York time the next morning (10:30 Helsinki). The hourly job does nothing outside this window or
 * once a background check has seen that day's close. Computed in New York time, so both
 * daylight-saving changes are handled.
 */
object EarlySchedule {
    val NEW_YORK: java.time.ZoneId = java.time.ZoneId.of("America/New_York")
    val START_AT: LocalTime = LocalTime.of(20, 30)
    val GIVE_UP_AT: LocalTime = LocalTime.of(3, 30)   // the next morning, New York time
    const val INTERVAL_MINUTES = 60L

    /** The trading day whose close the early check looks for at [now] (any zone), or null outside the window. */
    fun expectedAt(now: ZonedDateTime): String? {
        val ny = now.withZoneSameInstant(NEW_YORK)
        for (d in listOf(ny.toLocalDate(), ny.toLocalDate().minusDays(1))) {
            if (d.dayOfWeek.value > 5) continue
            val start = d.atTime(START_AT).atZone(NEW_YORK)
            val end = d.plusDays(1).atTime(GIVE_UP_AT).atZone(NEW_YORK)
            if (!ny.isBefore(start) && ny.isBefore(end)) return d.toString()
        }
        return null
    }
}
