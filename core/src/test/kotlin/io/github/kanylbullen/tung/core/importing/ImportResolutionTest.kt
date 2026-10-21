package io.github.kanylbullen.tung.core.importing

import io.github.kanylbullen.tung.core.catalogExerciseId
import io.github.kanylbullen.tung.core.command.ExerciseCandidate
import io.github.kanylbullen.tung.core.command.ExerciseRef
import io.github.kanylbullen.tung.core.extendedExerciseId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ImportResolutionTest {

    private val aliases = ImportTestFiles.jefitAliases

    /** The table is data: each target must be a core key or an extended id that exists, or the alias resolves to nothing. */
    @Test
    fun everyJefitAliasPointsAtAnExistingExercise() {
        val keys = ImportTestFiles.core.map { it.key }.toSet()
        val extended = ImportTestFiles.extended.map { it.id }.toSet()
        val file = ImportTestFiles.catalog("aliases/jefit.json").readText()
        val entries = kotlinx.serialization.json.Json.decodeFromString(AliasFile.serializer(), file)
        assertEquals("jefit", entries.source)
        assertTrue(entries.aliases.isNotEmpty())
        entries.aliases.forEach { (name, target) ->
            when (val ref = ImportAliases.aliasTarget(target)) {
                is ExerciseRef.ByKey -> assertTrue("$name → unknown key ${ref.nameKey}", ref.nameKey in keys)
                is ExerciseRef.Extended -> assertTrue("$name → unknown extended id ${ref.fedId}", ref.fedId in extended)
                else -> error("unexpected $ref")
            }
        }
        assertEquals("sorted by name", entries.aliases.keys.sorted(), entries.aliases.keys.toList())
    }

    @Test
    fun aliasesFirstThenAnExactNameElseTheReview() = runBlocking {
        val matches = resolveImportNames(
            listOf("Barbell Squat", "Dumbbell Squat", "dumbbell squat ", "Deadlift", "Heel Walking"),
            aliases,
            ImportTestFiles.resolver(),
        )
        assertEquals(ImportMatch.Matched::class, matches.getValue("Barbell Squat")::class)
        assertEquals(catalogExerciseId("back_squat"), (matches.getValue("Barbell Squat") as ImportMatch.Matched).exercise.id)
        assertTrue((matches.getValue("Barbell Squat") as ImportMatch.Matched).viaAlias)
        assertEquals(extendedExerciseId("Dumbbell_Squat"), (matches.getValue("Dumbbell Squat") as ImportMatch.Matched).exercise.id)
        assertEquals(extendedExerciseId("Dumbbell_Squat"), (matches.getValue("dumbbell squat ") as ImportMatch.Matched).exercise.id)
        // Not in the table, but the catalogue's own name.
        val deadlift = matches.getValue("Deadlift") as ImportMatch.Matched
        assertEquals(catalogExerciseId("deadlift"), deadlift.exercise.id)
        assertEquals(false, deadlift.viaAlias)
        assertTrue(matches.getValue("Heel Walking") is ImportMatch.Unmatched)
    }

    @Test
    fun aCustomExerciseOfTheSameNameIsFoundOnTheNextImport() = runBlocking {
        val custom = ExerciseCandidate(importedCustomExerciseId("jefit", "Heel Walking"), listOf("Heel Walking"))
        val match = resolveImportNames(listOf("Heel Walking"), aliases, ImportTestFiles.resolver(listOf(custom))).getValue("Heel Walking")
        assertEquals(custom.id, (match as ImportMatch.Matched).exercise.id)
    }

    @Test
    fun anAliasWhoseTargetIsGoneFallsThroughToTheName() = runBlocking {
        val broken = ImportAliases(mapOf("Bench Press" to "no_such_key"))
        val match = resolveImportNames(listOf("Bench Press"), broken, ImportTestFiles.resolver()).getValue("Bench Press")
        assertEquals(catalogExerciseId("bench_press"), (match as ImportMatch.Matched).exercise.id)
    }

    @Test
    fun theFixtureLeavesOneNameForTheReview() = runBlocking {
        val bundle = JefitImport.parse(ImportTestFiles.fixture("jefit/export-synthetic.csv"), ZoneId.of("Europe/Stockholm"))
        val unmatched = resolveImportNames(bundle.importExerciseNames(), aliases, ImportTestFiles.resolver())
            .filterValues { it is ImportMatch.Unmatched }.keys
        assertEquals(setOf("Heel Walking"), unmatched)
    }
}
