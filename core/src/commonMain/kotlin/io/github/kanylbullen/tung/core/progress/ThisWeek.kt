package io.github.kanylbullen.tung.core.progress

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus

/** One day of the current week: the finished workouts that started on it and what they logged. */
data class WeekDay(val date: LocalDate, val workouts: Int, val sets: Int, val volumeKg: Double)

/**
 * Home's "This week": the ISO week [today] is in, Monday first, every day
 * present (an empty one as zeros), its totals, and whether it is the best of
 * the last [OVERVIEW_WEEKS] weeks by volume ([bestOfRecentWeeks]: strictly
 * more than every earlier week in that span, and at least one of them had
 * a workout, so an empty week is never best, a tie is not news, and the
 * first week ever has nothing to beat). [lastWeek] is the week before, for
 * "last week 2 430 kg" under a week with nothing in it yet.
 */
data class ThisWeek(
    val start: LocalDate,
    val days: List<WeekDay>,
    val workouts: Int,
    val sets: Int,
    val volumeKg: Double,
    val bestOfRecentWeeks: Boolean,
    val lastWeek: WeekBucket,
)

/** [ThisWeek] of [today] from every counted set ([ProgressSet.isLogged]) of finished workouts; dates in [zone]. */
fun thisWeek(sets: List<ProgressSet>, today: LocalDate, zone: TimeZone): ThisWeek {
    val start = weekStart(today)
    val end = start.plus(6, DateTimeUnit.DAY)
    val inWeek = sets.filter { it.isLogged }
        .map { it to dateOf(it.startedAt, zone) }
        .filter { (_, date) -> date >= start && date <= end }
    val byDay = inWeek.groupBy({ it.second }, { it.first })
    val days = (0..6).map { offset ->
        val date = start.plus(offset, DateTimeUnit.DAY)
        val rows = byDay[date].orEmpty()
        WeekDay(date, rows.mapTo(HashSet()) { it.workoutId }.size, rows.size, rows.sumOf { it.set.volume })
    }
    val week = inWeek.map { it.first }
    val volume = week.sumOf { it.set.volume }
    val earlier = weeklyBuckets(sets, today, zone).dropLast(1)
    return ThisWeek(
        start = start,
        days = days,
        workouts = week.mapTo(HashSet()) { it.workoutId }.size,
        sets = week.size,
        volumeKg = volume,
        bestOfRecentWeeks = volume > 0.0 && earlier.any { it.volumeKg > 0.0 } && earlier.all { it.volumeKg < volume },
        lastWeek = earlier.lastOrNull() ?: WeekBucket(start.minus(1, DateTimeUnit.WEEK), 0, 0.0),
    )
}
