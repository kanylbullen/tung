package io.github.kanylbullen.tung.core

import java.time.LocalDate

/**
 * How a program picks the day to train: [FIXED] maps each day to weekdays,
 * [ROTATING] goes through the days in order whatever the date. [key] is what
 * is stored.
 */
enum class ProgramMode(val key: String) {
    FIXED("fixed"),
    ROTATING("rotating");

    companion object {
        /** The mode stored as [key]; an unknown key reads as [ROTATING], which never depends on the date. */
        fun fromKey(key: String): ProgramMode = entries.firstOrNull { it.key == key } ?: ROTATING
    }
}

/** ISO weekday numbers: 1 is Monday, 7 is Sunday (`java.time.DayOfWeek.value`). */
val ISO_WEEKDAYS: IntRange = 1..7

/**
 * One day of a program as the suggestion sees it: its [id], its [position] in
 * the program and, in fixed mode, the ISO [weekdays] it is trained on.
 */
data class PlanDay(val id: String, val position: Int, val weekdays: Set<Int> = emptySet())

/**
 * The day a program suggests for [today].
 *
 * - [ProgramMode.FIXED]: the first day (by position) that has today's weekday,
 *   or null for a rest day.
 * - [ProgramMode.ROTATING]: the day after the last completed one
 *   ([lastCompletedDayId]) by position, wrapping from the last day to the
 *   first; the first day when nothing has been completed yet, or when the
 *   completed day is no longer in the program.
 *
 * Null when the program has no days.
 */
fun suggestedDay(mode: ProgramMode, days: List<PlanDay>, today: LocalDate, lastCompletedDayId: String?): PlanDay? {
    val ordered = days.sortedBy { it.position }
    if (ordered.isEmpty()) return null
    return when (mode) {
        ProgramMode.FIXED -> {
            val weekday = today.dayOfWeek.value
            ordered.firstOrNull { weekday in it.weekdays }
        }
        ProgramMode.ROTATING -> {
            val last = ordered.indexOfFirst { it.id == lastCompletedDayId }
            if (last < 0) ordered.first() else ordered[(last + 1) % ordered.size]
        }
    }
}

/** "Day [number] of [count]"; the app formats it with its own string. */
data class DayOfCount(val number: Int, val count: Int)

/**
 * The label parts of a rotating day at zero-based [position] among [count]
 * days: position 1 of 3 is "Day 2 of 3".
 */
fun rotatingLabel(position: Int, count: Int): DayOfCount {
    require(count > 0) { "a program with no days has no day label" }
    require(position in 0 until count) { "position $position is outside 0 until $count" }
    return DayOfCount(number = position + 1, count = count)
}

/**
 * Weekdays as stored: one bit per ISO weekday, Monday in bit 0. Numbers
 * outside 1..7 are ignored.
 */
fun weekdaysToMask(weekdays: Set<Int>): Int =
    weekdays.filter { it in ISO_WEEKDAYS }.fold(0) { mask, day -> mask or (1 shl (day - 1)) }

/** The ISO weekdays in a stored mask, Monday first. */
fun maskToWeekdays(mask: Int): Set<Int> = ISO_WEEKDAYS.filter { mask and (1 shl (it - 1)) != 0 }.toSet()

/** [mask] with [weekday] switched on if it was off, off if it was on. */
fun toggleWeekday(mask: Int, weekday: Int): Int {
    require(weekday in ISO_WEEKDAYS) { "weekday $weekday is not an ISO weekday" }
    return mask xor (1 shl (weekday - 1))
}
