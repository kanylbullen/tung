package io.github.kanylbullen.tung.web

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.ExtendedName
import io.github.kanylbullen.tung.core.LoggedSet
import io.github.kanylbullen.tung.core.Muscle
import io.github.kanylbullen.tung.core.catalog.ExercisePageRef
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.extendedExerciseId
import io.github.kanylbullen.tung.core.sync.SyncExercise
import io.github.kanylbullen.tung.core.sync.SyncSet
import io.github.kanylbullen.tung.core.sync.SyncWorkout
import io.github.kanylbullen.tung.core.sync.SyncWorkoutExercise
import io.github.kanylbullen.tung.web.catalog.WebCatalogue
import io.github.kanylbullen.tung.web.exercises.browseItemsOf
import io.github.kanylbullen.tung.web.exercises.exercisePage
import io.github.kanylbullen.tung.web.store.TungStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExercisesModelTest {

    private val bench = catalogExerciseId("bench_press")
    private val catalogue = WebCatalogue(
        listOf(CoreExercise("bench_press", mapOf("en" to "Bench press"), "chest", "barbell", listOf("chest"), listOf("triceps"), mapOf("en" to listOf("Lie down.", "Press.")))),
        listOf(
            ExtendedExercise(id = "Zercher_Squats", name = ExtendedName("Zercher Squats"), muscleGroup = "legs", equipment = "barbell", primaryMuscles = listOf("quads"), source = "test"),
            ExtendedExercise(id = "Picked_Row", name = ExtendedName("Picked Row"), muscleGroup = "back", equipment = "cable", primaryMuscles = listOf("lats"), source = "test"),
        ),
    )

    private fun store() = TungStore().apply {
        exercises.put(SyncExercise(bench, "bench_press", "Bench press", "chest", "barbell", false, null, 0), dirty = false)
        exercises.put(SyncExercise(extendedExerciseId("Picked_Row"), null, "Picked Row", "back", "cable", false, null, 1), dirty = false)
        workouts.put(SyncWorkout("w1", startedAt = 1000, endedAt = 2000, updatedAt = 1), dirty = false)
        workoutExercises.put(SyncWorkoutExercise("we1", "w1", bench, 0, 1), dirty = false)
        sets.put(SyncSet("s1", "we1", 0, 80.0, 8, true, 1), dirty = false)
        sets.put(SyncSet("s2", "we1", 1, 85.0, 5, true, 1), dirty = false)
    }

    @Test
    fun theTabListsTheStoreThenTheExtendedEntriesNotPicked() {
        val items = browseItemsOf(store(), catalogue) { it.name ?: "" }
        assertEquals(listOf("Bench press", "Picked Row", "Zercher Squats"), items.map { it.name })
        assertEquals(ExercisePageRef.Extended("Zercher_Squats"), items.last().ref)
        assertEquals(Equipment.CABLE, items[1].equipment)
    }

    @Test
    fun aPageWithMusclesHistoryAndInstructions() {
        val page = assertNotNull(exercisePage(store(), catalogue, ExercisePageRef.Library(bench), "sv") { it.name ?: "" })
        assertEquals(listOf(Muscle.CHEST), page.muscles?.primary)
        assertEquals(listOf("Lie down.", "Press."), page.instructions, "Swedish falls back to English")
        assertEquals(1, page.history.sessions)
        assertEquals(LoggedSet(80.0, 8), page.history.best)
        assertEquals(bench, page.exerciseId)
    }

    @Test
    fun anExtendedEntryIsItsRowOncePicked() {
        val picked = assertNotNull(exercisePage(store(), catalogue, ExercisePageRef.Extended("Picked_Row"), "en") { it.name ?: "" })
        assertEquals(extendedExerciseId("Picked_Row"), picked.exerciseId)
        val notPicked = assertNotNull(exercisePage(store(), catalogue, ExercisePageRef.Extended("Zercher_Squats"), "en") { "" })
        assertNull(notPicked.exerciseId)
        assertEquals("Zercher Squats", notPicked.name)
        assertNull(exercisePage(store(), catalogue, ExercisePageRef.Extended("Nope"), "en") { "" })
    }
}
