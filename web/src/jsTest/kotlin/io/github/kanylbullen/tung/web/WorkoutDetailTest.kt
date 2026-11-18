package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncSet
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.SyncWorkoutExercise
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncRoutineExercise
import io.github.kanylbullen.tung.web.history.workoutDetail
import io.github.kanylbullen.tung.web.routines.RoutineSave
import io.github.kanylbullen.tung.web.routines.routineSave
import io.github.kanylbullen.tung.web.store.TungStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkoutDetailTest {

    private fun store() = TungStore().apply {
        exercises.put(SyncExercise("ex-bench", "bench_press", "Bench press", "chest", "barbell", false, null, 0), dirty = false)
        exercises.put(SyncExercise("ex-curl", null, "Curl", "arms", "dumbbell", true, null, 1), dirty = false)
        workouts.put(SyncWorkout("w1", startedAt = 0, endedAt = 3_000_000, updatedAt = 1, avgHeartRate = 128, maxHeartRate = 171), dirty = false)
        workoutExercises.put(SyncWorkoutExercise("we1", "w1", "ex-bench", 0, 1), dirty = false)
        workoutExercises.put(SyncWorkoutExercise("we2", "w1", "ex-curl", 1, 1), dirty = false)
        sets.put(SyncSet("s1", "we1", 0, 80.0, 8, true, 1), dirty = false)
        sets.put(SyncSet("s2", "we1", 1, 85.0, 5, true, 1), dirty = false)
        sets.put(SyncSet("s3", "we1", 2, 90.0, 0, true, 1), dirty = false)
        // An exercise with no logged set is left out.
        sets.put(SyncSet("s4", "we2", 0, 12.0, 10, false, 1), dirty = false)
        workouts.put(SyncWorkout("w2", startedAt = 5, endedAt = null, updatedAt = 1), dirty = false)
    }

    @Test
    fun aFinishedWorkoutWithItsLoggedSetsOnly() {
        val detail = assertNotNull(workoutDetail(store(), "w1") { it.name ?: "" })
        assertEquals(50 * 60_000L, detail.durationMillis)
        assertEquals(2, detail.sets)
        assertEquals(1065.0, detail.volumeKg)
        assertEquals(listOf("Bench press"), detail.exercises.map { it.name })
        val bench = detail.exercises.single()
        assertEquals(listOf(LoggedSet(80.0, 8), LoggedSet(85.0, 5)), bench.sets)
        assertEquals(LoggedSet(80.0, 8), bench.best, "weight × reps: 640 over 425")
        assertEquals(101.33, bench.e1rmKg?.let { kotlin.math.round(it * 100) / 100 }, "Epley's best: 80 × (1 + 8/30)")
        assertEquals(128, detail.avgHeartRate)
        assertNull(workoutDetail(store(), "w2") { "" }, "a workout in progress has no page")
        assertNull(workoutDetail(store(), "nope") { "" })
    }

    /** Issue #217: "w1" logged 2 sets of bench (curl had none); what it offers to save against its routine. */
    @Test
    fun whatAWorkoutOffersToSaveDependsOnItsRoutine() {
        val store = store()
        fun save() = routineSave(store, assertNotNull(workoutDetail(store, "w1") { it.name ?: "" }))
        assertEquals(RoutineSave.New, save(), "not started from a routine")

        store.workouts.put(SyncWorkout("w1", startedAt = 0, endedAt = 3_000_000, updatedAt = 2, routineId = "r1"), dirty = false)
        assertEquals(RoutineSave.New, save(), "its routine is gone")

        store.routines.put(SyncRoutine("r1", "Push", lastUsedAt = 0, updatedAt = 1), dirty = false)
        store.routineExercises.put(SyncRoutineExercise("re1", "r1", "ex-bench", 0, 2, 8, 1), dirty = false)
        assertEquals(RoutineSave.Matches, save(), "same exercises and set counts, whatever the weights")

        store.routineExercises.put(SyncRoutineExercise("re1", "r1", "ex-bench", 0, 3, 8, 2), dirty = false)
        assertEquals(RoutineSave.Differs("r1", "Push"), save(), "a set fewer than the routine asks")
    }
}
