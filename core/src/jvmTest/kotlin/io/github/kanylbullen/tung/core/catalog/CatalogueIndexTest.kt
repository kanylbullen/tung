package io.github.kanylbullen.tung.core.catalog

import io.github.kanylbullen.tung.core.CoreExercise
import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.ExtendedExercise
import io.github.kanylbullen.tung.core.ExtendedName
import io.github.kanylbullen.tung.core.Muscle
import io.github.kanylbullen.tung.core.MuscleGroup
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.extendedExerciseId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogueIndexTest {

    private val bench = CoreExercise(
        key = "bench_press",
        name = mapOf("en" to "Bench press", "sv" to "Bänkpress"),
        muscleGroup = "chest",
        equipment = "barbell",
        primaryMuscles = listOf("chest"),
        secondaryMuscles = listOf("triceps", "front_delts"),
        instructions = mapOf("en" to listOf("Lie back.", "Press.")),
    )
    private val glute = CoreExercise("glute_bridge", mapOf("en" to "Glute bridge"), "glutes", "bodyweight", listOf("glutes"))
    private val zercher = ExtendedExercise(
        "Zercher_Squats", ExtendedName("Zercher Squats"), "legs", listOf("quads"), listOf("glutes"), "barbell",
        listOf("Hold the bar in your elbows.", "Squat."), "free-exercise-db",
    )

    private val index = CatalogueIndex.build(listOf(bench, glute), listOf(zercher))

    @Test
    fun aCoreEntryByItsIdWithMusclesAndInstructions() {
        val entry = index.byExerciseId(catalogExerciseId("bench_press"))
        assertEquals("bench_press", entry?.key)
        assertEquals(MuscleGroup.CHEST, entry?.muscleGroup)
        assertEquals(Equipment.BARBELL, entry?.equipment)
        assertEquals(listOf(Muscle.CHEST), entry?.muscles?.primary)
        assertEquals(listOf(Muscle.TRICEPS, Muscle.FRONT_DELTS), entry?.muscles?.secondary)
        assertEquals(listOf("Lie back.", "Press."), entry?.instructionsIn("en"))
        // Swedish falls back to English until the catalogue has it.
        assertEquals(listOf("Lie back.", "Press."), entry?.instructionsIn("sv"))
        assertEquals(emptyList<String>(), index.byExerciseId(catalogExerciseId("glute_bridge"))?.instructionsIn("en"))
    }

    @Test
    fun anExtendedEntryByFedIdByRoomIdAndByPageRef() {
        val entry = index.byFedId("Zercher_Squats")
        assertEquals("Zercher_Squats", entry?.fedId)
        assertEquals(entry, index.byExerciseId(extendedExerciseId("Zercher_Squats")))
        assertEquals(entry, index.of(ExercisePageRef.Extended("Zercher_Squats")))
        assertEquals(entry, index.of(ExercisePageRef.Library(extendedExerciseId("Zercher_Squats"))))
        assertEquals("Zercher Squats", entry?.englishName)
        assertEquals(2, entry?.instructionsIn("sv")?.size)
    }

    @Test
    fun customExercisesAreNotInIt() {
        assertNull(index.byExerciseId("a-custom-uuid"))
        assertNull(CatalogueIndex.EMPTY.byFedId("Zercher_Squats"))
        assertEquals(3, index.size)
    }
}
