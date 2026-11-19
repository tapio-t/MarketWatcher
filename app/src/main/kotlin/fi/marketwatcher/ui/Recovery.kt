package fi.marketwatcher.ui

import java.time.LocalDate
import java.time.Period

/**
 * Recovery time for the History tile: from the date of the all-time high in effect on a day to the
 * first later close at or above that high. Hindsight by design (the tile shows when it was reached again).
 */

/**
 * For each day: index of the first later close at or above its all-time high, or -1 if not reached yet.
 * Days that share an all-time high are handled together, so the whole pass is O(n): each scan stops at
 * the next new high, which is where the next run of days starts.
 */
internal fun recoveryIndices(dates: List<String>, close: FloatArray, ath: List<Double>, athDate: List<String>): IntArray {
    val n = dates.size
    val out = IntArray(n) { -1 }
    var s = 0
    while (s < n) {
        var e = s + 1
        while (e < n && athDate[e] == athDate[s]) e++                 // days sharing this high
        // A close equal to the high counts as reached again (the engine sets a new high only when above it).
        // Compared as Float, the precision of [close], so an exact repeat of the high matches.
        val high = ath[s].toFloat()
        var k = s
        while (k < n && !(dates[k] > athDate[s] && close[k] >= high)) k++
        val found = if (k < n) k else -1
        for (i in s until e) out[i] = found
        s = e
    }
    return out
}

/** "5 years, 5 months", "1 year, 0 months": whole years and months between two ISO dates, counted down. */
internal fun yearsMonths(from: String, to: String): String {
    val p = Period.between(LocalDate.parse(from), LocalDate.parse(to))
    fun unit(v: Int, word: String) = "$v $word" + if (v == 1) "" else "s"
    return unit(p.years, "year") + ", " + unit(p.months, "month")
}

/** "1 year, 7 months, 9 days": whole years, months and days between two ISO dates. */
internal fun yearsMonthsDays(from: String, to: String): String {
    val p = Period.between(LocalDate.parse(from), LocalDate.parse(to))
    fun unit(v: Int, word: String) = "$v $word" + if (v == 1) "" else "s"
    return unit(p.years, "year") + ", " + unit(p.months, "month") + ", " + unit(p.days, "day")
}

/** "1 y 7 mo 9 d" for a tile; zero years (and then zero months) are left out: "4 mo 2 d", "6 d". */
internal fun ymdShort(from: String, to: String): String {
    val p = Period.between(LocalDate.parse(from), LocalDate.parse(to))
    return when {
        p.years > 0 -> "${p.years} y ${p.months} mo ${p.days} d"
        p.months > 0 -> "${p.months} mo ${p.days} d"
        else -> "${p.days} d"
    }
}

/**
 * A median gap given in calendar days as years, months and days, counted from 01.01.2001 (a fixed
 * non-leap start, so the same number always gives the same text): 122 → "4 months, 2 days".
 * Zero years and months are left out.
 */
internal fun daysAsYmd(days: Double, short: Boolean = false): String {
    val start = LocalDate.of(2001, 1, 1)
    val p = Period.between(start, start.plusDays(Math.round(days)))
    fun unit(v: Int, word: String, abbr: String) = if (short) "$v $abbr" else "$v $word" + if (v == 1) "" else "s"
    return listOfNotNull(if (p.years > 0) unit(p.years, "year", "y") else null,
        if (p.years > 0 || p.months > 0) unit(p.months, "month", "mo") else null, unit(p.days, "day", "d"))
        .joinToString(if (short) " " else ", ")
}
