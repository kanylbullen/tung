package io.github.kanylbullen.tung.core

import io.github.kanylbullen.tung.core.command.ExerciseRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusclesTest {

    @Test
    fun theFixedListHasTwentyOneMusclesWithUniqueKeys() {
        assertEquals(21, Muscle.entries.size)
        assertEquals(21, Muscle.entries.map { it.key }.toSet().size)
        Muscle.entries.forEach { assertEquals(it, Muscle.fromKey(it.key)) }
        assertNull(Muscle.fromKey("shoulders"))
    }

    /** Every muscle has a display group, and fullbody is never one: it has no muscle of its own. */
    @Test
    fun muscleGroupOfCoversEveryMuscle() {
        val groups = Muscle.entries.map { MuscleGroup.of(it) }.toSet()
        assertEquals(MuscleGroup.entries.toSet() - MuscleGroup.FULLBODY, groups)
        assertEquals(MuscleGroup.BACK, MuscleGroup.of(Muscle.LATS))
        assertEquals(MuscleGroup.SHOULDERS, MuscleGroup.of(Muscle.REAR_DELTS))
        assertEquals(MuscleGroup.LEGS, MuscleGroup.of(Muscle.HAMSTRINGS))
        assertEquals(MuscleGroup.CORE, MuscleGroup.of(Muscle.OBLIQUES))
    }

    @Test
    fun fullbodyMatchesAnyFirstMuscleAndOtherGroupsOnlyTheirOwn() {
        assertTrue(muscleGroupMatches("fullbody", Muscle.QUADS))
        assertTrue(muscleGroupMatches("legs", Muscle.QUADS))
        assertFalse(muscleGroupMatches("glutes", Muscle.QUADS))
    }

    @Test
    fun theIndexAnswersIdsKeysExtendedIdsAndRefs() {
        val core = listOf(
            CoreExercise("bench_press", mapOf("en" to "Bench press"), "chest", "barbell", listOf("chest"), listOf("triceps", "front_delts")),
            CoreExercise("odd", mapOf("en" to "Odd"), "chest", "barbell", listOf("wings"), emptyList()),
        )
        val extended = listOf(
            ExtendedExercise("Zercher_Squats", ExtendedName("Zercher Squats"), "legs", listOf("quads"), listOf("glutes", "quads", "wings"), "barbell", source = "x"),
        )
        val index = MuscleIndex.build(core, extended)
        val bench = ExerciseMuscles(listOf(Muscle.CHEST), listOf(Muscle.TRICEPS, Muscle.FRONT_DELTS))
        assertEquals(bench, index.musclesOf("bench_press"))
        assertEquals(bench, index.musclesOf(catalogExerciseId("bench_press")))
        assertEquals(bench, index.musclesOf(ExerciseRef.ByKey("bench_press")))
        assertEquals(bench, index.musclesOf(ExerciseRef.ById(catalogExerciseId("bench_press"))))
        // Unknown keys are skipped, and a muscle that is primary is not also secondary.
        val zercher = ExerciseMuscles(listOf(Muscle.QUADS), listOf(Muscle.GLUTES))
        assertEquals(zercher, index.musclesOf("Zercher_Squats"))
        assertEquals(zercher, index.musclesOf(ExerciseRef.Extended("Zercher_Squats")))
        // An entry with no known primary muscle is left out; names and custom exercises have none.
        assertNull(index.musclesOf("odd"))
        assertNull(index.musclesOf(ExerciseRef.ByName("Bench press")))
        assertNull(index.musclesOf(newId()))
        assertEquals(MuscleGroup.CHEST, index.musclesOf("bench_press")?.group)
    }
}
