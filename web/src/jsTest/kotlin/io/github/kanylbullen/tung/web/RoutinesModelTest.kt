package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncRoutineExercise
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.web.routines.routineRows
import io.github.kanylbullen.tung.web.routines.routineView
import io.github.kanylbullen.tung.web.routines.workoutInProgress
import io.github.kanylbullen.tung.web.store.TungStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutinesModelTest {

    private fun store() = TungStore().apply {
        exercises.put(SyncExercise("ex-bench", "bench_press", "Bench press", "chest", "barbell", false, null, 0), dirty = false)
        routines.put(SyncRoutine("r1", "Push", lastUsedAt = 10, updatedAt = 1), dirty = false)
        routines.put(SyncRoutine("r2", "Pull", lastUsedAt = null, updatedAt = 1), dirty = false)
        routines.put(SyncRoutine("r3", "Legs", lastUsedAt = 20, updatedAt = 1), dirty = false)
        routineExercises.put(SyncRoutineExercise("re2", "r1", "ex-bench", 1, 3, null, 1), dirty = false)
        routineExercises.put(SyncRoutineExercise("re1", "r1", "ex-bench", 0, 4, 8, 1), dirty = false)
    }

    @Test
    fun routinesByLastUseWithTheirExercisesInOrder() {
        val store = store()
        assertEquals(listOf("r3", "r1", "r2"), routineRows(store).map { it.id }, "most recently used first, never used last")
        assertEquals(2, routineRows(store).single { it.id == "r1" }.exerciseCount)
        val push = assertNotNull(routineView(store, "r1") { it.name ?: "" })
        assertEquals(listOf("re1", "re2"), push.exercises.map { it.id })
        assertEquals(4 to 8, push.exercises.first().let { it.targetSets to it.targetReps })
        assertNull(routineView(store, "gone") { "" })
    }

    @Test
    fun aWorkoutInProgressBlocksStart() {
        val store = store()
        assertFalse(workoutInProgress(store))
        store.workouts.put(SyncWorkout("w", startedAt = 1, endedAt = null, updatedAt = 1), dirty = false)
        assertTrue(workoutInProgress(store))
    }
}
