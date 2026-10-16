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
 * When the early check runs: 20:30 New York time on US weekdays, about 4.5 hours after the close
 * and half an hour after FRED usually adds the S&P 500 close (03:30 Helsinki time). If the close is
 * not there yet, it tries again every hour until 03:30 New York time (10:30 Helsinki).
 * Computed in New York time, so both daylight-saving changes are handled.
 */
object EarlySchedule {
    val NEW_YORK: java.time.ZoneId = java.time.ZoneId.of("America/New_York")
    val RUN_AT: LocalTime = LocalTime.of(20, 30)
    val GIVE_UP_AT: LocalTime = LocalTime.of(3, 30)   // the next morning, New York time
    const val RETRY_MINUTES = 60L

    data class Run(val at: ZonedDateTime, val expectedDate: String, val deadline: ZonedDateTime)

    /** The next regular run strictly after [now] (any zone), with the trading day it expects. */
    fun next(now: ZonedDateTime): Run {
        val ny = now.withZoneSameInstant(NEW_YORK)
        var d = ny.toLocalDate()
        while (true) {
            val at = d.atTime(RUN_AT).atZone(NEW_YORK)
            val weekday = d.dayOfWeek.value <= 5
            if (weekday && at.isAfter(ny)) return Run(at, d.toString(), d.plusDays(1).atTime(GIVE_UP_AT).atZone(NEW_YORK))
            d = d.plusDays(1)
        }
    }

    /** The retry after a run at [now] that did not find [run]'s day yet, or null after the deadline. */
    fun retry(run: Run, now: ZonedDateTime): ZonedDateTime? =
        now.plusMinutes(RETRY_MINUTES).takeIf { !it.isAfter(run.deadline) }
}
