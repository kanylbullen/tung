package io.github.kanylbullen.tung.core.importing

import io.github.kanylbullen.tung.core.Equipment
import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.extendedExerciseId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ImportSuggestionsTest {

    private val catalogue = ImportTestFiles.catalogueCandidates

    private fun top(name: String): List<ExerciseCandidate> = importSuggestions(name, catalogue)

    @Test
    fun theEquipmentIsReadOffTheName() {
        assertEquals(Equipment.DUMBBELL, inferEquipment("Dumbbell Deadlift"))
        assertEquals(Equipment.DUMBBELL, inferEquipment("Incline DB press"))
        assertEquals(Equipment.BARBELL, inferEquipment("barbell row"))
        assertEquals(Equipment.MACHINE, inferEquipment("Machine Lat Pulldown"))
        assertEquals(Equipment.MACHINE, inferEquipment("Smith Machine Squat"))
        assertEquals(Equipment.CABLE, inferEquipment("Cable Upper Row"))
        assertEquals(Equipment.KETTLEBELL, inferEquipment("Kettlebell Swing"))
        assertEquals(Equipment.BAND, inferEquipment("Banded pull-apart"))
        assertEquals(Equipment.BODYWEIGHT, inferEquipment("Bodyweight Single-Leg Calf Raise"))
        assertEquals(Equipment.BODYWEIGHT, inferEquipment("Body weight squat"))
        assertEquals(Equipment.EZ_BAR, inferEquipment("EZ-bar curl"))
        assertNull(inferEquipment("Heel Walking"))
        // Two loads named: no guess.
        assertNull(inferEquipment("Barbell or dumbbell row"))
        // A word that only starts like one is not one.
        assertNull(inferEquipment("Cablestation thing"))
    }

    @Test
    fun theEquipmentWordsAreNotMatchedAsWords() {
        assertEquals("deadlift", withoutEquipmentWords("Dumbbell Deadlift"))
        assertEquals("curl", withoutEquipmentWords("EZ Bar Curl"))
        assertEquals("squat", withoutEquipmentWords("Body Weight Squat"))
    }

    /** The issue's case: a dumbbell deadlift leads with the dumbbell one, not the barbell `deadlift`. */
    @Test
    fun theSameLoadComesFirst() {
        val suggestions = top("Dumbbell Deadlift")
        assertEquals(extendedExerciseId("Stiff-Legged_Dumbbell_Deadlift"), suggestions.first().id)
        assertNotEquals(catalogExerciseId("deadlift"), suggestions.first().id)
        assertTrue(suggestions.size <= 5)
        // Among equal scores, the dumbbell one first; the words still matter more than the load.
        val machine = top("Machine Lat Pulldown")
        assertEquals(catalogExerciseId("lat_pulldown"), machine.first().id)
    }

    @Test
    fun withoutEquipmentTheOrderIsMatchExerciseNames() {
        val a = ExerciseCandidate("a", listOf("Bench press"), equipment = Equipment.BARBELL)
        val b = ExerciseCandidate("b", listOf("Dumbbell bench press"), equipment = Equipment.DUMBBELL)
        assertEquals(listOf("a", "b"), importSuggestions("bench", listOf(b, a)).map { it.id })
        // "Dumbbell bench": both contain "bench", the dumbbell one first.
        assertEquals(listOf("b", "a"), importSuggestions("Dumbbell bench", listOf(a, b)).map { it.id })
        // A name that is only an equipment word is matched whole.
        assertEquals(listOf("b"), importSuggestions("Dumbbell", listOf(a, b)).map { it.id })
    }

    @Test
    fun theReviewGetsTheseSuggestions() = runBlocking {
        val match = resolveImportNames(listOf("Dumbbell Deadlift"), ImportTestFiles.jefitAliases, ImportTestFiles.resolver())
            .getValue("Dumbbell Deadlift") as ImportMatch.Unmatched
        assertEquals(extendedExerciseId("Stiff-Legged_Dumbbell_Deadlift"), match.suggestions.first().id)
    }

    /** Names from a real Jefit export that nothing matches; the top suggestions are printed for a person to judge. */
    @Test
    fun realUnmatchedNames() {
        val names = listOf(
            "Heel Walking", "Dumbbell Deadlift", "Machine Seated Row", "Machine Lat Pulldown", "Bodyweight Single-Leg Calf Raise",
            "Cable Shoulder Extension", "Cable Upper Row", "Dumbbell Hammer Curl to Press", "Dumbbell Push Press",
            "Dumbbell Rear Delt Row", "Machine Reverse Lat Pulldown (Close Grip)",
        )
        names.forEach { name ->
            println("$name → " + top(name).joinToString(" | ") { "${it.nameKey ?: it.extendedId} (${it.equipment?.key})" })
            assertTrue(name, top(name).isNotEmpty())
        }
        assertEquals(catalogExerciseId("hammer_curl"), top("Dumbbell Hammer Curl to Press").first().id)
        assertTrue(top("Cable Shoulder Extension").all { it.equipment == Equipment.CABLE })
    }

    @Test
    fun usageCountsWorkoutsSetsAndRoutines() {
        val bundle = JefitImport.parse(ImportTestFiles.fixture("jefit/export-synthetic.csv"), ZoneId.of("Europe/Stockholm"))
        val usage = bundle.nameUsage()
        assertEquals(bundle.importExerciseNames(), usage.keys)
        usage.forEach { (name, u) ->
            assertEquals(bundle.workouts.count { w -> w.exercises.any { it.name == name } }, u.workouts)
            assertEquals(bundle.routines.count { r -> r.exercises.any { it.name == name } }, u.routines)
        }
        assertEquals(bundle.setCount, usage.values.sumOf { it.sets })
    }
}
