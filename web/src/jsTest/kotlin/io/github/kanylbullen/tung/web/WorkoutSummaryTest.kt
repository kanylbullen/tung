package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.Muscle
import io.github.kanylbullen.tung.core.MuscleIndex
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncSet
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.SyncWorkoutExercise
import io.github.kanylbullen.tung.web.store.TungStore
import io.github.kanylbullen.tung.web.summary.workoutSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkoutSummaryTest {

    private val bench = catalogExerciseId("bench_press")
    private val muscles = MuscleIndex.build(
        listOf(CoreExercise("bench_press", mapOf("en" to "Bench press"), "chest", "barbell", listOf("chest"), listOf("front_delts", "triceps"))),
        emptyList(),
    )

    private fun store(imported: Boolean = false) = TungStore().apply {
        exercises.put(SyncExercise(bench, "bench_press", "Bench press", "chest", "barbell", false, null, 0), dirty = false)
        exercises.put(SyncExercise("ex-own", null, "My row", "back", "cable", true, null, 1), dirty = false)
        // An earlier workout: bench at 80 at most.
        workouts.put(SyncWorkout("w0", startedAt = 0, endedAt = 1000, updatedAt = 1), dirty = false)
        workoutExercises.put(SyncWorkoutExercise("we0", "w0", bench, 0, 1), dirty = false)
        sets.put(SyncSet("s0", "we0", 0, 80.0, 5, true, 1), dirty = false)
        // This one: bench at 82.5 and two sets of a custom exercise.
        workouts.put(SyncWorkout("w1", startedAt = 10_000, endedAt = 20_000, updatedAt = 1, importSource = if (imported) "jefit" else null), dirty = false)
        workoutExercises.put(SyncWorkoutExercise("we1", "w1", bench, 0, 1), dirty = false)
        workoutExercises.put(SyncWorkoutExercise("we2", "w1", "ex-own", 1, 1), dirty = false)
        sets.put(SyncSet("s1", "we1", 0, 82.5, 5, true, 1), dirty = false)
        sets.put(SyncSet("s2", "we1", 1, 80.0, 5, true, 1), dirty = false)
        sets.put(SyncSet("s3", "we2", 0, 40.0, 10, true, 1), dirty = false)
        sets.put(SyncSet("s4", "we2", 1, 40.0, 10, true, 1), dirty = false)
    }

    @Test
    fun recordsMusclesAndCharts() {
        val summary = assertNotNull(workoutSummary(store(), "w1", muscles) { it.name ?: "" })
        assertEquals(listOf(true, false), summary.records, "82.5 beats 80; the custom exercise has no history")
        // 2 sets of bench: chest 2, front delts 1, triceps 1 → 50 %, 25 %, 25 %.
        assertEquals(listOf(Muscle.CHEST to 50, Muscle.FRONT_DELTS to 25, Muscle.TRICEPS to 25), summary.muscles.muscles.map { it.muscle to it.percent })
        assertEquals(2, summary.muscles.uncountedSets)
        assertEquals(listOf(bench, "ex-own"), summary.chartExerciseIds)
    }

    @Test
    fun theFirstWorkoutAndAnImportedOneSetNoRecord() {
        assertEquals(listOf(false), assertNotNull(workoutSummary(store(), "w0", muscles) { "" }).records)
        assertEquals(listOf(false, false), assertNotNull(workoutSummary(store(imported = true), "w1", muscles) { "" }).records)
        assertNull(workoutSummary(store(), "nope", muscles) { "" })
    }
}
