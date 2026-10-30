package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncSet
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.SyncWorkoutExercise
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.web.workout.activeWorkout
import io.github.kanylbullen.tung.web.workout.pickerRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkoutModelTest {

    private val names = mapOf("bench_press" to mapOf("en" to "Bench press", "sv" to "Bänkpress"))
    private fun display(e: SyncExercise) = e.nameKey?.let { names[it]?.get("sv") } ?: e.name ?: e.id

    private fun store() = TungStore().apply {
        exercises.put(SyncExercise("ex-bench", "bench_press", "Bench press", "chest", "barbell", false, null, 0), dirty = false)
        exercises.put(SyncExercise("ex-row", null, "Cable row", "back", "cable", true, null, 1), dirty = false)
        // Last session: bench 80 × 8 ticked, 85 × 3 not ticked.
        workouts.put(SyncWorkout("w0", startedAt = 1_000, endedAt = 2_000, updatedAt = 1), dirty = false)
        workoutExercises.put(SyncWorkoutExercise("we0", "w0", "ex-bench", 0, 1), dirty = false)
        sets.put(SyncSet("s0", "we0", 0, 80.0, 8, true, 1), dirty = false)
        sets.put(SyncSet("s0b", "we0", 1, 85.0, 3, false, 1), dirty = false)
        // In progress, from a routine: bench with two sets, one ticked.
        routines.put(SyncRoutine("r1", "Gym · Push", null, 1), dirty = false)
        workouts.put(SyncWorkout("w1", startedAt = 5_000, endedAt = null, routineId = "r1", updatedAt = 1), dirty = false)
        workoutExercises.put(SyncWorkoutExercise("we1", "w1", "ex-bench", 0, 1), dirty = false)
        sets.put(SyncSet("s2", "we1", 1, 82.5, 6, false, 1), dirty = false)
        sets.put(SyncSet("s1", "we1", 0, 80.0, 8, true, 1), dirty = false)
    }

    @Test
    fun theWorkoutInProgressWithItsSetsAndLastTime() {
        val workout = assertNotNull(activeWorkout(store(), ::display))
        assertEquals("w1", workout.id)
        assertEquals("Gym · Push", workout.routineName)
        val bench = workout.exercises.single()
        assertEquals("Bänkpress", bench.name)
        assertEquals(listOf("s1", "s2"), bench.sets.map { it.id }, "in position order")
        assertEquals(listOf(1, 2), bench.sets.map { it.number })
        assertEquals(listOf(LoggedSet(80.0, 8)), bench.last, "last session's ticked sets only")
        assertEquals(1, workout.loggedSets)
        assertEquals(1, workout.openSets)
        assertNull(activeWorkout(TungStore(), ::display))
    }

    @Test
    fun thePickerSearchesTheNameOnScreen() {
        val all = pickerRows(store(), "", ::display, naturalOrder())
        assertEquals(listOf("Bänkpress", "Cable row"), all.map { it.name })
        assertEquals(listOf("ex-bench"), pickerRows(store(), "bank", ::display, naturalOrder()).map { it.id }, "without the diacritic")
        assertEquals(emptyList(), pickerRows(store(), "squat", ::display, naturalOrder()))
    }
}
