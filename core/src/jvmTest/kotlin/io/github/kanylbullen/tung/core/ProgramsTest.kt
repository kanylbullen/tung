package io.github.kanylbullen.tung.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

class ProgramsTest {

    // 2026-10-19 is a Monday.
    private val monday = LocalDate(2026, 10, 19)
    private val wednesday = monday.plus(2, DateTimeUnit.DAY)
    private val sunday = monday.plus(6, DateTimeUnit.DAY)

    private val push = PlanDay("push", position = 0, weekdays = setOf(1, 4))
    private val pull = PlanDay("pull", position = 1, weekdays = setOf(2, 5))
    private val legs = PlanDay("legs", position = 2, weekdays = setOf(3))

    // --- fixed ---

    @Test
    fun fixedSuggestsTheDayWithTodaysWeekday() {
        assertEquals(push, suggestedDay(ProgramMode.FIXED, listOf(push, pull, legs), monday, null))
        assertEquals(legs, suggestedDay(ProgramMode.FIXED, listOf(push, pull, legs), wednesday, null))
        assertEquals(push, suggestedDay(ProgramMode.FIXED, listOf(push, pull, legs), monday.plus(3, DateTimeUnit.DAY), null))
    }

    @Test
    fun fixedWithNoDayTodayIsARestDay() {
        assertNull(suggestedDay(ProgramMode.FIXED, listOf(push, pull, legs), sunday, null))
    }

    @Test
    fun fixedIgnoresWhatWasCompleted() {
        assertEquals(push, suggestedDay(ProgramMode.FIXED, listOf(push, pull, legs), monday, "push"))
    }

    @Test
    fun fixedWithTwoDaysOnOneWeekdayTakesTheFirstByPosition() {
        val alsoMonday = PlanDay("extra", position = 3, weekdays = setOf(1))
        assertEquals(push, suggestedDay(ProgramMode.FIXED, listOf(alsoMonday, legs, push), monday, null))
    }

    @Test
    fun fixedWithADayWithoutWeekdaysNeverSuggestsIt() {
        val unplanned = PlanDay("unplanned", position = 0)
        assertNull(suggestedDay(ProgramMode.FIXED, listOf(unplanned), monday, null))
    }

    // --- rotating ---

    @Test
    fun rotatingStartsWithTheFirstDay() {
        assertEquals(push, suggestedDay(ProgramMode.ROTATING, listOf(legs, pull, push), sunday, null))
    }

    @Test
    fun rotatingSuggestsTheDayAfterTheLastCompleted() {
        assertEquals(pull, suggestedDay(ProgramMode.ROTATING, listOf(push, pull, legs), monday, "push"))
        assertEquals(legs, suggestedDay(ProgramMode.ROTATING, listOf(push, pull, legs), monday, "pull"))
    }

    @Test
    fun rotatingWrapsFromTheLastDayToTheFirst() {
        assertEquals(push, suggestedDay(ProgramMode.ROTATING, listOf(push, pull, legs), monday, "legs"))
    }

    @Test
    fun rotatingIgnoresTheDate() {
        listOf(monday, wednesday, sunday).forEach { date ->
            assertEquals(pull, suggestedDay(ProgramMode.ROTATING, listOf(push, pull, legs), date, "push"))
        }
    }

    @Test
    fun rotatingWithTheCompletedDayGoneStartsOver() {
        assertEquals(push, suggestedDay(ProgramMode.ROTATING, listOf(push, pull, legs), monday, "deleted"))
    }

    @Test
    fun rotatingWithOneDayAlwaysSuggestsIt() {
        assertEquals(push, suggestedDay(ProgramMode.ROTATING, listOf(push), monday, "push"))
    }

    @Test
    fun rotatingFollowsPositionNotListOrder() {
        // Positions with a gap, out of order: the order is legs(2) < push(5) < pull(9).
        val a = PlanDay("push", 5)
        val b = PlanDay("pull", 9)
        val c = PlanDay("legs", 2)
        assertEquals(b, suggestedDay(ProgramMode.ROTATING, listOf(a, b, c), monday, "push"))
        assertEquals(c, suggestedDay(ProgramMode.ROTATING, listOf(a, b, c), monday, "pull"))
    }

    // --- no days ---

    @Test
    fun aProgramWithNoDaysSuggestsNothing() {
        assertNull(suggestedDay(ProgramMode.FIXED, emptyList(), monday, null))
        assertNull(suggestedDay(ProgramMode.ROTATING, emptyList(), monday, null))
        assertNull(suggestedDay(ProgramMode.ROTATING, emptyList(), monday, "push"))
    }

    // --- labels ---

    @Test
    fun rotatingLabelCountsFromOne() {
        assertEquals(DayOfCount(1, 3), rotatingLabel(0, 3))
        assertEquals(DayOfCount(2, 3), rotatingLabel(1, 3))
        assertEquals(DayOfCount(3, 3), rotatingLabel(2, 3))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rotatingLabelRejectsAPositionPastTheEnd() {
        rotatingLabel(3, 3)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rotatingLabelRejectsNoDays() {
        rotatingLabel(0, 0)
    }

    // --- mode and weekdays ---

    @Test
    fun modeKeysRoundTrip() {
        ProgramMode.entries.forEach { assertEquals(it, ProgramMode.fromKey(it.key)) }
        assertEquals(ProgramMode.ROTATING, ProgramMode.fromKey("weekly"))
    }

    @Test
    fun weekdayMaskRoundTrips() {
        assertEquals(0, weekdaysToMask(emptySet()))
        assertEquals(0b1, weekdaysToMask(setOf(1)))
        assertEquals(0b1000000, weekdaysToMask(setOf(7)))
        assertEquals(setOf(1, 3, 7), maskToWeekdays(weekdaysToMask(setOf(7, 3, 1))))
        assertEquals(ISO_WEEKDAYS.toSet(), maskToWeekdays(weekdaysToMask(ISO_WEEKDAYS.toSet())))
        assertEquals(weekdaysToMask(setOf(2)), weekdaysToMask(setOf(0, 2, 8)))
    }

    @Test
    fun toggleWeekdaySwitchesOneBit() {
        val mondayOnly = toggleWeekday(0, 1)
        assertEquals(setOf(1), maskToWeekdays(mondayOnly))
        assertEquals(setOf(1, 5), maskToWeekdays(toggleWeekday(mondayOnly, 5)))
        assertEquals(emptySet<Int>(), maskToWeekdays(toggleWeekday(mondayOnly, 1)))
    }
}
