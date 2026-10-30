package io.github.kanylbullen.tung.core.progress

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus

/** The Monday of [date]'s ISO week (which may be in the previous year). */
fun weekStart(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.isoDayNumber - 1, DateTimeUnit.DAY)

/** One ISO week of training: how many workouts started in it and their logged volume. */
data class WeekBucket(val weekStart: LocalDate, val workouts: Int, val volumeKg: Double)

/** Weeks the Progress tab's overview shows. */
const val OVERVIEW_WEEKS = 12

/**
 * The last [weeks] ISO weeks up to and including [today]'s, oldest first,
 * each with the workouts that have a logged set in it (by the workout's start
 * in [zone]) and their volume. Empty weeks are in the list with zeros, so a
 * chart has a bar for every week.
 */
fun weeklyBuckets(sets: List<ProgressSet>, today: LocalDate, zone: TimeZone, weeks: Int = OVERVIEW_WEEKS): List<WeekBucket> {
    val thisWeek = weekStart(today)
    val logged = sets.filter { it.isLogged }
    val byWeek = logged.groupBy { weekStart(dateOf(it.startedAt, zone)) }
    return (weeks - 1 downTo 0).map { back ->
        val start = thisWeek.minus(back, DateTimeUnit.WEEK)
        val rows = byWeek[start].orEmpty()
        WeekBucket(start, rows.mapTo(HashSet()) { it.workoutId }.size, rows.sumOf { it.set.volume })
    }
}

/**
 * Consecutive ISO weeks with at least one workout, counted back from [today]'s
 * week. The current week is not over, so while it has no workout yet the count
 * starts from last week instead of breaking: a streak ends only when a whole
 * week went by without one.
 */
fun weeklyStreak(workoutDates: Collection<LocalDate>, today: LocalDate): Int {
    val weeks = workoutDates.mapTo(HashSet()) { weekStart(it) }
    var week = weekStart(today)
    if (week !in weeks) week = week.minus(1, DateTimeUnit.WEEK)
    var streak = 0
    while (week in weeks) {
        streak++
        week = week.minus(1, DateTimeUnit.WEEK)
    }
    return streak
}

/** [weeklyStreak] over the workouts that have a logged set in [sets]. */
fun weeklyStreak(sets: List<ProgressSet>, today: LocalDate, zone: TimeZone): Int =
    weeklyStreak(sets.filter { it.isLogged }.map { dateOf(it.startedAt, zone) }, today)
