package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncRoutineExercise
import io.github.kanylbullen.tung.core.sync.SyncSet
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.SyncWorkoutExercise
import io.github.kanylbullen.tung.web.home.homeModel
import io.github.kanylbullen.tung.web.home.progressSets
import io.github.kanylbullen.tung.web.store.TungStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

class HomeModelTest {

    // Wednesday 2026-10-28 10:00 UTC, and the Monday of the week before.
    private val wednesday = 1_793_181_600_000L
    private val lastWeekMonday = wednesday - 9 * 86_400_000L
    private val today = LocalDate(2026, 10, 30)

    private fun store() = TungStore().apply {
        exercises.put(SyncExercise("ex", "bench_press", "Bench press", "chest", "barbell", false, null, 0), dirty = false)
        workouts.put(SyncWorkout("w1", startedAt = wednesday, endedAt = wednesday + 3_600_000, routineId = "r1", updatedAt = 1), dirty = false)
        workoutExercises.put(SyncWorkoutExercise("we1", "w1", "ex", 0, 1), dirty = false)
        sets.put(SyncSet("s1", "we1", 0, 80.0, 8, true, 1), dirty = false)
        sets.put(SyncSet("s2", "we1", 1, 80.0, 6, false, 1), dirty = false)
        workouts.put(SyncWorkout("w0", startedAt = lastWeekMonday, endedAt = lastWeekMonday + 1, updatedAt = 1), dirty = false)
        workoutExercises.put(SyncWorkoutExercise("we0", "w0", "ex", 0, 1), dirty = false)
        sets.put(SyncSet("s0", "we0", 0, 100.0, 5, true, 1), dirty = false)
        routines.put(SyncRoutine("r1", "Gym · Push", lastUsedAt = wednesday, updatedAt = 1), dirty = false)
        routines.put(SyncRoutine("r2", "Pull", lastUsedAt = null, updatedAt = 1), dirty = false)
        routineExercises.put(SyncRoutineExercise("re1", "r1", "ex", 0, 3, null, 1), dirty = false)
        workouts.put(SyncWorkout("w2", startedAt = wednesday + 7_200_000, endedAt = null, routineId = "r2", startedBy = "phone", updatedAt = 1), dirty = false)
    }

    @Test
    fun thisWeekCountsOnlyLoggedSetsOfFinishedWorkouts() {
        val model = homeModel(store(), today, TimeZone.UTC) { it.name ?: "" }
        val week = assertNotNull(model.thisWeek)
        assertEquals(640.0, week.volumeKg)
        assertEquals(1, week.workouts)
        assertEquals(1, week.sets)
        assertEquals(500.0, week.lastWeek.volumeKg)
        assertEquals(true, week.bestOfRecentWeeks)
        assertEquals(640.0, week.days[2].volumeKg, "Wednesday, Monday first")
        assertEquals(3, progressSets(store()).size, "every set of a finished workout; core drops the unticked one")
    }

    @Test
    fun theWorkoutInProgressTheRoutinesAndTheRecent() {
        val model = homeModel(store(), today, TimeZone.UTC) { it.name ?: "" }
        val active = assertNotNull(model.inProgress)
        assertEquals("w2", active.id)
        assertEquals("Pull", active.routineName)
        assertEquals(listOf("r1", "r2"), model.routines.map { it.id }, "most recently used first")
        assertEquals(1, model.routines.first().exerciseCount)
        assertEquals(listOf("w1", "w0"), model.recent.map { it.id })
    }

    @Test
    fun anEmptyLogHasNoWeekAndNothingInProgress() {
        val model = homeModel(TungStore(), today, TimeZone.UTC) { it.name ?: "" }
        assertNull(model.thisWeek)
        assertNull(model.inProgress)
        assertEquals(emptyList(), model.routines)
    }
}
