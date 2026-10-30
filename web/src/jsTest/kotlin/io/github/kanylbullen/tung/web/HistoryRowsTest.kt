package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncRoutine
import io.github.kanylbullen.tung.core.sync.SyncSet
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.SyncWorkoutExercise
import io.github.kanylbullen.tung.web.history.exerciseDisplayName
import io.github.kanylbullen.tung.web.history.historyRows
import io.github.kanylbullen.tung.web.store.TungStore
import kotlin.test.Test
import kotlin.test.assertEquals

class HistoryRowsTest {

    private val names = mapOf("bench_press" to mapOf("en" to "Bench press", "sv" to "Bänkpress"))

    private fun store(): TungStore = TungStore().apply {
        exercises.put(SyncExercise("ex-bench", "bench_press", "Bench press", "chest", "barbell", false, null, 0), dirty = false)
        exercises.put(SyncExercise("ex-custom", null, "Farmer's walk", "fullbody", "other", true, null, 1), dirty = false)
        routines.put(SyncRoutine("r1", "Gym · Day 1", null, 1), dirty = false)
        // Newest, from a routine: 80 × 8 logged, 100 × 0 ticked but no reps (not counted), 90 × 5 unticked.
        workouts.put(SyncWorkout("w2", startedAt = 20_000, endedAt = 20_000 + 47 * 60_000, routineId = "r1", updatedAt = 1), dirty = false)
        workoutExercises.put(SyncWorkoutExercise("we2", "w2", "ex-bench", 0, 1), dirty = false)
        sets.put(SyncSet("s1", "we2", 0, 80.0, 8, true, 1), dirty = false)
        sets.put(SyncSet("s2", "we2", 1, 100.0, 0, true, 1), dirty = false)
        sets.put(SyncSet("s3", "we2", 2, 90.0, 5, false, 1), dirty = false)
        // Older, no routine: two exercises, the second one first by position.
        workouts.put(SyncWorkout("w1", startedAt = 10_000, endedAt = 12_000, updatedAt = 1), dirty = false)
        workoutExercises.put(SyncWorkoutExercise("we1a", "w1", "ex-custom", 1, 1), dirty = false)
        workoutExercises.put(SyncWorkoutExercise("we1b", "w1", "ex-bench", 0, 1), dirty = false)
        sets.put(SyncSet("s4", "we1a", 0, 0.0, 10, true, 1), dirty = false)
        // In progress: not history.
        workouts.put(SyncWorkout("w3", startedAt = 30_000, endedAt = null, updatedAt = 1), dirty = false)
    }

    @Test
    fun finishedWorkoutsNewestFirstWithTheirNumbers() {
        val rows = historyRows(store()) { exerciseDisplayName(it, names, "sv") }
        assertEquals(listOf("w2", "w1"), rows.map { it.id })
        val newest = rows[0]
        assertEquals(640.0, newest.volumeKg, "only the logged set counts")
        assertEquals(47 * 60_000L, newest.durationMillis)
        assertEquals(1, newest.exerciseCount)
        assertEquals("Gym · Day 1", newest.routineName)
        val older = rows[1]
        assertEquals(null, older.routineName)
        assertEquals(listOf("Bänkpress", "Farmer's walk"), older.exerciseNames, "in position order, named in the app's language")
        assertEquals(0.0, older.volumeKg, "bodyweight at 0 kg contributes nothing, by design")
    }

    @Test
    fun aCatalogueNameFallsBackToEnglishThenToTheStoredName() {
        val bench = SyncExercise("ex-bench", "bench_press", "Bench press", "chest", "barbell", false, null, 0)
        assertEquals("Bench press", exerciseDisplayName(bench, names, "de"))
        assertEquals("Bänkpress", exerciseDisplayName(bench, names, "sv"))
        val unknownKey = bench.copy(nameKey = "new_lift", name = "New lift")
        assertEquals("New lift", exerciseDisplayName(unknownKey, names, "sv"))
    }
}
