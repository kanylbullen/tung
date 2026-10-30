package io.github.kanylbullen.tung.core.progress

import io.github.kanylbullen.tung.core.LoggedSet
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThisWeekTest {

    private val zone: TimeZone = TimeZone.of("Europe/Stockholm")

    /** Thursday 29 October 2026; its week runs from Monday the 26th. */
    private val today: LocalDate = LocalDate(2026, 10, 29)

    private fun at(date: LocalDate, hour: Int = 18): Long = date.atTime(hour, 0).toInstant(zone).toEpochMilliseconds()

    private fun LocalDate.plusDays(days: Int): LocalDate = plus(days, DateTimeUnit.DAY)

    private fun set(workout: String, startedAt: Long, kg: Double, reps: Int, position: Int = 0, exercise: String = "bench", completed: Boolean = true) =
        ProgressSet(exercise, workout, "$workout-$exercise", startedAt, 0, position, LoggedSet(kg, reps), completed)

    @Test
    fun `every day of the week is there, Monday first, with its workouts, sets and volume`() {
        val monday = LocalDate(2026, 10, 26)
        val sets = listOf(
            set("w1", at(monday), 80.0, 8),
            set("w1", at(monday), 80.0, 8, position = 1),
            set("w2", at(monday, hour = 7), 60.0, 10, exercise = "squat"),
            set("w3", at(monday.plusDays(2)), 100.0, 5),
        )
        val week = thisWeek(sets, today, zone)
        assertEquals(monday, week.start)
        assertEquals(7, week.days.size)
        assertEquals((0..6).map { monday.plusDays(it) }, week.days.map { it.date })
        assertEquals(DayOfWeek.MONDAY, week.days.first().date.dayOfWeek)
        assertEquals(WeekDay(monday, workouts = 2, sets = 3, volumeKg = 1880.0), week.days[0])
        assertEquals(WeekDay(monday.plusDays(1), 0, 0, 0.0), week.days[1])
        assertEquals(WeekDay(monday.plusDays(2), 1, 1, 500.0), week.days[2])
        assertEquals(3, week.workouts)
        assertEquals(4, week.sets)
        assertEquals(2380.0, week.volumeKg, 0.0)
    }

    @Test
    fun `sets outside the week and sets that are not logged do not count`() {
        val sunday = LocalDate(2026, 10, 25)
        val nextMonday = LocalDate(2026, 11, 2)
        val sets = listOf(
            set("old", at(sunday, hour = 23), 80.0, 8),
            set("next", at(nextMonday, hour = 0), 80.0, 8),
            set("open", at(today), 80.0, 8, completed = false),
            set("zero", at(today), 80.0, 0),
            set("ok", at(today), 50.0, 10),
        )
        val week = thisWeek(sets, today, zone)
        assertEquals(1, week.workouts)
        assertEquals(1, week.sets)
        assertEquals(500.0, week.volumeKg, 0.0)
        assertEquals(500.0, week.days[3].volumeKg, 0.0)
    }

    @Test
    fun `the week's date is read in the zone, not UTC`() {
        // 23:30 Sunday in Stockholm is 22:30 UTC, still Sunday; 00:30 Monday in Stockholm is 23:30 UTC Sunday.
        val monday = LocalDate(2026, 10, 26)
        val lateSunday = monday.minus(1, DateTimeUnit.DAY).atTime(23, 30).toInstant(zone).toEpochMilliseconds()
        val earlyMonday = monday.atTime(0, 30).toInstant(zone).toEpochMilliseconds()
        val week = thisWeek(listOf(set("a", lateSunday, 10.0, 1), set("b", earlyMonday, 20.0, 1)), today, zone)
        assertEquals(1, week.workouts)
        assertEquals(20.0, week.days[0].volumeKg, 0.0)
    }

    @Test
    fun `best of the recent weeks means strictly more volume than each of the eleven before`() {
        val lastWeek = LocalDate(2026, 10, 20)
        val sets = listOf(
            set("prev", at(lastWeek), 100.0, 10),
            set("now", at(today), 100.0, 10),
        )
        assertFalse("a tie is not best", thisWeek(sets, today, zone).bestOfRecentWeeks)
        assertEquals(WeekBucket(LocalDate(2026, 10, 19), 1, 1000.0), thisWeek(sets, today, zone).lastWeek)
        assertTrue(thisWeek(sets + set("now", at(today), 1.0, 1, position = 1), today, zone).bestOfRecentWeeks)
        assertFalse("an empty week is never best", thisWeek(emptyList(), today, zone).bestOfRecentWeeks)
        assertFalse("the first week ever has nothing to beat", thisWeek(listOf(set("now", at(today), 100.0, 10)), today, zone).bestOfRecentWeeks)
        // A week older than the span does not count against this one; a trained week inside it is what is beaten.
        val longAgo = today.minus(20, DateTimeUnit.WEEK)
        val spanned = listOf(set("ancient", at(longAgo), 500.0, 10), set("prev", at(lastWeek), 1.0, 1), set("now", at(today), 2.0, 1))
        assertTrue(thisWeek(spanned, today, zone).bestOfRecentWeeks)
    }

    @Test
    fun `an empty history is a week of zeros`() {
        val week = thisWeek(emptyList(), today, zone)
        assertEquals(0, week.workouts)
        assertEquals(0, week.sets)
        assertEquals(0.0, week.volumeKg, 0.0)
        assertTrue(week.days.all { it.workouts == 0 && it.sets == 0 && it.volumeKg == 0.0 })
        assertEquals(WeekBucket(LocalDate(2026, 10, 19), 0, 0.0), week.lastWeek)
    }
}
