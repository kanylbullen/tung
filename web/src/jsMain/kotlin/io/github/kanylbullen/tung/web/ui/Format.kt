package io.github.kanylbullen.tung.web.ui

import io.github.kanylbullen.tung.web.i18n.Strings
import kotlin.js.Date

/** The browser's formatters; only what the screens use is declared. */
@JsName("Intl")
private external object Intl {
    class DateTimeFormat(locales: String, options: dynamic) {
        fun format(date: Date): String
    }

    class NumberFormat(locales: String, options: dynamic) {
        fun format(value: Double): String
    }
}

/**
 * "Thu 22 Oct" ("tors 22 okt" in Swedish), the year added when it is not
 * this year ("Thu 22 Oct 2023"), first letter capitalised: the phone's
 * `shortLocalDate`, with the browser's calendar.
 */
fun shortDate(epochMillis: Long, locale: String, nowMillis: Long): String {
    val date = Date(epochMillis.toDouble())
    val options: dynamic = js("({})")
    options.weekday = "short"
    options.day = "numeric"
    options.month = "short"
    if (date.getFullYear() != Date(nowMillis.toDouble()).getFullYear()) options.year = "numeric"
    val text = Intl.DateTimeFormat(locale, options).format(date)
    return text.replaceFirstChar { it.uppercase() }
}

/** "22 Oct 2023, 14:05": a date with its time, for the sync status. */
fun dateTime(epochMillis: Long, locale: String): String {
    val options: dynamic = js("({})")
    options.day = "numeric"
    options.month = "short"
    options.year = "numeric"
    options.hour = "2-digit"
    options.minute = "2-digit"
    return Intl.DateTimeFormat(locale, options).format(Date(epochMillis.toDouble()))
}

/** Whole kilos with the locale's grouping: "12 450", the phone's `localVolume`. */
fun wholeNumber(value: Double, locale: String): String {
    val options: dynamic = js("({})")
    options.maximumFractionDigits = 0
    return Intl.NumberFormat(locale, options).format(value)
}

/** "47 min" or "1 h 12 min", the phone's `durationText`. */
fun durationText(millis: Long, strings: Strings): String {
    val totalMinutes = (millis.coerceAtLeast(0L) / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) strings.durationHoursMinutes(hours, minutes) else strings.durationMinutes(minutes)
}

/** The sync status line: never, just now, minutes or hours ago, else the date. */
fun lastSyncText(lastSyncAt: Long?, nowMillis: Long, locale: String, strings: Strings): String {
    if (lastSyncAt == null) return strings.lastSyncNever
    val minutes = ((nowMillis - lastSyncAt) / 60_000L).toInt()
    return when {
        minutes < 1 -> strings.lastSyncJustNow
        minutes < 60 -> strings.lastSyncMinutesAgo(minutes)
        minutes < 24 * 60 -> strings.lastSyncHoursAgo(minutes / 60)
        else -> strings.lastSyncOn(dateTime(lastSyncAt, locale))
    }
}

/** The weekday of [date] as the locale writes it short ("Mon", "mån"). */
fun weekdayName(date: kotlinx.datetime.LocalDate, locale: String): String {
    val options: dynamic = js("({})")
    options.weekday = "short"
    options.timeZone = "UTC"
    val utcNoon = Date(Date.UTC(date.year, date.monthNumber - 1, date.dayOfMonth, 12, 0, 0, 0))
    return Intl.DateTimeFormat(locale, options).format(utcNoon)
}
