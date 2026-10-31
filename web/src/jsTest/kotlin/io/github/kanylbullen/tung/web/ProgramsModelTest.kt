package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.ProgramMode
import io.github.kanylbullen.tung.core.sync.SyncProgram
import io.github.kanylbullen.tung.core.sync.SyncProgramDay
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.weekdaysToMask
import io.github.kanylbullen.tung.web.programs.programRows
import io.github.kanylbullen.tung.web.programs.todayView
import io.github.kanylbullen.tung.web.store.TungStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate

class ProgramsModelTest {

    private val monday = LocalDate(2026, 10, 26)

    private fun store(mode: ProgramMode, active: Boolean = true) = TungStore().apply {
        routines.put(SyncRoutine("push", "Push", updatedAt = 1), dirty = false)
        routines.put(SyncRoutine("pull", "Pull", updatedAt = 1), dirty = false)
        programs.put(SyncProgram("p", "PPL", mode.key, active, updatedAt = 1), dirty = false)
        programs.put(SyncProgram("q", "Another", ProgramMode.ROTATING.key, false, updatedAt = 1), dirty = false)
        programDays.put(SyncProgramDay("d1", "p", "push", 0, weekdaysToMask(setOf(1, 4)), updatedAt = 1), dirty = false)
        programDays.put(SyncProgramDay("d2", "p", "pull", 1, weekdaysToMask(setOf(2)), updatedAt = 1), dirty = false)
    }

    @Test
    fun theActiveProgramComesFirst() {
        assertEquals(listOf("PPL" to 2, "Another" to 0), programRows(store(ProgramMode.FIXED)).map { it.name to it.dayCount })
    }

    @Test
    fun fixedModeFollowsTheWeekdayAndRestsOtherwise() {
        assertEquals("Push", assertNotNull(todayView(store(ProgramMode.FIXED), monday)).day?.routineName)
        assertEquals("Pull", todayView(store(ProgramMode.FIXED), LocalDate(2026, 10, 27))?.day?.routineName)
        val wednesday = assertNotNull(todayView(store(ProgramMode.FIXED), LocalDate(2026, 10, 28)))
        assertNull(wednesday.day, "a rest day")
        assertEquals(2, wednesday.days.size)
    }

    @Test
    fun rotatingModeGoesOnFromTheLastFinishedDay() {
        val s = store(ProgramMode.ROTATING)
        assertEquals("d1", todayView(s, monday)?.day?.id)
        s.workouts.put(SyncWorkout("w", startedAt = 1, endedAt = 2, updatedAt = 1, programDayId = "d1"), dirty = false)
        assertEquals("d2", todayView(s, monday)?.day?.id)
        s.workouts.put(SyncWorkout("w2", startedAt = 3, endedAt = 4, updatedAt = 1, programDayId = "d2"), dirty = false)
        assertEquals("d1", todayView(s, monday)?.day?.id, "wraps to the first day")
    }

    @Test
    fun noActiveProgramNoToday() {
        assertNull(todayView(store(ProgramMode.FIXED, active = false), monday))
    }
}
